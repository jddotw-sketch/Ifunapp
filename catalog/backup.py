"""Metadata-only protobuf subsets, checked against the pinned readers in CI.

Use proto2 so optional zero/false values are explicitly serialized. Field numbers
are reader APIs, not interchangeable even though both use the .tachibk suffix.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path

from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

SCHEMAS = {
    'anikku': (501, 502, 503),
    'komikku': (1, 2, 101),
}


def schema(reader):
    manga_field, category_field, source_field = SCHEMAS[reader]
    file = descriptor_pb2.FileDescriptorProto(name=f'{reader}_catalog.proto', package=reader, syntax='proto2')

    def message(name, fields):
        msg = file.message_type.add(name=name)
        for number, name, kind, repeated in fields:
            f = msg.field.add(name=name, number=number, label=3 if repeated else 1)
            if isinstance(kind, str):
                f.type, f.type_name = 11, f'.{reader}.{kind}'
            else:
                f.type = kind

    # Types: int64=3, int32=5, bool=8, string=9.
    message('Entry', [(1, 'source', 3, False), (2, 'url', 9, False), (3, 'title', 9, False),
                      (4, 'artist', 9, False), (5, 'author', 9, False), (6, 'description', 9, False),
                      (7, 'genre', 9, True), (8, 'status', 5, False), (9, 'thumbnail', 9, False),
                      (13, 'date_added', 3, False), (17, 'categories', 3, True),
                      (100, 'favorite', 8, False), (111, 'initialized', 8, False)])
    message('Category', [(1, 'name', 9, False), (2, 'order', 3, False), (3, 'id', 3, False)])
    message('Source', [(1, 'name', 9, False), (2, 'source_id', 3, False)])
    fields = [(manga_field, 'entries', 'Entry', True), (category_field, 'categories', 'Category', True),
              (source_field, 'sources', 'Source', True), (900, 'catalog_version', 5, False)]
    if reader == 'anikku':
        fields.append((500, 'is_legacy', 8, False))
    message('Backup', fields)
    pool = descriptor_pool.DescriptorPool()
    pool.Add(file)
    return message_factory.GetMessageClass(pool.FindMessageTypeByName(f'{reader}.Backup'))


class AnikkuEncoder:
    reader = 'anikku'

    def new(self):
        backup = schema(self.reader)()
        backup.is_legacy = False  # Required by Anikku's new/legacy decoder detection.
        backup.catalog_version = 1
        return backup


class KomikkuEncoder:
    reader = 'komikku'

    def new(self):
        backup = schema(self.reader)()
        backup.catalog_version = 1
        return backup


ENCODERS = {'anikku': AnikkuEncoder, 'komikku': KomikkuEncoder}


def validate(path, reader, expected=None):
    # A bounded full backup is decoded; never allow unbounded gzip expansion.
    with gzip.open(path, 'rb') as stream:
        raw = stream.read(256 * 1024 * 1024 + 1)
    if len(raw) > 256 * 1024 * 1024:
        raise ValueError('Backup exceeds 256 MiB decompressed')
    backup = schema(reader)()
    backup.ParseFromString(raw)
    if backup.catalog_version != 1 or not 0 < len(backup.entries) <= 50_000:
        raise ValueError('Invalid catalog backup or wrong reader')
    if expected is not None and len(backup.entries) != expected:
        raise ValueError('Backup entry count mismatch')
    orders = {c.order for c in backup.categories}
    if len(orders) != len(backup.categories):
        raise ValueError('Duplicate category order')
    sources = {s.source_id for s in backup.sources}
    keys = set()
    for entry in backup.entries:
        key = (entry.source, entry.url)
        if not entry.title or not entry.url or key in keys or entry.source not in sources:
            raise ValueError('Invalid or duplicate entry')
        if len(entry.categories) != 1 or entry.categories[0] not in orders:
            raise ValueError('Invalid category assignment')
        keys.add(key)
    return backup


def export(store, adapter, reader, directory, allow_partial=False):
    if reader not in adapter.reader_sources:
        raise ValueError(f'{adapter.name} has no compatible {reader} source mapping')
    stats = store.stats()
    if not allow_partial and (not stats['discovery_complete'] or
            any(s != 'done' and count for s, count in stats['pages'].items())):
        raise ValueError('Collection incomplete; use --allow-partial explicitly')
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    # Append-only ordinals survive later collection and repeated exports. No OFFSET scans.
    with store.db:
        offset = store.db.execute('SELECT coalesce(max(ordinal),0) FROM export_entries WHERE reader=?',
                                  (reader,)).fetchone()[0]
        store.db.execute('''INSERT INTO export_entries(reader,entry_id,ordinal)
            SELECT ?,id,? + row_number() OVER (ORDER BY id) FROM entries
            WHERE id NOT IN (SELECT entry_id FROM export_entries WHERE reader=?)''', (reader, offset, reader))
    total = store.db.execute('SELECT count(*) FROM export_entries WHERE reader=?', (reader,)).fetchone()[0]
    output = []
    namespace = adapter.identity[:12]
    for start in range(0, total, 50_000):
        backup = ENCODERS[reader]().new()
        backup.sources.add(name=adapter.name, source_id=adapter.reader_sources[reader])
        rows = store.db.execute('''SELECT e.payload,x.ordinal FROM export_entries x
            JOIN entries e ON e.id=x.entry_id WHERE x.reader=? AND x.ordinal>? AND x.ordinal<=?
            ORDER BY x.ordinal''', (reader, start, start + 50_000))
        categories = set()
        for row in rows:
            data = json.loads(row['payload'])
            order = (row['ordinal'] - 1) // 5_000 + 1
            categories.add(order)
            entry = backup.entries.add(source=adapter.reader_sources[reader], url=data['url'],
                                       title=data['title'], favorite=True, initialized=False,
                                       date_added=0, status=data['status'])
            for name in ('artist', 'author', 'description', 'thumbnail'):
                if data[name]:
                    setattr(entry, name, data[name])
            entry.genre.extend(data['genres'])
            entry.categories.append(order)  # Reader restore maps category ORDER, not database ID.
        for order in sorted(categories):
            backup.categories.add(name=f'Catalog {namespace} {order:04d}', order=order, id=order)
        path = directory / f'{namespace}-{reader}-{start // 50_000 + 1:04d}.tachibk'
        temporary = path.with_suffix('.tmp')
        raw = backup.SerializeToString(deterministic=True)
        if len(raw) > 256 * 1024 * 1024:
            raise ValueError('Metadata too large for a bounded backup')
        with open(temporary, 'wb') as target:
            target.write(gzip.compress(raw, mtime=0))
            target.flush()
            os.fsync(target.fileno())
        validate(temporary, reader, len(backup.entries))
        temporary.replace(path)
        output.append({'file': path.name, 'entries': len(backup.entries),
                       'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    manifest = {'reader': reader, 'adapter': adapter.identity, 'partial': allow_partial,
                'entries': total, 'files': output}
    index = directory / f'{namespace}-{reader}-index.json'
    tmp = index.with_suffix('.tmp')
    tmp.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    tmp.replace(index)
    return manifest
