package lab.roomlab

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        setContent {
            MaterialTheme {
                val vm: NoteViewModel = viewModel(factory = NoteViewModel.factory(db))
                LaunchedEffect(vm) { Log.d("RoomLab", "factory ViewModel: ${vm.hashCode()}") }
                NoteScreen(vm, db.noteDao())
            }
        }
    }
}

@Composable
private fun NoteScreen(vm: NoteViewModel, dao: NoteDao) {
    val search by vm.search.collectAsStateWithLifecycle()
    val sort by vm.sortOrder.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val error by vm.validation.collectAsStateWithLifecycle()
    val notebookFlow = remember(dao) { dao.notebooks() }
    val groups by notebookFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var notebook by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf("") }
    var debug by remember { mutableStateOf(false) }
    var threadResult by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Notes", style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedTextField(notebook, { notebook = it }, label = { Text("Notebook name") }, singleLine = true)
            Button(onClick = {
                val name = notebook.trim()
                notebook = ""
                scope.launch { dao.addNotebook(Notebook(name = name)) }
            }, enabled = notebook.isNotBlank()) { Text("Add notebook") }
        }
        item {
            OutlinedTextField(title, { title = it }, label = { Text("Note title") }, singleLine = true)
            OutlinedTextField(content, { content = it }, label = { Text("Note text") }, maxLines = 3)
            Row {
                FilterChip(selected = pinned, onClick = { pinned = !pinned }, label = { Text("Pinned") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            OutlinedTextField(search, { vm.search.value = it }, label = { Text("Search") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Date" to SortOrder.Date, "Title" to SortOrder.Title, "Pinned" to SortOrder.Pinned).forEach { (label, order) ->
                    FilterChip(selected = sort == order, onClick = { vm.sortOrder.value = order }, label = { Text(label) })
                }
            }
            OutlinedTextField(tag, { tag = it }, label = { Text("Tag to attach") }, singleLine = true)
        }
        for (group in groups) {
            val id = group.notebook.id
            item(key = "notebook-$id") {
                Text(group.notebook.name, style = MaterialTheme.typography.titleLarge)
                Row {
                    Button(onClick = {
                        if (vm.add(title, id, pinned, content)) {
                            title = ""
                            content = ""
                        }
                    }) { Text("Add note") }
                    TextButton(onClick = { scope.launch { dao.deleteNotebook(id) } }) { Text("Delete notebook") }
                }
            }
            val ids = group.notes.map { it.id }.toSet()
            items(notes.filter { it.id in ids }, key = { "note-${it.id}" }) { note ->
                Text(note.title, style = MaterialTheme.typography.titleMedium)
                if (note.preview.isNotEmpty()) Text(note.preview)
                Text(note.dateText)
                Row {
                    TextButton(onClick = { vm.delete(note.id) }) { Text("Delete note") }
                    TextButton(enabled = tag.isNotBlank(), onClick = {
                        val name = tag.trim()
                        scope.launch {
                            val existing = dao.tagsWithNotes().first().find { it.tag.name == name }
                            val tagId = existing?.tag?.id ?: dao.addTag(Tag(name = name))
                            dao.attachTag(NoteTagCrossRef(note.id, tagId))
                        }
                    }) { Text("Attach tag") }
                }
            }
        }
        if (BuildConfig.DEBUG) item {
            TextButton(onClick = { debug = !debug }) { Text("Debug relations and threads") }
            if (debug) {
                val noteTags by remember(dao) { dao.notesWithTags() }.collectAsStateWithLifecycle(emptyList())
                val tagNotes by remember(dao) { dao.tagsWithNotes() }.collectAsStateWithLifecycle(emptyList())
                noteTags.forEach { row -> Text("${row.note.title} → ${row.tags.joinToString { it.name }}") }
                tagNotes.forEach { row -> Text("${row.tag.name} → ${row.notes.joinToString { it.title }}") }
                Button(onClick = {
                    threadResult = try {
                        "Blocking returned ${dao.getAllBlocking().size} notes"
                    } catch (e: IllegalStateException) {
                        Log.d("RoomLab", "Blocking query", e)
                        "${e.javaClass.simpleName}: ${e.message}"
                    }
                }) { Text("Blocking query") }
                Button(onClick = { vm.checkSuspendQuery(dao) { threadResult = it } }) {
                    Text("Suspend query")
                }
                Text(threadResult)
            }
        }
    }
}
