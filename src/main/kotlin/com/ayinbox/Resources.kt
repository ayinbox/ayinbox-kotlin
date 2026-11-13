package com.ayinbox

import com.ayinbox.AyInbox.Companion.segment
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** `GET /me`. */
class MeResource internal constructor(private val client: AyInbox) {
    /** Validates the credential and says who it acts for. Needs no scope. */
    suspend fun get(options: RequestOptions = RequestOptions()): Identity =
        client.call("GET", "/me", Identity.serializer(), options = options)
}

/** `/campaigns`. */
class CampaignsResource internal constructor(private val client: AyInbox) {
    /** One page. Scope READ_CAMPAIGNS. [limit] 1 to 100 is the recommended range. */
    suspend fun list(
        q: String? = null,
        status: String? = null,
        folder: String? = null,
        limit: Int? = null,
        cursor: String? = null,
        options: RequestOptions = RequestOptions(),
    ): Page<Campaign> = client.page(
        "GET", "/campaigns", Campaign.serializer(),
        query = mapOf("q" to q, "status" to status, "folder" to folder, "limit" to limit, "cursor" to cursor),
        options = options,
    )

    /** Every campaign, page by page. */
    fun listAll(
        q: String? = null,
        status: String? = null,
        folder: String? = null,
        limit: Int? = null,
        options: RequestOptions = RequestOptions(),
    ): Flow<Campaign> = client.pages { cursor -> list(q, status, folder, limit, cursor, options) }

    /** Scope READ_CAMPAIGNS. */
    suspend fun get(id: String, options: RequestOptions = RequestOptions()): Campaign =
        client.call("GET", "/campaigns/${segment(id)}", Campaign.serializer(), options = options)

    /** Creates a draft. Never sends mail. Scope WRITE_CAMPAIGNS. */
    suspend fun create(params: CampaignCreateParams, options: RequestOptions = RequestOptions()): Campaign {
        val encoded = client.encode(CampaignCreateParams.serializer(), params) as JsonObject
        return create(JsonObject(encoded + params.extra), options)
    }

    /** Creates a draft from a raw JSON body. */
    suspend fun create(body: JsonObject, options: RequestOptions = RequestOptions()): Campaign =
        client.call("POST", "/campaigns", Campaign.serializer(), body = body, options = options)

    /** `POST /campaigns/{id}/stop`: pauses an active campaign. Sends nothing. */
    suspend fun pause(id: String, options: RequestOptions = RequestOptions()): CampaignStopResult =
        client.call("POST", "/campaigns/${segment(id)}/stop", CampaignStopResult.serializer(), options = options)
}

/** `/contacts`. */
class ContactsResource internal constructor(private val client: AyInbox) {
    /** `POST /contacts/search`, one page. Read-only, so it is retried like a GET. Scope READ_CONTACTS. */
    suspend fun list(
        search: ContactSearch = ContactSearch(),
        limit: Int? = null,
        cursor: String? = null,
        category: String? = null,
        options: RequestOptions = RequestOptions(),
    ): Page<Contact> = client.page(
        "POST", "/contacts/search", Contact.serializer(),
        query = mapOf("limit" to limit, "cursor" to cursor, "category" to category),
        body = client.encode(ContactSearch.serializer(), search),
        options = options,
    )

    /** Every matching contact. The same search body is sent on every page. */
    fun listAll(
        search: ContactSearch = ContactSearch(),
        limit: Int? = null,
        category: String? = null,
        options: RequestOptions = RequestOptions(),
    ): Flow<Contact> = client.pages { cursor -> list(search, limit, cursor, category, options) }

    /** Scope READ_CONTACTS. Fills the detail fields of [Contact]. */
    suspend fun get(id: String, options: RequestOptions = RequestOptions()): Contact =
        client.call("GET", "/contacts/${segment(id)}", Contact.serializer(), options = options)

    /**
     * Creates or updates contacts by address (up to 10,240). Scope WRITE_CONTACTS.
     * Contacts added to an active campaign are queued for sending.
     */
    suspend fun create(contacts: List<ContactInput>, options: RequestOptions = RequestOptions()): List<Contact> =
        client.call(
            "POST", "/contacts", ListSerializer(Contact.serializer()),
            body = client.encode(ListSerializer(ContactInput.serializer()), contacts),
            options = options,
        )

    suspend fun create(contact: ContactInput, options: RequestOptions = RequestOptions()): List<Contact> =
        create(listOf(contact), options)

    /** Scope WRITE_CONTACTS. */
    suspend fun update(id: String, params: ContactUpdateParams, options: RequestOptions = RequestOptions()): Contact =
        client.call(
            "PATCH", "/contacts/${segment(id)}", Contact.serializer(),
            body = client.encode(ContactUpdateParams.serializer(), params),
            options = options,
        )

    /** Scope WRITE_CONTACTS. */
    suspend fun delete(id: String, options: RequestOptions = RequestOptions()) {
        client.requestRaw("DELETE", "/contacts/${segment(id)}", options = options)
    }
}

/** Mailboxes (`/emails`). */
class MailboxesResource internal constructor(private val client: AyInbox) {
    /** One page. Scope READ_EMAILS. */
    suspend fun list(
        q: String? = null,
        tag: String? = null,
        limit: Int? = null,
        cursor: String? = null,
        options: RequestOptions = RequestOptions(),
    ): Page<Mailbox> = client.page(
        "GET", "/emails", Mailbox.serializer(),
        query = mapOf("q" to q, "tag" to tag, "limit" to limit, "cursor" to cursor),
        options = options,
    )

    fun listAll(
        q: String? = null,
        tag: String? = null,
        limit: Int? = null,
        options: RequestOptions = RequestOptions(),
    ): Flow<Mailbox> = client.pages { cursor -> list(q, tag, limit, cursor, options) }
}

/** `/unibox`. */
class UniboxResource internal constructor(private val client: AyInbox) {
    /** One row per conversation. Scope READ_UNIBOX. [limit] 1 to 100. */
    suspend fun listThreads(
        filters: ThreadQuery = ThreadQuery(),
        limit: Int? = null,
        cursor: String? = null,
        options: RequestOptions = RequestOptions(),
    ): Page<MessagePreview> = client.page(
        "GET", "/unibox", MessagePreview.serializer(),
        query = filters.toQuery() + mapOf("limit" to limit, "cursor" to cursor),
        options = options,
    )

    fun listAllThreads(
        filters: ThreadQuery = ThreadQuery(),
        limit: Int? = null,
        options: RequestOptions = RequestOptions(),
    ): Flow<MessagePreview> = client.pages { cursor -> listThreads(filters, limit, cursor, options) }

    /** Messages of one thread, as previews. Scope READ_UNIBOX. [limit] 1 to 500. */
    suspend fun getThread(
        threadId: String,
        emailId: String? = null,
        limit: Int? = null,
        cursor: String? = null,
        options: RequestOptions = RequestOptions(),
    ): Page<MessagePreview> = client.page(
        "GET", "/unibox/thread", MessagePreview.serializer(),
        query = mapOf("thread_id" to threadId, "email_id" to emailId, "limit" to limit, "cursor" to cursor),
        options = options,
    )

    fun getThreadAll(
        threadId: String,
        emailId: String? = null,
        limit: Int? = null,
        options: RequestOptions = RequestOptions(),
    ): Flow<MessagePreview> = client.pages { cursor -> getThread(threadId, emailId, limit, cursor, options) }
}

/** Webhook helpers. Verification is local and makes no HTTP call. */
class WebhooksResource internal constructor() {
    fun verifySignature(
        payload: ByteArray,
        header: String,
        secret: String,
        toleranceSeconds: Long = WebhookSignature.DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean = WebhookSignature.verify(payload, header, secret, toleranceSeconds, nowSeconds)

    fun constructEvent(
        payload: ByteArray,
        header: String,
        secret: String,
        toleranceSeconds: Long = WebhookSignature.DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): WebhookEvent = WebhookSignature.constructEvent(payload, header, secret, toleranceSeconds, nowSeconds)
}
