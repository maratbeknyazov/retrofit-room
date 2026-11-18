package lab.roomlab

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertFailsWith
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test"

    @After fun deleteDatabase() { context.deleteDatabase(name) }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO notebook (id, name) VALUES (1, 'Lab')")
        db.execSQL("INSERT INTO note (id, title, body, created_at, pinned, notebookId, archived) VALUES (1, 'First', 'Text', 10, 0, 1, 0), (2, 'Second', 'Other', 20, 0, 1, 1)")
        db.execSQL("INSERT INTO Tag (id, name) VALUES (1, 'example')")
        db.execSQL("INSERT INTO NoteTagCrossRef VALUES (1, 1)")
    }

    @Test fun addsDefaultAndPreservesBothNotes() {
        helper.createDatabase(name, 1).use(::seed)
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT id, priority FROM note ORDER BY id").use {
                for (id in 1..2) {
                    assertTrue(it.moveToNext())
                    assertEquals(id, it.getInt(0))
                    assertEquals("NORMAL", it.getString(1))
                }
                assertFalse(it.moveToNext())
            }
        }
    }

    @Test fun renamesBodyThenDropsArchivedWithoutLosingTags() {
        helper.createDatabase(name, 2).use(::seed)
        helper.runMigrationsAndValidate(name, 3, true).use { db ->
            db.query("SELECT content FROM note WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("Text", it.getString(0))
            }
        }
        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT content, priority FROM note ORDER BY id").use {
                assertEquals(2, it.count)
                assertTrue(it.moveToFirst())
                assertEquals("Text", it.getString(0))
                assertEquals("NORMAL", it.getString(1))
                assertEquals(-1, it.getColumnIndex("archived"))
            }
            db.query("SELECT * FROM NoteTagCrossRef").use { assertEquals(1, it.count) }
        }
    }

    @Test fun missingMigrationFailsAndDebugFallbackWipesData() {
        helper.createDatabase(name, 2).use(::seed)
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().useRoom { db ->
            val error = assertFailsWith<IllegalStateException> { db.openHelper.writableDatabase }
            android.util.Log.i("RoomEvidence", "Missing migration: ${error.message}")
        }
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .fallbackToDestructiveMigration().build().useRoom { db ->
                db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM note").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
    }

    @Test fun wrongDefaultFailsSchemaValidation() {
        helper.createDatabase(name, 1).use(::seed)
        val broken = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE note ADD COLUMN priority TEXT NOT NULL DEFAULT 'WRONG'")
            }
        }
        val error = assertFailsWith<IllegalStateException> {
            helper.runMigrationsAndValidate(name, 2, true, broken).close()
        }
        android.util.Log.i("RoomEvidence", "Invalid migration: ${error.message}")
    }
}

