package lab.roomlab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(AndroidJUnit4::class)
class UserCacheTest {
    @Test fun offlineStartAnd404BothKeepTheCache() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "user-cache-test"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        fun service(url: String) = Retrofit.Builder().baseUrl(url)
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(GitHubService::class.java)

        context.deleteDatabase(name)
        try {
            val offlineService = MockWebServer().use { server ->
                server.start()
                val api = service(server.url("/").toString())
                server.enqueue(MockResponse().setBody("""[{"id":1,"login":"octocat","avatar_url":"https://example.org/avatar"}]"""))
                open().useRoom { db ->
                    val repo = UserRepository(db, api)
                    assertTrue(repo.users.first().isEmpty())
                    repo.refresh()
                    assertEquals("octocat", repo.users.first().single().login)
                }
                assertEquals("/users?per_page=5", server.takeRequest().path)
                api
            }

            open().useRoom { db ->
                val offline = UserRepository(db, offlineService)
                val cached = offline.users.first()
                assertEquals("octocat", cached.single().login)
                assertFailsWith<IOException> { offline.refresh() }
                assertEquals(cached, offline.users.first())

                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse().setResponseCode(404))
                    val repo = UserRepository(db, service(server.url("/").toString()))
                    assertEquals(404, assertFailsWith<HttpException> { repo.refresh() }.code())
                    assertEquals(cached, repo.users.first())
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}

