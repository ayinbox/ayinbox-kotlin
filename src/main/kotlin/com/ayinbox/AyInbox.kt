package com.ayinbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Per-call options. */
data class RequestOptions(
    val idempotencyKey: String? = null,
    val timeout: Duration? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** A raw 2xx response from [AyInbox.requestRaw]. */
class RawResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun text(): String = body.toString(Charsets.UTF_8)
}

/**
 * Client for the AY Inbox REST API.
 *
 * The key falls back to `AYINBOX_API_KEY` and the base URL to `AYINBOX_BASE_URL`.
 */
class AyInbox(
    apiKey: String? = null,
    baseUrl: String? = null,
    val timeout: Duration = 30.seconds,
    val maxRetries: Int = 2,
    httpClient: OkHttpClient? = null,
    environment: (String) -> String? = System::getenv,
) {
    private val apiKey: String = (apiKey ?: environment("AYINBOX_API_KEY"))?.takeIf { it.isNotBlank() }
        ?: throw AyInboxException("No API key: pass apiKey or set AYINBOX_API_KEY")

    val baseUrl: String = (baseUrl ?: environment("AYINBOX_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL)
        .trimEnd('/')

    private val http: OkHttpClient = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    init {
        require(maxRetries >= 0) { "maxRetries must be zero or more" }
    }

    val me = MeResource(this)
    val campaigns = CampaignsResource(this)
    val contacts = ContactsResource(this)
    val mailboxes = MailboxesResource(this)
    val unibox = UniboxResource(this)
    val webhooks = WebhooksResource()

    override fun toString(): String = "AyInbox(baseUrl=$baseUrl, apiKey=***)"

    /** Calls any route. [path] is relative to the base URL. Returns the decoded JSON, or [JsonNull] for an empty body. */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        options: RequestOptions = RequestOptions(),
    ): JsonElement {
        val text = requestRaw(method, path, query, body, options).text()
        if (text.isBlank()) return JsonNull
        return try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            JsonPrimitive(text)
        }
    }

    /** Like [request] but returns the raw bytes and headers. */
    suspend fun requestRaw(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        options: RequestOptions = RequestOptions(),
    ): RawResponse = send(method, path, query, body, options, readOnly = false)

    /**
     * Walks every page of a list route that answers `{data, pagination}`, following `next_cursor`.
     * The same query and body are sent on every page.
     */
    fun paginate(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        method: String = "GET",
        body: JsonElement? = null,
        options: RequestOptions = RequestOptions(),
    ): Flow<JsonObject> = pages { cursor ->
        val raw = send(method, path, query + ("cursor" to cursor), body, options, readOnly = true)
        parsePage(raw, JsonObject.serializer())
    }

    internal suspend fun <T> call(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        options: RequestOptions = RequestOptions(),
        readOnly: Boolean = false,
    ): T = json.decodeFromString(serializer, send(method, path, query, body, options, readOnly).text())

    internal suspend fun <T> page(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        options: RequestOptions = RequestOptions(),
    ): Page<T> = parsePage(send(method, path, query, body, options, readOnly = true), serializer)

    private fun <T> parsePage(raw: RawResponse, serializer: KSerializer<T>): Page<T> {
        val obj = json.parseToJsonElement(raw.text()) as? JsonObject
            ?: throw AyInboxException("Expected a JSON object list response")
        val data = obj["data"]?.takeIf { it !is JsonNull }
            ?.let { json.decodeFromJsonElement(ListSerializer(serializer), it) } ?: emptyList()
        val pagination = obj["pagination"] as? JsonObject
        val next = (pagination?.get("next_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val hasMore = (pagination?.get("has_more") as? JsonPrimitive)?.booleanOrNull ?: false
        val total = (pagination?.get("total") as? JsonPrimitive)?.longOrNull
        return Page(data, next, hasMore, total, obj)
    }

    internal fun <T> pages(fetch: suspend (cursor: String?) -> Page<T>): Flow<T> = flow {
        var cursor: String? = null
        while (true) {
            val page = fetch(cursor)
            for (item in page.data) emit(item)
            val next = page.nextCursor
            if (!page.hasMore || next.isNullOrEmpty()) break
            cursor = next
        }
    }

    internal fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = json.encodeToJsonElement(serializer, value)

    internal suspend fun send(
        method: String,
        path: String,
        query: Map<String, Any?>,
        body: JsonElement?,
        options: RequestOptions,
        readOnly: Boolean,
    ): RawResponse {
        val m = method.uppercase()
        val request = buildRequest(m, path, query, body, options)
        val client = options.timeout?.let {
            http.newBuilder().callTimeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
        } ?: http
        val replaySafe = readOnly || options.idempotencyKey != null || m in SAFE_METHODS

        var attempt = 0
        while (true) {
            val result: RawResponse = try {
                execute(client, request)
            } catch (e: IOException) {
                val err: NetworkException = if (e is InterruptedIOException) {
                    ApiTimeoutException("Request to the AY Inbox API timed out", e)
                } else {
                    ConnectionException("Could not reach the AY Inbox API: ${e.javaClass.simpleName}", e)
                }
                if (replaySafe && attempt < maxRetries) {
                    delay(backoffMillis(attempt, null))
                    attempt++
                    continue
                }
                throw err
            }
            if (result.status in 200..299) return result
            val error = buildApiException(result.status, result.text(), result.headers)
            val retryable = when (result.status) {
                429 -> true
                502, 503, 504 -> replaySafe
                else -> false
            }
            if (retryable && attempt < maxRetries) {
                delay(backoffMillis(attempt, error.details.retryAfterSeconds))
                attempt++
                continue
            }
            throw error
        }
    }

    private fun buildRequest(
        method: String,
        path: String,
        query: Map<String, Any?>,
        body: JsonElement?,
        options: RequestOptions,
    ): Request {
        val url = (baseUrl + "/" + path.trimStart('/')).toHttpUrl().newBuilder()
        for ((k, v) in query) {
            when (v) {
                null -> Unit
                is Iterable<*> -> url.addQueryParameter(k, v.joinToString(","))
                else -> url.addQueryParameter(k, v.toString())
            }
        }
        val requestBody = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_MEDIA)
            method in BODY_METHODS -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(url.build()).method(method, requestBody)
        for ((k, v) in options.headers) builder.header(k, v)
        builder.header("Authorization", "Bearer $apiKey")
        builder.header("User-Agent", USER_AGENT)
        builder.header("Accept", "application/json")
        options.idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return builder.build()
    }

    private suspend fun execute(client: OkHttpClient, request: Request): RawResponse {
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                RawResponse(it.code, it.headers.toMultimap(), it.body?.bytes() ?: ByteArray(0))
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private fun backoffMillis(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            return minOf(retryAfterSeconds, MAX_RETRY_AFTER_SECONDS) * 1000
        }
        val ceiling = minOf(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl minOf(attempt, 10))
        return Random.nextLong(0, ceiling + 1)
    }

    companion object {
        const val VERSION = "0.1.0"
        const val DEFAULT_BASE_URL = "https://api.ayinbox.com/v1"
        internal const val USER_AGENT = "ayinbox-kotlin/$VERSION"
        private const val MAX_RETRY_AFTER_SECONDS = 120L
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 8000L
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "PUT", "DELETE")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val JSON_MEDIA = "application/json".toMediaType()

        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            isLenient = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

        internal fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
