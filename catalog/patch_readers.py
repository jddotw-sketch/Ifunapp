"""Apply narrow, fail-closed changes to the pinned reader sources in CI."""
import argparse
import re
from pathlib import Path


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f'Upstream changed: expected exactly one {old[:70]!r}')
    return text.replace(old, new)


def patch(root, reader):
    root = Path(root)
    java = root / 'app/src/main/java/eu/kanade/tachiyomi/data/backup'
    path = java / 'models/Backup.kt'
    text = path.read_text(encoding='utf-8')
    text = replace_once(text, 'data class Backup(\n',
                        'data class Backup(\n    @ProtoNumber(900) val catalogVersion: Int = 0,\n')
    path.write_text(text, encoding='utf-8')

    # A real SQLite integration test uses each reader's generated queries/adapters.
    app_module = (root / 'app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt').read_text(encoding='utf-8')
    start = app_module.index('            Database(\n') + 12
    depth, end = 0, start
    for end in range(start + len('Database'), len(app_module)):
        if app_module[end] == '(':
            depth += 1
        elif app_module[end] == ')':
            depth -= 1
            if depth == 0:
                break
    constructor = app_module[start:end + 1].replace('driver = get()', 'driver = driver')
    imports = '\n'.join(line for line in app_module.splitlines()
                        if line.startswith('import tachiyomi.data.') and line not in
                        ('import tachiyomi.data.Database', 'import tachiyomi.data.DatabaseHandler',
                         'import tachiyomi.data.AndroidDatabaseHandler'))
    restorer_text = (java / 'restore/restorers/MangaRestorer.kt').read_text(encoding='utf-8')
    ctor = restorer_text.split('class MangaRestorer(', 1)[1].split(') {', 1)[0]
    mock_args = '\n'.join(f'                {name} = mockk(),'
        for name in re.findall(r'private val (\w+):', ctor)
        if name not in ('handler', 'getCategories'))
    is_anime = reader == 'anikku'
    replacements = {
        'DATABASE_CONSTRUCTOR': constructor,
        '// ADAPTER_IMPORTS': imports,
        '// MOCK_CONSTRUCTOR_ARGS': mock_args,
        'QUERY': 'animesQueries' if is_anime else 'mangasQueries',
        'LOOKUP': 'getAnimeByUrlAndSource' if is_anime else 'getMangaByUrlAndSource',
        'CAT_TABLE': 'animes_categories' if is_anime else 'mangas_categories',
        'FOREIGN_ID': 'anime_id' if is_anime else 'manga_id',
        'STATE_TABLE': 'episodes' if is_anime else 'chapters',
        'STATE_COLUMNS': ('anime_id,url,name,seen,bookmark,fillermark,last_second_seen,total_seconds,episode_number,source_order,date_fetch,date_upload'
                          if is_anime else 'manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload'),
        'STATE_VALUES': ("$id,'/chapter','Keep state',1,1,1,123,456,1,0,0,0" if is_anime else "$id,'/chapter','Keep state',1,1,123,1,0,0,0"),
        'STATE_SUM': 'seen+bookmark+last_second_seen' if is_anime else 'read+bookmark+last_page_read',
        'TABLE': 'animes' if is_anime else 'mangas',
    }
    test_text = Path(__file__).with_name('reader_database_test.kt').read_text(encoding='utf-8')
    for old, new in replacements.items():
        test_text = test_text.replace(old, new)
    test_path = root / 'app/src/test/java/eu/kanade/tachiyomi/data/backup/restore/CatalogDatabaseTest.kt'
    test_path.parent.mkdir(parents=True, exist_ok=True)
    test_path.write_text(test_text, encoding='utf-8')

    path = java / 'restore/BackupRestorer.kt'
    text = path.read_text(encoding='utf-8')
    text = replace_once(text, '        val backup = BackupDecoder(context).decode(uri)', '''        val backup = BackupDecoder(context).decode(uri)

        // Only explicitly marked pipeline files use insert-only bulk restoration.
        require(backup.catalogVersion in 0..1) { "Unsupported catalog backup version" }
        if (backup.catalogVersion == 1) {
            require(!isSync) { "Catalog files cannot be used for sync" }
            if (options.libraryEntries) {
                CatalogRestoreValidation.check(backup.backupManga)
                if (options.categories) categoriesRestorer(backup.backupCategories)
                restoreAmount = backup.backupManga.size
                mangaRestorer.restoreCatalog(
                    backup.backupManga,
                    if (options.categories) backup.backupCategories else emptyList(),
                ) { processed ->
                    restoreProgress = processed
                    notifier.showRestoreProgress("Catalog import", processed, restoreAmount, false)
                        .show(Notifications.ID_RESTORE_PROGRESS)
                }
            }
            return
        }''')
    path.write_text(text, encoding='utf-8')

    path = java / 'restore/restorers/MangaRestorer.kt'
    text = path.read_text(encoding='utf-8')
    # Use the reader's own insert signature and column adapters, including Anikku seasons/fetchType.
    query = 'animesQueries' if reader == 'anikku' else 'mangasQueries'
    category_query = 'animes_categoriesQueries' if reader == 'anikku' else 'mangas_categoriesQueries'
    lookup = 'getAnimeByUrlAndSource' if reader == 'anikku' else 'getMangaByUrlAndSource'
    match = re.search(r'    private suspend fun insertManga\(manga: Manga\): Long \{(.*?)\n    \}', text, re.S)
    if not match:
        raise ValueError('Missing insertManga')
    insert = match.group(1).split('            ' + query + '.insert(', 1)[1]
    insert = '            ' + query + '.insert(' + insert.split('\n            ' + query + '.selectLastInsertedRowId()', 1)[0]
    method = '''
    /** Insert-only catalog restore. A committed chunk is its own durable checkpoint:
     * replay skips existing source/URL pairs, and interrupted transactions roll back.
     * Existing entries (even non-favorites), categories and all user state are untouched.
     */
    suspend fun restoreCatalog(
        entries: List<BackupManga>,
        backupCategories: List<BackupCategory>,
        progress: (Int) -> Unit,
    ) {
        eu.kanade.tachiyomi.data.backup.restore.CatalogRestoreValidation.check(entries)
        val categoriesByName = getCategories.await().associateBy { it.name }
        val categoryIds = backupCategories.associate { it.order to categoriesByName[it.name]?.id }
        var processed = 0
        for (chunk in entries.chunked(400)) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            handler.await(inTransaction = true) {
                for (entry in chunk) {
                    if (QUERY.LOOKUP(entry.url, entry.source, MangaMapper::mapManga)
                            .executeAsList().isNotEmpty()
                    ) continue
                    val manga = entry.getMangaImpl()
INSERT
                    val id = QUERY.selectLastInsertedRowId().executeAsOne()
                    entry.categories.mapNotNull { categoryIds[it] }.distinct().forEach { categoryId ->
                        CATEGORY.insert(id, categoryId)
                    }
                }
            }
            processed += chunk.size
            progress(processed)
        }
    }
'''.replace('QUERY', query).replace('LOOKUP', lookup).replace('CATEGORY', category_query).replace('INSERT', insert)
    text = replace_once(text, '    private suspend fun findExistingManga(', method + '\n    private suspend fun findExistingManga(')
    text = text.replace('import eu.kanade.domain.manga.interactor.UpdateManga',
                        'import kotlinx.coroutines.ensureActive\nimport eu.kanade.domain.manga.interactor.UpdateManga')
    path.write_text(text, encoding='utf-8')

    chapters = 'episodes' if reader == 'anikku' else 'chapters'
    extra = ' && it.parentId == null && it.id == null' if reader == 'anikku' else ''
    validation = '''package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupManga

object CatalogRestoreValidation {
    fun check(entries: List<BackupManga>) {
        require(entries.size in 1..50000) { "Catalog batch must contain 1 to 50,000 entries" }
        require(entries.all {
            it.url.isNotBlank() && it.title.isNotBlank() && it.CHAPTERS.isEmpty() &&
                it.history.isEmpty() && it.tracking.isEmpty() && it.mergedMangaReferences.isEmpty()EXTRA
        }) { "Catalog imports must contain metadata only" }
    }
}
'''.replace('CHAPTERS', chapters).replace('EXTRA', extra)
    (java / 'restore/CatalogRestoreValidation.kt').write_text(validation, encoding='utf-8')
    test = root / 'app/src/test/java/eu/kanade/tachiyomi/data/backup/restore/CatalogWireTest.kt'
    test.parent.mkdir(parents=True, exist_ok=True)
    test.write_text('''package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.GZIPInputStream

class CatalogWireTest {
    @Test
    fun pythonBackupDecodesWithRealReaderSchema() {
        val path = System.getenv("CATALOG_FIXTURE") ?: error("Missing CATALOG_FIXTURE")
        val bytes = GZIPInputStream(File(path).inputStream()).use { it.readBytes() }
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(1, backup.catalogVersion)
        assertEquals(5001, backup.backupManga.size)
        assertEquals(2, backup.backupCategories.size)
        assertEquals(1L, backup.backupManga[4999].categories.single())
        assertEquals(2L, backup.backupManga[5000].categories.single())
        assertEquals(-123456789012345L, backup.backupManga.first().source)
        assertEquals("Title 0 🐈", backup.backupManga.first().title)
        CatalogRestoreValidation.check(backup.backupManga)
    }

    @Test
    fun emptyCatalogIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRestoreValidation.check(emptyList())
        }
    }
}
''', encoding='utf-8')
    if reader == 'anikku':
        path = root / 'app/src/main/AndroidManifest.xml'
        text = path.read_text(encoding='utf-8').replace('android:label="@string/app_name"',
                'android:label="Anikku Library Import"').replace('android:icon="@mipmap/ic_launcher"',
                'android:icon="@drawable/catalog_launcher"')
        path.write_text(text, encoding='utf-8')
        drawable = root / 'app/src/main/res/drawable/catalog_launcher.xml'
        drawable.write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#123548" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#4DE0CF" android:pathData="M20,22h12v64h-12zM37,22h12v64h-12zM54,22h12v64h-12z" />
    <path android:fillColor="#FFFFFF" android:pathData="M76,24h7v37h12l-15,20l-15,-20h11z" />
</vector>
''', encoding='utf-8')
    path = root / 'app/build.gradle.kts'
    text = path.read_text(encoding='utf-8')
    version_file = (root / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
    sql_version = re.search(r'^sqldelight = "([^"]+)"', version_file, re.M).group(1)
    text = replace_once(text, '\ndependencies {\n',
        f'\ndependencies {{\n    testImplementation("app.cash.sqldelight:sqlite-driver:{sql_version}")\n')
    text, count = re.subn(r'versionCode = \d+', 'versionCode = 100000 + (System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 0)', text, count=1)
    if count != 1:
        raise ValueError('Missing versionCode')
    text = replace_once(text, '    buildTypes {', '''    // A persistent existing certificate may be supplied by repository secrets.
    // Never generate or commit a signing key here.
    val catalogSigning = System.getenv("SIGNING_STORE_FILE")?.let { storePath ->
        signingConfigs.create("catalogImport") {
            storeFile = file(storePath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {''')
    text = replace_once(text, '        val debug by getting {',
                        '        val debug by getting {\n            if (catalogSigning != null) signingConfig = catalogSigning')
    path.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root')
    parser.add_argument('reader', choices=['anikku', 'komikku'])
    args = parser.parse_args()
    patch(args.root, args.reader)
