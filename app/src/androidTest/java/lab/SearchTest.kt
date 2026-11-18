package lab.roomlab

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchTest {
    @Test fun recordsQueriesForThreeCharacters() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queries = Channel<String>(Channel.UNLIMITED)
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback({ sql, args ->
                if ("WHERE title LIKE" in sql) queries.trySend("$sql | $args")
            }, Executor { it.run() })
            .build().useRoom { db ->
                for (debounceEnabled in listOf(true, false)) {
                    val input = MutableStateFlow("initial")
                    val source = if (debounceEnabled) input.debounce(300) else input
                    val collector = launch { source.flatMapLatest { db.noteDao().search(it) }.collect {} }
                    try {
                        withTimeout(5000) { queries.receive() }
                        val statements = mutableListOf<String>()
                        for (prefix in listOf("R", "Ro", "Roo")) {
                            input.value = prefix
                            if (!debounceEnabled) statements += withTimeout(5000) { queries.receive() }
                            delay(40)
                        }
                        if (debounceEnabled) statements += withTimeout(5000) { queries.receive() }
                        delay(350)
                        assertTrue(queries.tryReceive().isFailure)
                        assertEquals(if (debounceEnabled) 1 else 3, statements.size)
                        statements.forEach { Log.i("RoomEvidence", "debounce=$debounceEnabled $it") }
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            }
    }
}
