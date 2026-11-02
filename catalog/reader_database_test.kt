package eu.kanade.tachiyomi.data.backup.restore

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
// ADAPTER_IMPORTS

class CatalogDatabaseTest {
    @Test
    fun interruptedRestoreReplaysWithoutChangingExistingUserState() = runBlocking {
        Injekt.addSingletonFactory {
            GetCustomMangaInfo(object : CustomMangaRepository {
                override fun get(mangaId: Long) = null
                override fun set(mangaInfo: CustomMangaInfo) = Unit
            })
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val db = DATABASE_CONSTRUCTOR
            val handler = object : DatabaseHandler by mockk<DatabaseHandler>() {
                override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T =
                    if (inTransaction) db.transactionWithResult { runBlocking { block(db) } } else block(db)
            }
            val categories = mockk<GetCategories>()
            coEvery { categories.await() } returns emptyList()
            val restorer = MangaRestorer(
                handler = handler,
                getCategories = categories,
                fetchInterval = FetchInterval(mockk()),
                // MOCK_CONSTRUCTOR_ARGS
            )
            val original = BackupManga(source = 7L, url = "/existing", title = "Original")
            restorer.restoreCatalog(listOf(original), emptyList()) {}
            val id = db.QUERY.LOOKUP("/existing", 7L, MangaMapper::mapManga).executeAsOne().id
            driver.execute(null, "UPDATE TABLE SET notes='keep notes', favorite=0, viewer=77 WHERE _id=$id", 0)
            db.categoriesQueries.insert("Keep category", 0L, 0L, 0L)
            val categoryId = db.categoriesQueries.selectLastInsertedRowId().executeAsOne()
            driver.execute(null, "INSERT INTO CAT_TABLE(FOREIGN_ID,category_id) VALUES($id,$categoryId)", 0)
            driver.execute(null, "INSERT INTO STATE_TABLE(STATE_COLUMNS) VALUES(STATE_VALUES)", 0)
            val before = db.QUERY.LOOKUP("/existing", 7L, MangaMapper::mapManga).executeAsOne()
            fun number(sql: String): Long = driver.executeQuery(null, sql, { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0)!!)
            }, 0).value
            val stateBefore = number("SELECT STATE_SUM FROM STATE_TABLE")
            val categoryBefore = number("SELECT category_id FROM CAT_TABLE WHERE FOREIGN_ID=$id")
            val entries = listOf(BackupManga(source = 7L, url = "/existing", title = "Must not overwrite")) +
                (1..801).map { BackupManga(source = 7L, url = "/bulk/$it", title = "Item $it") }
            driver.execute(null, "CREATE TRIGGER fail_catalog BEFORE INSERT ON TABLE WHEN new.url='/bulk/500' BEGIN SELECT RAISE(ABORT,'interrupted'); END", 0)
            assertThrows(Exception::class.java) {
                runBlocking { restorer.restoreCatalog(entries, emptyList()) {} }
            }
            assertEquals(400L, number("SELECT count(*) FROM TABLE"))
            driver.execute(null, "DROP TRIGGER fail_catalog", 0)
            restorer.restoreCatalog(entries, emptyList()) {}
            restorer.restoreCatalog(entries, emptyList()) {}
            assertEquals(802L, number("SELECT count(*) FROM TABLE"))
            assertEquals(before, db.QUERY.LOOKUP("/existing", 7L, MangaMapper::mapManga).executeAsOne())
            assertEquals(stateBefore, number("SELECT STATE_SUM FROM STATE_TABLE"))
            assertEquals(categoryBefore, number("SELECT category_id FROM CAT_TABLE WHERE FOREIGN_ID=$id"))
        } finally {
            driver.close()
        }
    }
}
