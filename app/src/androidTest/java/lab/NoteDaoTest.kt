package lab.roomlab

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.system.measureNanoTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.noteDao()
        runBlocking { dao.addNotebook(Notebook(1, "Lab")) }
    }

    @After fun close() { db.close() }

    private fun note(title: String = "Salom", time: Long = 10) =
        Note(title = title, createdAt = time, notebookId = 1)

    @Test fun insertAndRead() = runBlocking<Unit> {
        val id = dao.insert(note())
        val ids = dao.insertAll(listOf(note("Second"), note("Third")))
        assertTrue(id > 0)
        assertEquals(2, ids.size)
        assertEquals("Salom", dao.getById(id)?.title)
        val e = assertFailsWith<SQLiteConstraintException> { dao.insert(note().copy(id = id)) }
        Log.i("RoomEvidence", "insert=$id insertAll=$ids duplicate=${e.javaClass.simpleName}")
    }

    @Test fun updateReturnsCount() = runBlocking<Unit> {
        val id = dao.insert(note())
        val updated = dao.update(note("New").copy(id = id))
        val missing = dao.update(note().copy(id = 999))
        assertEquals(1, updated)
        assertEquals(0, missing)
        Log.i("RoomEvidence", "update=$updated missing=$missing")
    }

    @Test fun deleteReturnsCount() = runBlocking<Unit> {
        val id = dao.insert(note())
        val deleted = dao.delete(note().copy(id = id))
        assertEquals(1, deleted)
        assertEquals(0, dao.deleteById(id))
        assertNull(dao.getById(id))
        Log.i("RoomEvidence", "delete=$deleted")
    }

    @Test fun ordersAndFiltersByTime() = runBlocking<Unit> {
        dao.insertAll(listOf(note("Old", 10), note("New", 30), note("Middle", 20)))
        assertEquals(listOf("New", "Middle", "Old"), dao.getAll().map { it.title })
        assertEquals(listOf("New"), dao.createdAfter(20).map { it.title })
    }

    @Test fun searchesLatinAndCyrillic() = runBlocking<Unit> {
        dao.insertAll(listOf(note("Salom"), note("Привет"), note("привет")))
        val latin = dao.search("salom").first().map { it.title }
        val cyrillic = dao.search("привет").first().map { it.title }
        assertEquals(listOf("Salom"), latin)
        val capitalized = dao.search("Привет").first().map { it.title }
        // The platform SQLite build can provide Unicode-aware LIKE.
        assertTrue(cyrillic.toSet() in setOf(setOf("привет"), setOf("привет", "Привет")))
        assertTrue(capitalized.toSet() in setOf(setOf("Привет"), setOf("привет", "Привет")))
        assertEquals(cyrillic.size, capitalized.size)
        assertEquals(3, dao.count())
        Log.i("RoomEvidence", "LIKE salom=$latin привет=$cyrillic Привет=$capitalized")
    }

    @Test fun upsertUpdatesExistingRow() = runBlocking<Unit> {
        val id = dao.insert(note())
        dao.upsert(note("New").copy(id = id))
        assertEquals(1, dao.count())
        assertEquals("New", dao.getById(id)?.title)
    }

    @Test fun constraintsCascadeInvalidParentAndRestrict() = runBlocking<Unit> {
        dao.insert(note())
        dao.deleteNotebook(1)
        assertEquals(0, dao.count())
        val invalid = assertFailsWith<SQLiteConstraintException> { dao.insert(note()) }
        val restrictive = db.restrictDao()
        restrictive.add(RestrictedParent(1))
        restrictive.add(RestrictedChild(1, 1))
        val restrict = assertFailsWith<SQLiteConstraintException> { restrictive.delete(RestrictedParent(1)) }
        Log.i("RoomEvidence", "foreign key=${invalid.javaClass.simpleName} restrict=${restrict.javaClass.simpleName}")
    }

    @Test fun comparesReplaceAndUpsert() = runBlocking<Unit> {
        dao.insertAll(List(3) { note("Note $it") })
        dao.replaceNotebook(Notebook(1, "Replaced"))
        val replaced = dao.count()
        dao.insertAll(List(3) { note("Note $it") })
        dao.upsertNotebook(Notebook(1, "Updated"))
        val upserted = dao.count()
        assertEquals(0, replaced)
        assertEquals(3, upserted)
        Log.i("RoomEvidence", "REPLACE=$replaced Upsert=$upserted")
    }

    @Test fun queriesTagRelationsBothWays() = runBlocking<Unit> {
        val first = dao.insert(note("First"))
        val second = dao.insert(note("Second"))
        val a = dao.addTag(Tag(name = "A"))
        val b = dao.addTag(Tag(name = "B"))
        val c = dao.addTag(Tag(name = "C"))
        for (link in listOf(NoteTagCrossRef(first, a), NoteTagCrossRef(first, b), NoteTagCrossRef(second, b), NoteTagCrossRef(second, c))) {
            dao.attachTag(link)
        }
        val forward = dao.notesWithTags().first()
        val reverse = dao.tagsWithNotes().first()
        assertEquals(setOf("A", "B"), forward.first().tags.map { it.name }.toSet())
        assertEquals(setOf("First", "Second"), reverse.first { it.tag.id == b }.notes.map { it.title }.toSet())
    }

    @Test fun flowEmitsForNotesAndIgnoresOtherTables() = runBlocking<Unit> {
        dao.observeAll().test {
            assertEquals(emptyList<Note>(), awaitItem())
            val id = dao.insert(note())
            assertEquals("Salom", awaitItem().single().title)
            dao.update(note("Edited").copy(id = id))
            assertEquals("Edited", awaitItem().single().title)
            dao.addTag(Tag(name = "unrelated"))
            kotlinx.coroutines.delay(250)
            expectNoEvents()
        }
    }

    @Test fun convertersRoundTrip() {
        val converters = Converters()
        for (tags in listOf(emptyList(), listOf("a,b"), listOf("a\"b"))) {
            assertEquals(tags, converters.jsonToTags(converters.tagsToJson(tags)))
        }
        assertNull(converters.longToInstant(converters.instantToLong(null)))
        val instant = Instant.ofEpochMilli(1234)
        assertEquals(instant, converters.longToInstant(converters.instantToLong(instant)))
        assertEquals(Priority.HIGH, converters.stringToPriority(converters.priorityToString(Priority.HIGH)))
    }

    @Test fun mainThreadGuardAndDebugOverride() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().useRoom { guarded ->
            instrumentation.runOnMainSync {
                val error = assertFailsWith<IllegalStateException> { guarded.noteDao().getAllBlocking() }
                Log.i("RoomEvidence", "Blocking on main: ${error.message}")
                assertEquals(0, dao.getAllBlocking().size)
                Log.i("RoomEvidence", "Main thread with allowMainThreadQueries: 0 notes")
            }
        }
    }

    @Test fun queryPlanWithAndWithoutIndex() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""
            WITH RECURSIVE rows(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM rows WHERE n < 50000)
            INSERT INTO note(title, content, created_at, pinned, notebookId, priority)
            SELECT 'Note ' || n, '', n, 0, 1, 'NORMAL' FROM rows
        """.trimIndent())
        val query = "SELECT * FROM note WHERE created_at > ?"
        fun plan() = sql.query("EXPLAIN QUERY PLAN $query", arrayOf(49000)).use {
            check(it.moveToFirst())
            it.getString(3)
        }
        fun elapsed() = measureNanoTime {
            sql.query(query, arrayOf(49000)).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) { cursor.getString(1); count++ }
                assertEquals(1000, count)
            }
        }
        sql.execSQL("DROP INDEX index_note_created_at")
        val scan = plan()
        val scanTime = elapsed()
        sql.execSQL("CREATE INDEX index_note_created_at ON note(created_at)")
        val indexed = plan()
        val indexedTime = elapsed()
        assertTrue(scan.contains("SCAN"))
        assertTrue(indexed.contains("index_note_created_at"))
        Log.i("RoomEvidence", "$scan ${scanTime / 1e6}ms | $indexed ${indexedTime / 1e6}ms")
    }
}

