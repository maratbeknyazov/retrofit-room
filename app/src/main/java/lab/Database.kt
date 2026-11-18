package lab.roomlab

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Priority { NORMAL, HIGH }

class Converters {
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
    @TypeConverter fun tagsToJson(value: List<String>): String = Json.encodeToString(value)
    @TypeConverter fun jsonToTags(value: String): List<String> = Json.decodeFromString(value)
    @TypeConverter fun priorityToString(value: Priority): String = value.name
    @TypeConverter fun stringToPriority(value: String): Priority = Priority.valueOf(value)
}

@Entity(tableName = "notebook")
data class Notebook(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "note",
    foreignKeys = [ForeignKey(entity = Notebook::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("created_at"), Index("notebookId")]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val notebookId: Long,
    @ColumnInfo(defaultValue = "'NORMAL'") val priority: Priority = Priority.NORMAL
) {
    @Ignore var isSelected: Boolean = false
}

data class Address(val street: String, val city: String)

@Entity
data class Contact(
    @PrimaryKey val id: Long,
    @Embedded val address: Address,
    @Embedded(prefix = "work_") val workAddress: Address
)

@Entity
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    primaryKeys = ["noteId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class NoteTagCrossRef(val noteId: Long, val tagId: Long)

data class NotebookWithNotes(
    @Embedded val notebook: Notebook,
    @Relation(parentColumn = "id", entityColumn = "notebookId") val notes: List<Note>
)

data class NoteWithTags(
    @Embedded val note: Note,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(NoteTagCrossRef::class, "noteId", "tagId"))
    val tags: List<Tag>
)

data class TagWithNotes(
    @Embedded val tag: Tag,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(NoteTagCrossRef::class, "tagId", "noteId"))
    val notes: List<Note>
)

@Dao
interface NoteDao {
    @Insert suspend fun insert(note: Note): Long
    @Insert suspend fun insertAll(notes: List<Note>): List<Long>
    @Update suspend fun update(note: Note): Int
    @Delete suspend fun delete(note: Note): Int
    @Query("DELETE FROM note WHERE id = :id") suspend fun deleteById(id: Long): Int
    @Upsert suspend fun upsert(note: Note)
    @Query("SELECT * FROM note ORDER BY created_at DESC, id DESC") suspend fun getAll(): List<Note>
    @Query("SELECT * FROM note ORDER BY created_at DESC, id DESC") fun observeAll(): Flow<List<Note>>
    @Query("SELECT * FROM note ORDER BY created_at DESC, id DESC") fun getAllBlocking(): List<Note>
    @Query("SELECT * FROM note WHERE id = :id") suspend fun getById(id: Long): Note?
    @Query("SELECT * FROM note WHERE title LIKE '%' || :q || '%' ORDER BY created_at DESC, id DESC")
    fun search(q: String): Flow<List<Note>>
    @Query("SELECT COUNT(*) FROM note") suspend fun count(): Int
    @Query("SELECT * FROM note WHERE created_at > :since ORDER BY created_at DESC")
    suspend fun createdAfter(since: Long): List<Note>

    @Insert suspend fun addNotebook(notebook: Notebook): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun replaceNotebook(notebook: Notebook)
    @Upsert suspend fun upsertNotebook(notebook: Notebook)
    @Query("DELETE FROM notebook WHERE id = :id") suspend fun deleteNotebook(id: Long)
    @Transaction @Query("SELECT * FROM notebook ORDER BY id")
    fun notebooks(): Flow<List<NotebookWithNotes>>

    @Insert suspend fun addTag(tag: Tag): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun attachTag(link: NoteTagCrossRef)
    @Transaction @Query("SELECT * FROM note ORDER BY id") fun notesWithTags(): Flow<List<NoteWithTags>>
    @Transaction @Query("SELECT * FROM Tag ORDER BY id") fun tagsWithNotes(): Flow<List<TagWithNotes>>
}

// The Room tests cover both the production cascade and a RESTRICT relationship.
@Entity
data class RestrictedParent(@PrimaryKey val id: Long)

@Entity(
    foreignKeys = [ForeignKey(entity = RestrictedParent::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("parentId")]
)
data class RestrictedChild(@PrimaryKey val id: Long, val parentId: Long)

@Dao
interface RestrictDao {
    @Insert suspend fun add(parent: RestrictedParent)
    @Insert suspend fun add(child: RestrictedChild)
    @Delete suspend fun delete(parent: RestrictedParent)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE note ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
    }
}

@RenameColumn(tableName = "note", fromColumnName = "body", toColumnName = "content")
class RenameBody : AutoMigrationSpec

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Dropping the old parent table can cascade into the junction table.
        db.execSQL("CREATE TEMP TABLE saved_note_tags AS SELECT noteId, tagId FROM NoteTagCrossRef")
        db.execSQL("DROP TABLE NoteTagCrossRef")
        db.execSQL("""
            CREATE TABLE new_note (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                notebookId INTEGER NOT NULL,
                priority TEXT NOT NULL DEFAULT 'NORMAL',
                FOREIGN KEY(notebookId) REFERENCES notebook(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO new_note(id, title, content, created_at, pinned, notebookId, priority)
            SELECT id, title, content, created_at, pinned, notebookId, priority FROM note
        """.trimIndent())
        db.execSQL("DROP TABLE note")
        db.execSQL("ALTER TABLE new_note RENAME TO note")
        db.execSQL("CREATE INDEX index_note_created_at ON note(created_at)")
        db.execSQL("CREATE INDEX index_note_notebookId ON note(notebookId)")
        db.execSQL("""
            CREATE TABLE NoteTagCrossRef (
                noteId INTEGER NOT NULL,
                tagId INTEGER NOT NULL,
                PRIMARY KEY(noteId, tagId),
                FOREIGN KEY(noteId) REFERENCES note(id) ON DELETE CASCADE,
                FOREIGN KEY(tagId) REFERENCES Tag(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_NoteTagCrossRef_tagId ON NoteTagCrossRef(tagId)")
        db.execSQL("INSERT INTO NoteTagCrossRef SELECT noteId, tagId FROM saved_note_tags")
        db.execSQL("DROP TABLE saved_note_tags")
    }
}

@Database(
    entities = [Note::class, Notebook::class, Contact::class, Tag::class, NoteTagCrossRef::class, UserEntity::class, RestrictedParent::class, RestrictedChild::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 2, to = 3, spec = RenameBody::class)]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun userDao(): UserDao
    abstract fun restrictDao(): RestrictDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
                .apply {
                    if (BuildConfig.DEBUG) {
                        setQueryCallback({ sql, args -> Log.d("RoomSQL", "$sql | $args") }, Executor { it.run() })
                    }
                }
                .build().also { instance = it }
        }
    }
}


