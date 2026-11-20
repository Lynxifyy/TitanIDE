package dev.titanide.app.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.data.db.HttpRequestEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

data class HttpResult(
    val status: Int = 0,
    val statusText: String = "",
    val durationMs: Long = 0,
    val body: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
)

data class ApiTesterState(
    val method: String = "GET",
    val url: String = "https://api.github.com/rate_limit",
    val headers: String = "",
    val body: String = "",
    val running: Boolean = false,
    val result: HttpResult? = null,
    val history: List<HttpRequestEntity> = emptyList(),
)

class ApiTesterViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(ApiTesterState())
    val state: StateFlow<ApiTesterState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            container.database.httpRequestDao().observeAll().collect { history ->
                _state.value = _state.value.copy(history = history)
            }
        }
    }

    fun setMethod(method: String) {
        _state.value = _state.value.copy(method = method)
    }

    fun setUrl(url: String) {
        _state.value = _state.value.copy(url = url)
    }

    fun setHeaders(headers: String) {
        _state.value = _state.value.copy(headers = headers)
    }

    fun setBody(body: String) {
        _state.value = _state.value.copy(body = body)
    }

    /** Executes the request for real. Failures are reported as failures. */
    fun send() {
        val current = _state.value
        if (current.url.isBlank()) return
        _state.value = current.copy(running = true, result = null)
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val builder = Request.Builder().url(current.url.trim())
                    current.headers.lineSequence().forEach { line ->
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            builder.header(
                                line.substring(0, separator).trim(),
                                line.substring(separator + 1).trim(),
                            )
                        }
                    }
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = when (current.method.uppercase()) {
                        "GET", "DELETE" -> null
                        else -> current.body.toRequestBody(mediaType)
                    }
                    builder.method(current.method.uppercase(), requestBody)
                    client.newCall(builder.build()).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        HttpResult(
                            status = response.code,
                            statusText = response.message,
                            durationMs = System.currentTimeMillis() - started,
                            body = if (text.length > 200_000) text.take(200_000) + "\n[truncated]" else text,
                            headers = response.headers.names().map { it to response.header(it).orEmpty() },
                        )
                    }
                }.getOrElse { error ->
                    HttpResult(
                        durationMs = System.currentTimeMillis() - started,
                        error = error.message ?: error::class.simpleName ?: "Request failed",
                    )
                }
            }

            container.database.httpRequestDao().upsert(
                HttpRequestEntity(
                    id = UUID.randomUUID().toString(),
                    name = "${current.method} ${current.url.take(60)}",
                    method = current.method,
                    url = current.url,
                    headersJson = current.headers,
                    body = current.body,
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            _state.value = _state.value.copy(running = false, result = result)
        }
    }

    fun loadHistory(entry: HttpRequestEntity) {
        _state.value = _state.value.copy(
            method = entry.method,
            url = entry.url,
            headers = entry.headersJson,
            body = entry.body,
            result = null,
        )
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch { container.database.httpRequestDao().delete(id) }
    }
}
