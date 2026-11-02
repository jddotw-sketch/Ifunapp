# Large catalog pipeline

This directory is independent of both Android readers. Existing Anikku and Komikku
overlays, their source behavior, and Anikku's anime database remain in place.
The `catalog-pipeline` branch starts at `anikku-importer-port`; it does not replace
the unrelated app on `main` or rewrite either reader branch.

## Run on a PC or TrueNAS Linux host

Use Python 3.11 or newer with a writable persistent dataset. Run from the repository root:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r catalog/requirements.txt
python -m catalog discover --config source.json --db /data/catalog.sqlite --interval 1
python -m catalog collect --config source.json --db /data/catalog.sqlite --interval 1
python -m catalog status --config source.json --db /data/catalog.sqlite
python -m catalog export --config source.json --db /data/catalog.sqlite --reader anikku --output /data/backups
python -m catalog validate --reader anikku --file /data/backups/FILE.tachibk
```

On Windows activate `.venv\Scripts\Activate.ps1` instead. Repeat a failed command
with the same config/database to resume. Keep the SQLite database and WAL together;
use SQLite's backup API or stop the process before copying them. Only one process
may own a manifest at a time. No scheduler, hourly monitor, or server deployment is installed.

## Adapters and compatibility

There is no universal website parser. The included adapters support normalized JSON
and configurable static HTML. Each real source needs verified parsing rules,
canonical extension URLs, the exact installed extension's signed 64-bit source ID,
and an accurate pagination contract. JavaScript, login, anti-bot challenges and
source-specific APIs may require another implementation of `engine.Adapter`.
No Rule34Video production scrape or installed-extension playback test has been performed.
`example-config.json` contains a deliberately unusable example endpoint and ID.

`media_type: video` permits only Anikku; `media_type: manga` permits only Komikku.
For collection-only sources, omit `reader_sources` or set it to `{}`. Collection
works independently of either reader. Add a verified mapping and `media_type` later
to export the same database without refetching. These two export settings do not
change collection identity. After the first export, changing that reader's source
ID is rejected to prevent silently mixing incompatible backup sets.
The source ID must come from that reader's extension, not from the website or a
different reader. A declared mapping is a configuration contract, not evidence
that an arbitrary installed extension is compatible. Never invent source IDs.

JSON configuration uses `adapter: json` and a `url_template` containing `{key}`.
Each response must explicitly contain:

```json
{"exists": true, "entries": [{"url": "/reader/key", "title": "Title", "genres": []}], "next_key": null}
```

Other optional entry fields: `author`, `artist`, `description`, `thumbnail`,
`status` (reader status 0–6). URLs must already be canonical reader keys.
Fixture testing can replace the URL transport with `fixture_dir`, containing
percent-encoded page-key filenames ending in `.json`.

An HTML adapter configuration additionally supplies `base_url` and `selectors`:

```json
{
  "adapter": "html", "name": "Verified source", "media_type": "video",
  "mode": "sequential", "first_key": "1",
  "base_url": "https://example.invalid",
  "url_template": "https://example.invalid/list?page={key}",
  "reader_sources": {"anikku": 123456789},
  "selectors": {"catalog": "#catalog", "entry": ".card", "link": "a",
    "title": ".title", "thumbnail": "img", "empty": ".no-results",
    "next": "a.next", "end": ".last-page"}
}
```

These are illustrative selectors, not a working adapter for a named source.
`absolute_reader_urls: true` keeps absolute URLs only when the extension expects
them. `thumbnail_attribute` defaults to `src`; set it for lazy-loaded images.
Missing expected markup is an error; it is never taken as the catalog ending.

## Discovery, recovery and consistency

Choose `monotonic` only when every page 1 through N exists and every page after N
is definitively empty. Exponential probes plus binary search and neighbor checks
discover the boundary in logarithmic requests (under 40 in the 30,000-page test).
This still fetches pages; it does not prove monotonicity or discover every possible
source's page count without requests. A hard safety limit prevents infinite probes.
Probes are cached and reused for metadata collection.

Use `sequential` for cursor sources and gapped numbered sources. The adapter must
return the explicit next key across gaps and `null` only at the actual end. Every
listing is fetched and durably cached during discovery. Cycles and self-loops fail.
SQLite stores page order/status, attempts, error messages, cached probes, cursor
checkpoints and deduplicated metadata. Committing entries and page completion is
atomic. The source is fixed per manifest; deduplication is by canonical URL within
that source. First-seen metadata wins; this is a snapshot collector, not an updater.

HTTP 429/5xx and network timeouts retry with capped exponential backoff and
`Retry-After`. HTTP 403/404, invalid markup and invalid JSON fail visibly rather
than becoming end-of-catalog signals. Default pacing is one request per second;
the default uses one collection worker. `collect --workers 4` overlaps up to four
requests under the same global limiter/backoff. Commits stay in page order for
repeatable deduplication; the buffer is bounded by the worker count (maximum 32).

A changing catalog can shift numbered pages during a long run. Use source snapshot
tokens/stable sorting where available; otherwise completeness cannot be guaranteed.
To take a fresh catalog snapshot, use a new manifest. Cached discovery boundaries
are not automatically refreshed. Disk capacity must include cached cursor listings.

## Backups

`backup.py` defines separate Anikku and Komikku top-level schemas and encoders.
Anikku uses fields 500 (required false legacy detector), 501/502/503; the pinned
Komikku uses 1/2/101. Both contain gzip-compressed protobuf metadata subsets with
explicit favorites and no episodes/chapters, history, tracking or app preferences.
Reader schema references are the pinned Anikku commit
`6e35b41f0cc7e375d15527bd61375435f998685a` and the archived Komikku source SHA256
`9113479c0eea4151bf2d0708aea1ba40fa3d72db90eea0a1c687c0f7e288c36c`.

Each file has at most 50,000 entries; category groups contain at most 5,000 entries.
Category order values (which reader restore actually maps) and names remain stable
across files and incremental exports using persisted append-only export ordinals.
Names include the manifest's adapter identity to avoid cross-source collisions.
336,277 entries produce six files of 50,000 and one of 36,277, with 68 categories.
The final category contains 1,277 entries. IDs in backups are transport identifiers,
not forced Android database primary keys.

Exports are blocked until collection is complete unless `--allow-partial` is given.
An explicit partial export can be repeated later; use the latest index and listed
checksums. Do not mix indexes from different exports. Files are deterministic,
written to temporary files, decoded/validated, then atomically replaced. Each index
lists the exact output files and SHA256 checksums. The 256 MiB decompressed limit
rejects unusually large metadata batches instead of creating oversized reader files.

## Reader restore and app identity

`patch_readers.py` applies narrow changes after each existing overlay. Anikku keeps
`animesQueries`, anime categories, season/fetch-type fields and its own database
migration; Komikku keeps its manga tables. The script fails if expected source
anchors change. Ordinary backups retain the original restoration path.

Pipeline files carry protobuf field 900 `catalogVersion=1`. On these builds, use
the existing restore screen and choose a generated backup. Marked files take the
bulk path automatically. Sync rejects catalog files. Category creation uses the
reader's existing name matching; entries insert in transactions of 400, with one
progress notification per transaction. No per-entry episode/track/history work runs.
Existing source/URL pairs are skipped entirely, including non-favorites, preserving
user reading state, notes, edits and category assignments. Consequently pre-existing
non-favorites are NOT automatically added to the library.

The database itself is the import checkpoint: each committed chunk remains after a
crash, an incomplete transaction rolls back, and selecting the same file again skips
already-present entries. There is no separate numeric restore cursor that can skip
failed rows. Folder selection/automatic multi-file restore and a device performance
benchmark are not implemented. Import the seven files individually. Older unmodified
readers ignore the marker and use ordinary restore; use the catalog builds for the
insert-only state-preservation guarantee.

Anikku's default launcher name becomes **Anikku Library Import**, with a teal books
and white import-arrow vector icon. Existing application IDs stay the same.
Both readers use versionCode `100000 + GITHUB_RUN_NUMBER` from this workflow.
The workflow can use a permanent existing certificate from repository secrets:
`IMPORT_SIGNING_STORE_BASE64`, `IMPORT_SIGNING_STORE_PASSWORD`,
`IMPORT_SIGNING_KEY_ALIAS`, `IMPORT_SIGNING_KEY_PASSWORD`. It never generates or
commits a private key. Without those secrets builds use ephemeral debug signing
and cannot promise an update over an existing installation. Even a permanent new
key cannot update an APK signed with a different old certificate. Versioning must
continue through this workflow or a higher coordinated version sequence.

## Validation and remaining work

Run `python -m unittest discover -s catalog/tests -v`. GitHub Actions also exports
336,277 synthetic entries per reader, checks all seven files, and uploads the scale
index/checksums. It generates 5,001-entry fixtures and decodes them with the actual
Kotlin reader schemas, then runs the existing importer tests and builds both APKs.
A SQLite integration test seeds a non-favorite entry with notes, viewer flags,
categories, bookmarks and reading/watching progress. It injects an insertion failure
inside a chunk, verifies rollback, replays twice and checks existing state is unchanged.
Synthetic fixture backups are NOT real catalog downloads.

Production acceptance still needs a verified adapter/config for each desired live
source, installed-extension source-ID/URL/playback checks, Android restore tests
against a pre-populated library with a forced interruption, and on-device timings.
The code makes no claim that 336,277 entries have been crawled or restored on a phone.
