# ayinbox-kotlin

The official Kotlin client for the AY Inbox REST API, for the JVM (Java 11 or newer). Built on coroutines, OkHttp and kotlinx.serialization.

AY Inbox is a hosted cold email platform: it sends cold email at a paced daily volume, brings every reply into one inbox (Unibox), drafts answers from a per-campaign brain, and can offer a meeting time from Cal.com or Calendly. Access is invite only for now: ask at team@ayinbox.com.

AY Inbox is a hosted service. This repository holds the Kotlin client library for its public API. The service itself is not open source.

## Install

Not on Maven Central yet. The coordinates will be `com.ayinbox:ayinbox-kotlin`.

Until then, build it from GitHub as a Gradle composite build. Clone the repository next to your project:

```sh
git clone https://github.com/ayinbox/ayinbox-kotlin.git
```

In your project's `settings.gradle.kts`:

```kotlin
includeBuild("../ayinbox-kotlin")
```

In `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.ayinbox:ayinbox-kotlin:0.1.0")
}
```

Gradle substitutes the included build for the coordinates.

## Quick start

```kotlin
import com.ayinbox.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val client = AyInbox() // reads AYINBOX_API_KEY

    val me = client.me.get()
    println("Signed in as ${me.email} with scopes ${me.scopes}")

    client.campaigns.listAll().collect { println("${it.status}  ${it.name}") }

    try {
        val created = client.contacts.create(
            ContactInput(email = "lee@example.com", firstName = "Lee"),
            RequestOptions(idempotencyKey = "import-2026-11-13-lee"),
        )
        println("Contact ${created.first().id}")
    } catch (e: PermissionDeniedException) {
        println("Missing scope: ${e.code} (request ${e.requestId})")
    }
}
```

Create an API key in the dashboard at https://app.ayinbox.com. Keys start with `aybox_`; OAuth access tokens (`ayat_`) work the same way.

## Client options

```kotlin
val client = AyInbox(
    apiKey = "aybox_...",                 // default: AYINBOX_API_KEY
    baseUrl = "https://api.ayinbox.com/v1", // default: AYINBOX_BASE_URL, then this
    timeout = 30.seconds,                 // whole-call timeout
    maxRetries = 2,                       // 0 disables retries
    httpClient = OkHttpClient(),          // optional, for proxies, interceptors or a shared pool
)
```

Every method takes `RequestOptions(idempotencyKey, timeout, headers)` for one call. The key is never logged, and `toString()` hides it.

## Resources

| Resource | Methods | Route |
|---|---|---|
| `me` | `get` | `GET /me` |
| `campaigns` | `list`, `listAll` | `GET /campaigns` |
| | `get` | `GET /campaigns/{id}` |
| | `create` | `POST /campaigns` |
| | `pause` | `POST /campaigns/{id}/stop` |
| `contacts` | `list`, `listAll` | `POST /contacts/search` |
| | `get` | `GET /contacts/{id}` |
| | `create` | `POST /contacts` |
| | `update` | `PATCH /contacts/{id}` |
| | `delete` | `DELETE /contacts/{id}` |
| `mailboxes` | `list`, `listAll` | `GET /emails` |
| `unibox` | `listThreads`, `listAllThreads` | `GET /unibox` |
| | `getThread`, `getThreadAll` | `GET /unibox/thread` |
| `webhooks` | `verifySignature`, `constructEvent` | local, no HTTP |

Every other route is reachable through `request`:

```kotlin
val count = client.request("GET", "/unibox/count")
val steps = client.request("GET", "/campaigns/$id/steps")
val result = client.request(
    "POST", "/suppressions",
    body = buildJsonObject { putJsonArray("entries") { addJsonObject { put("value", "example.org") } } },
    options = RequestOptions(idempotencyKey = "suppress-example-org"),
)
```

`request` returns a `JsonElement` (`JsonNull` for an empty body). `requestRaw` returns status, headers and bytes.

Response models ignore fields they do not know, so new API fields never break deserialization. Timestamps are RFC 3339 strings. Fields whose shape the API does not fix (lead scores, engagement, schedule windows, webhook `data`) are `JsonElement`. `Page.raw` keeps the whole list response, including extras such as `counts` on contact search.

## Errors

Every API error is an `ApiException` with `status`, `code`, `apiMessage`, `reason`, `requestId`, `body` and `headers`. Branch on `status` and `code`, never on the message.

| Exception | Status |
|---|---|
| `BadRequestException` (a `ValidationException`) | 400 |
| `AuthenticationException` | 401 |
| `PaymentRequiredException` | 402 |
| `PermissionDeniedException` | 403 |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `UnprocessableException` (a `ValidationException`) | 422 |
| `RateLimitException` (has `retryAfterSeconds`) | 429 |
| `InternalServerException` (a `ServerException`) | 500 and other 5xx |
| `ServiceUnavailableException` (a `ServerException`) | 503 |
| `ConnectionException`, `ApiTimeoutException` (both `NetworkException`) | no response |

All of them extend `AyInboxException`. A 429 from a per-user limiter carries no `code` in its body; the client still reports `rate_limit_exceeded` and reads the wait from `Retry-After`, `retry_after` or `retry_after_ms`.

## Retries and timeouts

The client retries 429, 502, 503, 504 and network failures up to `maxRetries` times (default 2). It waits for `Retry-After` when the API sends it (capped at 120 seconds), otherwise exponential backoff with full jitter (0.5 s base, 8 s cap).

A `POST` or `PATCH` without an idempotency key is retried only on 429, which the API never runs. Pass an idempotency key to let it retry other failures. `contacts.list` is a read sent as a `POST` and is retried like a `GET`. A 500 and `409 idempotency_outcome_unknown` are never retried.

The default timeout is 30 seconds for the whole call. Override it per client or per call with `RequestOptions(timeout = 60.seconds)`. Cancelling the coroutine cancels the HTTP call.

## Pagination

List methods return one `Page<T>` with `data`, `nextCursor`, `hasMore` and `total` (not on every list). The `listAll` variants return a `Flow<T>` that follows `next_cursor` until `has_more` is false:

```kotlin
client.contacts.listAll(ContactSearch(campaignIds = listOf(campaignId))).collect { println(it.email) }
```

For any other paginated route, `client.paginate("/suppressions")` yields each row as a `JsonObject`. Recommended page sizes are 1 to 100.

## Idempotency

Every mutating call accepts `RequestOptions(idempotencyKey = "...")`, sent as `Idempotency-Key` (1 to 255 visible ASCII characters). The API stores the result for 24 hours: the same key and request replays the original answer, and the same key with a different request answers `409 conflict`. If the first attempt ended without an outcome, the key answers `409 idempotency_outcome_unknown` for 24 hours: find out whether the change happened before trying again with a new key. The client does not generate keys for you; every retry of a call reuses the key you gave it.

## Webhooks

```kotlin
val ok = WebhookSignature.verify(
    payload = rawBodyBytes,                       // the raw request body, before JSON parsing
    header = request.getHeader("X-Ayinbox-Signature"),
    secret = "whsec_...",                          // the endpoint secret, prefix included
)
// or verify and parse in one step, throwing SignatureVerificationException:
val event = WebhookSignature.constructEvent(rawBodyBytes, header, secret)
println("${event.eventType} ${event.id}")
```

The signature is HMAC-SHA256 over `"<t>.<raw body>"`, compared in constant time. A timestamp more than 300 seconds from now fails by default. Use the `X-Ayinbox-Event-Id` header (equal to `event.id`) to drop retried deliveries.

## Calls that send email

These calls put real email on the wire:

- `campaigns.start` (`POST /campaigns/{id}/start`, through `request`): needs `START_CAMPAIGNS` or `SEND_CAMPAIGNS`.
- `campaigns.update` with `status: "active"` (and `completed` or `paused_undeliverable`), through `request`: needs `START_CAMPAIGNS` or `SEND_CAMPAIGNS` in addition to `WRITE_CAMPAIGNS`.
- `campaigns.leads.resume` on a finished campaign (`POST /campaigns/{id}/leads/{contactId}/resume`, through `request`): needs `START_CAMPAIGNS` or `SEND_CAMPAIGNS`.
- `unibox.reply` and `unibox.approveAgentDraft` (`POST /unibox/reply`, `POST /unibox/agent-drafts/{id}/approve`, through `request`): need `WRITE_UNIBOX`. A key with `WRITE_UNIBOX` can send replies from any mailbox it can reach.
- Also through `request`: `POST /unibox/compose`, `POST /emails/{id}/send`, `POST /campaigns/{id}/test-email`, the first-email review queue (`/campaigns/{id}/reviews/...`) and placement tests need `SEND_CAMPAIGNS` (compose needs `WRITE_UNIBOX`).

Adding contacts can send mail without calling start. Contacts added to an `active` campaign (through `contacts.create` with `campaigns`) are queued; a `completed` campaign starts again on its own when leads are added; a contact that comes to match a segment linked to a sending campaign is enrolled.

`START_CAMPAIGNS` starts, resumes and pauses campaigns and nothing else. `SEND_CAMPAIGNS` covers everything `START_CAMPAIGNS` covers plus the other sending actions. `WRITE_CAMPAIGNS` alone edits drafts and pauses, and cannot start anything. `campaigns.create` never sends and `campaigns.pause` sends nothing.

A key for a model that reads the inbox should use the "Agent: read and draft" preset (84574239): every read scope plus `AI_DRAFT`, with nothing that writes or sends.

Some calls spend workspace credits (AI drafts, research, enrichment, paid tools). They answer `402 insufficient_credits` when the balance is out.

Retrying a send without an idempotency key can send twice. Pass an idempotency key on every mutating call you might retry.

## Examples

`examples/` holds two programs: `gradle listCampaigns` (needs `AYINBOX_API_KEY`) and `gradle verifyWebhook` (runs offline against the test vector).

## Development

Tests run against a local MockWebServer fed from `src/test/resources/fixtures`, with no network and no real key:

```sh
gradle check
```

## Links

- API reference: https://docs.ayinbox.com/api/
- Dashboard: https://app.ayinbox.com
- Contact: team@ayinbox.com

## License

MIT. See `LICENSE`.
