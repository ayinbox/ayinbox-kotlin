package com.ayinbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil

/** Base of every exception this SDK throws. */
open class AyInboxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** What the API said about a failed request. */
class ErrorDetails(
    val status: Int,
    val code: String,
    val message: String,
    val reason: String?,
    val requestId: String?,
    val retryAfterSeconds: Long?,
    val body: String,
    val headers: Map<String, List<String>>,
)

/** An error response from the API. Branch on [status] and [code], never on [apiMessage]. */
open class ApiException(val details: ErrorDetails) : AyInboxException(
    "${details.status} ${details.code}: ${details.message}" +
        (details.requestId?.let { " (request_id $it)" } ?: ""),
) {
    val status: Int get() = details.status
    val code: String get() = details.code
    val apiMessage: String get() = details.message
    val reason: String? get() = details.reason
    val requestId: String? get() = details.requestId
    val body: String get() = details.body
    val headers: Map<String, List<String>> get() = details.headers
}

/** 400 or 422. */
open class ValidationException(details: ErrorDetails) : ApiException(details)
class BadRequestException(details: ErrorDetails) : ValidationException(details)
class UnprocessableException(details: ErrorDetails) : ValidationException(details)
class AuthenticationException(details: ErrorDetails) : ApiException(details)
class PaymentRequiredException(details: ErrorDetails) : ApiException(details)
class PermissionDeniedException(details: ErrorDetails) : ApiException(details)
class NotFoundException(details: ErrorDetails) : ApiException(details)
class ConflictException(details: ErrorDetails) : ApiException(details)
class RateLimitException(details: ErrorDetails) : ApiException(details) {
    val retryAfterSeconds: Long? get() = details.retryAfterSeconds
}

/** 5xx. */
open class ServerException(details: ErrorDetails) : ApiException(details)
class InternalServerException(details: ErrorDetails) : ServerException(details)
class ServiceUnavailableException(details: ErrorDetails) : ServerException(details) {
    val retryAfterSeconds: Long? get() = details.retryAfterSeconds
}

/** The request may not have reached the API. */
open class NetworkException(message: String, cause: Throwable?) : AyInboxException(message, cause)
class ConnectionException(message: String, cause: Throwable?) : NetworkException(message, cause)
class ApiTimeoutException(message: String, cause: Throwable?) : NetworkException(message, cause)

/** A webhook signature did not verify. */
class SignatureVerificationException(message: String) : AyInboxException(message)

private val CODE_PATTERN = Regex("^[a-z0-9_]+$")

internal fun defaultCode(status: Int): String = when (status) {
    400 -> "bad_request"
    401 -> "unauthorized"
    402 -> "insufficient_credits"
    403 -> "forbidden"
    404 -> "not_found"
    409 -> "conflict"
    422 -> "unprocessable"
    429 -> "rate_limit_exceeded"
    500 -> "internal_error"
    501 -> "not_implemented"
    503 -> "service_unavailable"
    else -> if (status >= 500) "internal_error" else "http_$status"
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun headerValue(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

internal fun buildApiException(status: Int, text: String, headers: Map<String, List<String>>): ApiException {
    val obj = try {
        AyInbox.json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }
    val code = obj?.str("code")
        ?: obj?.str("error")?.takeIf { CODE_PATTERN.matches(it) }
        ?: defaultCode(status)
    val message = obj?.str("message") ?: text.ifBlank { "HTTP $status" }
    val requestId = obj?.str("request_id") ?: headerValue(headers, "X-Request-Id")
    val retryAfter = headerValue(headers, "Retry-After")?.trim()?.toLongOrNull()
        ?: (obj?.get("retry_after") as? JsonPrimitive)?.longOrNull
        ?: (obj?.get("retry_after_ms") as? JsonPrimitive)?.doubleOrNull?.let { ceil(it / 1000.0).toLong() }
    val d = ErrorDetails(status, code, message, obj?.str("reason"), requestId, retryAfter, text, headers)
    return when (status) {
        400 -> BadRequestException(d)
        401 -> AuthenticationException(d)
        402 -> PaymentRequiredException(d)
        403 -> PermissionDeniedException(d)
        404 -> NotFoundException(d)
        409 -> ConflictException(d)
        422 -> UnprocessableException(d)
        429 -> RateLimitException(d)
        503 -> ServiceUnavailableException(d)
        else -> if (status >= 500) InternalServerException(d) else ApiException(d)
    }
}
