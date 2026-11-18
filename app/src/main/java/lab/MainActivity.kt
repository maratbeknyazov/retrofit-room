package lab.retrofitlab

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Loading : UiState
    data class Success(val users: List<User>) : UiState
    data class Error(val message: String, val showRetry: Boolean, val showWifi: Boolean) : UiState
}

class UsersViewModel : ViewModel() {
    private val service by lazy { ApiInstance.createService(GitHubService::class.java) }
    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users = _users.asStateFlow()
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _detail = MutableStateFlow<User?>(null)
    val detail = _detail.asStateFlow()
    private var request: Job? = null
    private var failures = 0
    private var failedCall: (() -> Unit)? = null

    fun load() {
        if (request != null) return
        loadUsers()
    }

    fun reload() {
        failures = 0
        loadUsers()
    }

    private fun loadUsers(): Unit = runCall({ loadUsers() }, { service.users(5) }) {
        _users.value = it
        _detail.value = null
    }

    fun select(login: String) {
        failures = 0
        loadDetail(login)
    }

    private fun loadDetail(login: String) {
        runCall({ loadDetail(login) }, { service.user(login) }) { _detail.value = it }
    }

    fun retry() {
        if (failures < 3) failedCall?.invoke()
    }

    private fun <T> runCall(retry: () -> Unit, call: suspend () -> T, onSuccess: (T) -> Unit) {
        request?.cancel()
        failedCall = retry
        _state.value = UiState.Loading
        request = viewModelScope.launch {
            when (val result = safeApiCall(call)) {
                is ApiResult.Success -> {
                    failures = 0
                    failedCall = null
                    onSuccess(result.value)
                    _state.value = UiState.Success(_users.value)
                }
                ApiResult.NetworkError -> {
                    failures++
                    _state.value = UiState.Error("No network", failures < 3, failures >= 3)
                }
                is ApiResult.HttpError -> _state.value = UiState.Error("Server error: ${result.code}", false, false)
                is ApiResult.OtherError -> _state.value = UiState.Error(result.message, false, false)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                UsersScreen(openWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
            }
        }
    }
}

@Composable
private fun UsersScreen(openWifi: () -> Unit, vm: UsersViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val users by vm.users.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("GitHub users", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::reload) { Text("Reload") }
            if (BuildConfig.DEBUG) {
                OutlinedButton(onClick = { vm.select("no-such-login-xyz") }) { Text("Test 404") }
            }
        }
        when (val current = state) {
            UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> {
                Text(current.message)
                if (current.showRetry) Button(onClick = vm::retry) { Text("Retry") }
                if (current.showWifi) Button(onClick = openWifi) { Text("Open Wi-Fi settings") }
            }
            is UiState.Success -> LazyColumn {
                items(users, key = { it.id }) { user ->
                    Column(Modifier.fillMaxWidth().clickable { vm.select(user.login) }.padding(12.dp)) {
                        Text(user.login)
                        detail?.takeIf { it.login == user.login }?.let {
                            Text("id: ${it.id}")
                            Text("avatarUrl: ${it.avatarUrl}")
                        }
                    }
                }
            }
        }
    }
}

