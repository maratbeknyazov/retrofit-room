package lab.retrofitlab

import com.google.gson.annotations.SerializedName
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class User(val id: Long, val login: String, @SerializedName("avatar_url") val avatarUrl: String?)

interface GitHubService {
    @GET("/users")
    suspend fun users(@Query("per_page") n: Int = 5): List<User>

    @GET("/users/{login}")
    suspend fun user(@Path("login") login: String): User
}

object ApiInstance {
    private val retrofit by lazy {
        val logging = HttpLoggingInterceptor { android.util.Log.d("OkHttp", it) }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(OkHttpClient.Builder().addInterceptor(logging).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    fun <T> createService(type: Class<T>): T = retrofit.create(type)
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data object NetworkError : ApiResult<Nothing>
    data class HttpError(val code: Int) : ApiResult<Nothing>
    data class OtherError(val message: String) : ApiResult<Nothing>
}

suspend fun <T> safeApiCall(call: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(call())
} catch (e: HttpException) {
    ApiResult.HttpError(e.code())
} catch (e: IOException) {
    ApiResult.NetworkError
} catch (e: SecurityException) {
    ApiResult.OtherError(e.javaClass.simpleName)
}

