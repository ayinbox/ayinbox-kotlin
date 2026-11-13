package com.ayinbox

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ClientTest {
    private lateinit var server: MockWebServer
    private val key = "aybox_test_key_not_real"

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun client(maxRetries: Int = 2) =
        AyInbox(apiKey = key, baseUrl = server.url("/v1/").toString(), maxRetries = maxRetries, environment = { null })

    private fun fixture(name: String): String =
        ClientTest::class.java.getResource("/fixtures/$name")?.readText() ?: error("missing fixture $name")

    private fun ok(name: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setHeader("X-Request-Id", "req-fixture-0001")
        .setBody(fixture(name))

    private fun err(name: String, status: Int, vararg headers: Pair<String, String>): MockResponse {
        val r = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(fixture(name))
        headers.forEach { (k, v) -> r.setHeader(k, v) }
        return r
    }

    private fun take(): RecordedRequest = server.takeRequest()

    @Test
    fun sendsAuthUserAgentAndAccept() = runTest {
        server.enqueue(ok("me.json"))
        val me = client().me.get()
        assertEquals("dana@example.com", me.email)
        assertEquals("api_key", me.authType)
        assertEquals(listOf("read_campaigns", "read_contacts", "write_contacts"), me.scopes)
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/v1/me", req.path)
        assertEquals("Bearer $key", req.getHeader("Authorization"))
        assertEquals("ayinbox-kotlin/${AyInbox.VERSION}", req.getHeader("User-Agent"))
        assertEquals("application/json", req.getHeader("Accept"))
        assertNull(req.getHeader("Idempotency-Key"))
    }

    @Test
    fun keyFallsBackToEnvironmentAndIsNeverPrinted() {
        val c = AyInbox(environment = { if (it == "AYINBOX_API_KEY") key else null })
        assertEquals(AyInbox.DEFAULT_BASE_URL, c.baseUrl)
        assertFalse(c.toString().contains(key))
        assertFailsWith<AyInboxException> { AyInbox(environment = { null }) }
        val custom = AyInbox(apiKey = key, environment = { if (it == "AYINBOX_BASE_URL") "https://example.test/v1/" else null })
        assertEquals("https://example.test/v1", custom.baseUrl)
    }

    @Test
    fun mapsEveryErrorFixtureToItsType() = runTest {
        val cases: List<Triple<String, Int, KClass<out ApiException>>> = listOf(
            Triple("errors/400_bad_request.json", 400, BadRequestException::class),
            Triple("errors/400_invalid_cursor.json", 400, BadRequestException::class),
            Triple("errors/401_unauthorized.json", 401, AuthenticationException::class),
            Triple("errors/401_api_key_holder_left.json", 401, AuthenticationException::class),
            Triple("errors/402_insufficient_credits.json", 402, PaymentRequiredException::class),
            Triple("errors/403_forbidden.json", 403, PermissionDeniedException::class),
            Triple("errors/403_api_key_mailbox_limited.json", 403, PermissionDeniedException::class),
            Triple("errors/404_not_found.json", 404, NotFoundException::class),
            Triple("errors/409_conflict.json", 409, ConflictException::class),
            Triple("errors/409_idempotency_outcome_unknown.json", 409, ConflictException::class),
            Triple("errors/409_idempotent_secret_withheld.json", 409, ConflictException::class),
            Triple("errors/422_unprocessable.json", 422, UnprocessableException::class),
            Triple("errors/429_api_key_rate_limited.json", 429, RateLimitException::class),
            Triple("errors/500_internal_error.json", 500, InternalServerException::class),
            Triple("errors/503_service_unavailable.json", 503, ServiceUnavailableException::class),
        )
        val c = client(maxRetries = 0)
        for ((file, status, type) in cases) {
            server.enqueue(err(file, status))
            val e = try {
                c.campaigns.get("11111111-1111-4111-8111-111111111111")
                fail("expected $file to throw")
            } catch (e: ApiException) {
                e
            }
            assertTrue(type.isInstance(e), "$file gave ${e::class.simpleName}")
            assertEquals(status, e.status)
            val expectedCode = AyInbox.json.parseToJsonElement(fixture(file)).jsonObject["code"]!!.jsonPrimitive.content
            assertEquals(expectedCode, e.code)
            assertEquals("req-fixture-0001", e.requestId)
            assertFalse(e.message!!.contains(key))
            take()
        }
        assertTrue(ValidationException::class.isInstance(UnprocessableException(ErrorDetails(422, "x", "", null, null, null, "", emptyMap()))))
    }

    @Test
    fun rateLimitWithoutCodeUsesRetryAfterMs() = runTest {
        server.enqueue(err("errors/429_category_rate_limited.json", 429))
        val e = assertFailsWith<RateLimitException> { client(maxRetries = 0).me.get() }
        assertEquals("rate_limit_exceeded", e.code)
        assertEquals(12L, e.retryAfterSeconds)
        assertNull(e.requestId)
    }

    @Test
    fun rateLimitPrefersRetryAfterHeader() = runTest {
        server.enqueue(err("errors/429_api_key_rate_limited.json", 429, "Retry-After" to "7"))
        val e = assertFailsWith<RateLimitException> { client(maxRetries = 0).me.get() }
        assertEquals(7L, e.retryAfterSeconds)
    }

    @Test
    fun nonJsonErrorKeepsTextAndDefaultCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val e = assertFailsWith<InternalServerException> { client(maxRetries = 0).me.get() }
        assertEquals("internal_error", e.code)
        assertEquals("<html>bad gateway</html>", e.body)
    }

    @Test
    fun retriesGetOn503ThenSucceeds() = runTest {
        server.enqueue(err("errors/503_service_unavailable.json", 503, "Retry-After" to "1"))
        server.enqueue(ok("me.json"))
        assertEquals("Dana Example", client().me.get().name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retriesKeylessPostOn429WithRetryAfter() = runTest {
        server.enqueue(err("errors/429_api_key_rate_limited.json", 429, "Retry-After" to "2"))
        server.enqueue(ok("campaign.json"))
        val c = client().campaigns.create(CampaignCreateParams(name = "Q4 outreach"))
        assertEquals("Q4 outreach", c.name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun doesNotRetryKeylessPostOn503() = runTest {
        server.enqueue(err("errors/503_service_unavailable.json", 503))
        server.enqueue(ok("campaign.json"))
        assertFailsWith<ServiceUnavailableException> {
            client().campaigns.create(CampaignCreateParams(name = "Q4 outreach"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesPostWithIdempotencyKeyReusingTheKey() = runTest {
        server.enqueue(err("errors/503_service_unavailable.json", 503, "Retry-After" to "1"))
        server.enqueue(ok("contacts_create.json"))
        val created = client().contacts.create(
            ContactInput(email = "lee@example.com", firstName = "Lee"),
            RequestOptions(idempotencyKey = "create-lee-1"),
        )
        assertEquals("lee@example.com", created.first().email)
        val first = take()
        val second = take()
        assertEquals("create-lee-1", first.getHeader("Idempotency-Key"))
        assertEquals("create-lee-1", second.getHeader("Idempotency-Key"))
        val body = AyInbox.json.parseToJsonElement(first.body.readUtf8())
        assertTrue(body is JsonArray)
        assertEquals("lee@example.com", body.jsonArray[0].jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals("Lee", body.jsonArray[0].jsonObject["first_name"]!!.jsonPrimitive.content)
        assertFalse(body.jsonArray[0].jsonObject.containsKey("company"))
    }

    @Test
    fun neverRetriesIdempotencyOutcomeUnknown() = runTest {
        server.enqueue(err("errors/409_idempotency_outcome_unknown.json", 409))
        val e = assertFailsWith<ConflictException> {
            client().campaigns.pause("11111111-1111-4111-8111-222222222222", RequestOptions(idempotencyKey = "k1"))
        }
        assertEquals("idempotency_outcome_unknown", e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun walksTwoPages() = runTest {
        server.enqueue(ok("campaigns_list.json"))
        server.enqueue(ok("campaigns_list_page2.json"))
        val all = client().campaigns.listAll(limit = 2).toList()
        assertEquals(3, all.size)
        val p1 = take()
        val p2 = take()
        assertNull(p1.requestUrl!!.queryParameter("cursor"))
        assertEquals("2", p1.requestUrl!!.queryParameter("limit"))
        assertEquals("c1_ZmFrZS1jdXJzb3ItcGFnZS0y", p2.requestUrl!!.queryParameter("cursor"))
        assertEquals("2", p2.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun campaignsListOnePage() = runTest {
        server.enqueue(ok("campaigns_list.json"))
        val page = client().campaigns.list(status = "active")
        assertEquals(2, page.data.size)
        assertTrue(page.hasMore)
        assertEquals("c1_ZmFrZS1jdXJzb3ItcGFnZS0y", page.nextCursor)
        assertEquals(3L, page.total)
        assertEquals("explicit", page.data[0].senderStrategy)
        assertEquals("active", take().requestUrl!!.queryParameter("status"))
    }

    @Test
    fun campaignsGetCreatePause() = runTest {
        val c = client()
        server.enqueue(ok("campaign.json"))
        assertEquals("11111111-1111-4111-8111-111111111111", c.campaigns.get("11111111-1111-4111-8111-111111111111").id)
        assertEquals("/v1/campaigns/11111111-1111-4111-8111-111111111111", take().path)

        server.enqueue(ok("campaign.json"))
        c.campaigns.create(
            CampaignCreateParams(
                name = "Q4 outreach",
                dailyLimit = 30,
                steps = listOf(StepInput(subject = "Hi", bodyPlain = "Hello")),
                extra = mapOf("utm_tracking" to JsonPrimitive(true)),
            ),
        )
        val created = take()
        assertEquals("POST", created.method)
        assertEquals("application/json; charset=utf-8", created.getHeader("Content-Type"))
        val body = AyInbox.json.parseToJsonElement(created.body.readUtf8()).jsonObject
        assertEquals("Q4 outreach", body["name"]!!.jsonPrimitive.content)
        assertEquals("30", body["daily_limit"]!!.jsonPrimitive.content)
        assertEquals("true", body["utm_tracking"]!!.jsonPrimitive.content)
        assertEquals("Hello", body["steps"]!!.jsonArray[0].jsonObject["body_plain"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("description"))

        server.enqueue(ok("campaign_stop.json"))
        assertEquals("stopped", c.campaigns.pause("11111111-1111-4111-8111-222222222222").status)
        val stop = take()
        assertEquals("POST", stop.method)
        assertEquals("/v1/campaigns/11111111-1111-4111-8111-222222222222/stop", stop.path)
    }

    @Test
    fun contactsListGetUpdateDelete() = runTest {
        val c = client()
        server.enqueue(ok("contacts_list.json"))
        val page = c.contacts.list(ContactSearch(query = "example", campaignIds = listOf("c1")), limit = 25)
        assertEquals(2, page.data.size)
        assertEquals("Head of Ops", page.data[0].customFields["title"])
        assertEquals("VIP", page.data[0].categories[0].title)
        assertFalse(page.hasMore)
        assertTrue(page.raw.containsKey("counts"))
        val search = take()
        assertEquals("POST", search.method)
        assertEquals("/v1/contacts/search?limit=25", search.path)
        val body = AyInbox.json.parseToJsonElement(search.body.readUtf8()).jsonObject
        assertEquals("example", body["query"]!!.jsonPrimitive.content)
        assertEquals("c1", body["campaign_ids"]!!.jsonArray[0].jsonPrimitive.content)

        server.enqueue(ok("contact.json"))
        assertTrue(c.contacts.get("22222222-2222-4222-8222-111111111111").email.isNotEmpty())
        assertEquals("/v1/contacts/22222222-2222-4222-8222-111111111111", take().path)

        val one = AyInbox.json.parseToJsonElement(fixture("contacts_create.json")).jsonArray[0].toString()
        server.enqueue(MockResponse().setBody(one))
        val updated = c.contacts.update("22222222-2222-4222-8222-111111111111", ContactUpdateParams(company = "New Co"))
        assertEquals("lee@example.com", updated.email)
        val patch = take()
        assertEquals("PATCH", patch.method)
        assertEquals("{\"company\":\"New Co\"}", patch.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(204))
        c.contacts.delete("22222222-2222-4222-8222-111111111111", RequestOptions(idempotencyKey = "del-1"))
        val del = take()
        assertEquals("DELETE", del.method)
        assertEquals("del-1", del.getHeader("Idempotency-Key"))
    }

    @Test
    fun contactSearchIsRetriedWithoutAKey() = runTest {
        server.enqueue(err("errors/503_service_unavailable.json", 503, "Retry-After" to "1"))
        server.enqueue(ok("contacts_list.json"))
        assertEquals(2, client().contacts.list().data.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun mailboxesList() = runTest {
        server.enqueue(ok("mailboxes_list.json"))
        val page = client().mailboxes.list(q = "sales")
        assertEquals(2, page.data.size)
        assertTrue(page.data[0].email.isNotEmpty())
        assertEquals("sales", take().requestUrl!!.queryParameter("q"))
    }

    @Test
    fun uniboxThreads() = runTest {
        val c = client()
        server.enqueue(ok("unibox_threads.json"))
        val page = c.unibox.listThreads(ThreadQuery(folder = "inbox", unseen = true), limit = 10)
        assertEquals("thread-example-001", page.data[0].threadId)
        assertEquals(listOf("Lee Example <lee@example.com>"), page.data[0].fromAddr)
        assertTrue(page.hasMore)
        val url = take().requestUrl!!
        assertEquals("inbox", url.queryParameter("folder"))
        assertEquals("true", url.queryParameter("unseen"))
        assertNull(url.queryParameter("subject"))

        server.enqueue(ok("unibox_thread.json"))
        val thread = c.unibox.getThread("thread-example-001")
        assertFalse(thread.hasMore)
        assertEquals("thread-example-001", take().requestUrl!!.queryParameter("thread_id"))
    }

    @Test
    fun genericRequestAndPaginate() = runTest {
        val c = client()
        server.enqueue(ok("unibox_count.json"))
        val count = c.request("GET", "/unibox/count", query = mapOf("email_id" to "abc"))
        assertTrue(count is JsonObject)
        assertEquals("/v1/unibox/count?email_id=abc", take().path)

        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertEquals(kotlinx.serialization.json.JsonNull, c.request("DELETE", "campaigns/x/steps/y"))
        assertEquals("/v1/campaigns/x/steps/y", take().path)

        server.enqueue(ok("campaigns_list.json"))
        server.enqueue(ok("campaigns_list_page2.json"))
        assertEquals(3, c.paginate("/campaigns").toList().size)
    }

    @Test
    fun extraHeadersAndUnknownFieldsAreFine() = runTest {
        server.enqueue(MockResponse().setBody("{\"user_id\":\"u\",\"brand_new_field\":{\"x\":1},\"auth_type\":null}"))
        val me = client().me.get(RequestOptions(headers = mapOf("X-Request-Id" to "my-req-1")))
        assertEquals("u", me.userId)
        assertEquals("", me.authType)
        assertEquals("my-req-1", take().getHeader("X-Request-Id"))
    }

    @Test
    fun connectionErrorIsTyped() = runTest {
        val c = AyInbox(apiKey = key, baseUrl = "http://127.0.0.1:1/v1", maxRetries = 0, environment = { null })
        assertFailsWith<ConnectionException> { c.me.get() }
    }
}
