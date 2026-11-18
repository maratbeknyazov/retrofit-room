package lab.roomlab

import androidx.lifecycle.viewmodel.initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

private class FakeNoteDao : NoteRepository {
    val rows = MutableStateFlow<List<Note>>(emptyList())
    override fun observeAll() = rows
    override fun search(query: String) = rows.map { notes -> notes.filter { it.title.contains(query, true) } }
    override suspend fun insert(note: Note): Long {
        val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
        rows.value += note.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModelTest {
    @Test fun loadsAddsDeletesAndValidatesTitles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val dao = FakeNoteDao()
            dao.insert(Note(title = "existing", notebookId = 1))
            val vm = viewModel(store, dao)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notes.collect {} }
            advanceTimeBy(301)
            runCurrent()
            assertEquals(listOf("existing"), vm.notes.value.map { it.title })
            assertFalse(vm.add("  ", 1))
            assertFalse(vm.add("x".repeat(101), 1))
            runCurrent()
            assertEquals(1, dao.rows.value.size)
            assertTrue(vm.add("  new  ", 1))
            runCurrent()
            assertEquals(listOf("existing", "new"), dao.rows.value.map { it.title })
            vm.delete(2)
            runCurrent()
            assertEquals(listOf("existing"), vm.notes.value.map { it.title })
            assertTrue(vm.add("x".repeat(100), 1))
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun sortsFiveNotesWithDuplicateTitles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val dao = FakeNoteDao()
            dao.rows.value = listOf(
                Note(id = 1, title = "Zulu", createdAt = 10, notebookId = 1),
                Note(id = 2, title = "Alpha", createdAt = 20, notebookId = 1),
                Note(id = 3, title = "Alpha", createdAt = 30, notebookId = 1),
                Note(id = 4, title = "Beta", createdAt = 40, notebookId = 1, pinned = true),
                Note(id = 5, title = "Gamma", createdAt = 50, notebookId = 1)
            )
            val vm = viewModel(store, dao)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notes.collect {} }
            advanceTimeBy(301)
            runCurrent()
            assertEquals(listOf(5L, 4L, 3L, 2L, 1L), vm.notes.value.map { it.id })
            vm.sortOrder.value = SortOrder.Title
            runCurrent()
            assertEquals(listOf(3L, 2L, 4L, 5L, 1L), vm.notes.value.map { it.id })
            vm.sortOrder.value = SortOrder.Pinned
            runCurrent()
            assertEquals(listOf(4L, 5L, 3L, 2L, 1L), vm.notes.value.map { it.id })
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(store: androidx.lifecycle.ViewModelStore, repo: NoteRepository): NoteViewModel {
        val factory = androidx.lifecycle.viewmodel.viewModelFactory {
            initializer { NoteViewModel(repo) }
        }
        return androidx.lifecycle.ViewModelProvider(store, factory)[NoteViewModel::class.java]
    }
}

