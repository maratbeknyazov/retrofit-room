package lab.roomlab

import androidx.room.*
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.http.GET
import retrofit2.http.Query as HttpQuery

data class User(val id: Long, val login: String, @SerializedName("avatar_url") val avatarUrl: String?)

interface GitHubService {
    @GET("/users")
    suspend fun users(@HttpQuery("per_page") count: Int = 5): List<User>
}

@Entity(tableName = "github_user")
data class UserEntity(@PrimaryKey val id: Long, val login: String, val avatarUrl: String?)

@Dao
interface UserDao {
    @Query("SELECT * FROM github_user ORDER BY id") fun observeAll(): Flow<List<UserEntity>>
    @Query("DELETE FROM github_user") suspend fun clear()
    @Insert suspend fun insertAll(users: List<UserEntity>)
}

class UserRepository(private val db: AppDatabase, private val api: GitHubService) {
    private val dao = db.userDao()
    val users: Flow<List<User>> = dao.observeAll().map { rows ->
        rows.map { User(it.id, it.login, it.avatarUrl) }
    }

    suspend fun refresh() {
        val rows = api.users(5).map { UserEntity(it.id, it.login, it.avatarUrl) }
        db.withTransaction {
            dao.clear()
            dao.insertAll(rows)
        }
    }
}

