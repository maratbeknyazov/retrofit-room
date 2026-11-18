package lab.roomlab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

interface NoteRepository {
    fun observeAll(): Flow<List<Note>>
    fun search(query: String): Flow<List<Note>>
    suspend fun insert(note: Note): Long
    suspend fun delete(id: Long)
}

class RoomNoteRepository(private val dao: NoteDao) : NoteRepository {
    override fun observeAll() = dao.observeAll()
    override fun search(query: String) = dao.search(query)
    override suspend fun insert(note: Note) = dao.insert(note)
    override suspend fun delete(id: Long) { dao.deleteById(id) }
}

sealed interface SortOrder {
    data object Date : SortOrder
    data object Title : SortOrder
    data object Pinned : SortOrder
}

data class NoteUi(val id: Long, val title: String, val preview: String, val dateText: String)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteViewModel(private val repo: NoteRepository) : ViewModel() {
    constructor(noteDao: NoteDao) : this(RoomNoteRepository(noteDao))

    val search = MutableStateFlow("")
    val sortOrder = MutableStateFlow<SortOrder>(SortOrder.Date)
    val validation = MutableStateFlow<String?>(null)
    private val byDate = compareByDescending<Note> { it.createdAt }.thenByDescending { it.id }
    private val filtered = search.debounce(300).distinctUntilChanged().flatMapLatest {
        if (it.isEmpty()) repo.observeAll() else repo.search(it)
    }

    val notes = combine(filtered, sortOrder) { rows, order ->
        val comparator = when (order) {
            SortOrder.Date -> byDate
            SortOrder.Title -> compareBy<Note, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.then(byDate)
            SortOrder.Pinned -> compareByDescending<Note> { it.pinned }.then(byDate)
        }
        rows.sortedWith(comparator).map { it.toUi() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, notebookId: Long, pinned: Boolean = false, content: String = ""): Boolean {
        val trimmed = title.trim()
        validation.value = when {
            trimmed.isEmpty() -> "Enter a title"
            trimmed.length > 100 -> "Title must have at most 100 characters"
            else -> null
        }
        if (validation.value != null) return false
        viewModelScope.launch {
            repo.insert(Note(title = trimmed, content = content, notebookId = notebookId, pinned = pinned))
        }
        return true
    }

    fun delete(id: Long) { viewModelScope.launch { repo.delete(id) } }

    internal fun checkSuspendQuery(dao: NoteDao, show: (String) -> Unit) {
        viewModelScope.launch { show("Suspend returned ${dao.getAll().size} notes on ${Thread.currentThread().name}") }
    }

    companion object {
        fun factory(db: AppDatabase) = viewModelFactory {
            initializer { NoteViewModel(db.noteDao()) }
        }
    }
}

fun Note.toUi() = NoteUi(id, title, content.take(80), Instant.ofEpochMilli(createdAt).toString().take(10))

