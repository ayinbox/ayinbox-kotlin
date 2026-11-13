package com.ayinbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Timestamps are RFC 3339 strings, kept as sent. Fields the spec does not pin down are JsonElement.

/** One page of a list. [raw] holds the whole response, including extras such as `counts`. */
data class Page<T>(
    val data: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val total: Long?,
    val raw: JsonObject,
)

@Serializable
data class Identity(
    val userId: String = "",
    val email: String = "",
    val name: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val organizationId: String? = null,
    val organizationName: String? = null,
    val authType: String = "",
    val scopes: List<String> = emptyList(),
)

@Serializable
data class CampaignSender(
    val emailAccountId: String = "",
    val weight: Int? = null,
    val enabled: Boolean? = null,
    val lastSentAt: String? = null,
)

@Serializable
data class Campaign(
    val id: String = "",
    val userId: String = "",
    val organizationId: String? = null,
    val name: String = "",
    val description: String = "",
    val status: String = "",
    val stopOnReply: Boolean = false,
    val stopOnMeeting: Boolean = false,
    val openTracking: Boolean = false,
    val linkTracking: Boolean = false,
    val textOnly: Boolean = false,
    val unsubscribeHeader: Boolean = false,
    val riskyEmails: Boolean = false,
    val dailyLimit: Int = 0,
    val unsubscribeMode: String = "",
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val timezone: String = "",
    val effectiveTimezone: String = "",
    val days: Int = 0,
    val startTime: String = "",
    val endTime: String = "",
    val scheduleWindows: JsonElement? = null,
    val emailTags: List<String> = emptyList(),
    val folders: List<String> = emptyList(),
    val contactOrderBy: String = "",
    val contactOrderDir: String = "",
    val contactOrderField: String? = null,
    val senderStrategy: String = "",
    val rotationMode: String = "",
    val senders: List<CampaignSender>? = null,
    val rampEnabled: Boolean = false,
    val rampStart: Int = 0,
    val rampIncrement: Int = 0,
    val rampCeiling: Int = 0,
    val rampLevel: Int = 0,
    val rampLevelDate: String? = null,
    val espMatchMode: String = "",
    val maxNewLeadsPerDay: Int = 0,
    val prioritizeNewLeads: Boolean = false,
    val continuous: Boolean = false,
    val reviewFirstEmails: Boolean = false,
    val entryDelayMinutes: Int = 0,
    val idleSince: String? = null,
    val guardrailEnabled: Boolean = false,
    val guardrailBounceRateMax: Double = 0.0,
    val guardrailComplaintRateMax: Double = 0.0,
    val guardrailReplyRateMin: Double = 0.0,
    val guardrailMinSample: Int = 0,
    val guardrailWindowDays: Int = 0,
    val guardrailTrippedAt: String? = null,
    val guardrailReason: String? = null,
    val trackingDomain: String = "",
    val trackingDomainVerified: Boolean = false,
    val trackingDomainVerifiedAt: String? = null,
    val utmTracking: Boolean = false,
    val utmSource: String = "",
    val utmMedium: String = "",
    val utmCampaign: String = "",
    val lastStatusChangeAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class StepInput(
    val name: String? = null,
    val subject: String? = null,
    val bodyPlain: String? = null,
    val bodyHtml: String? = null,
    val bodySync: Boolean? = null,
    val bodyCode: Boolean? = null,
    val waitAfter: Int? = null,
    val threadReply: Boolean? = null,
)

@Serializable
data class SenderInput(
    val emailAccountId: String,
    val weight: Int? = null,
    val enabled: Boolean? = null,
)

/** Body of `POST /campaigns`. Creating never sends mail. Put any field not listed here in [extra]. */
@Serializable
data class CampaignCreateParams(
    val name: String,
    val description: String? = null,
    val stopOnReply: Boolean? = null,
    val stopOnMeeting: Boolean? = null,
    val openTracking: Boolean? = null,
    val linkTracking: Boolean? = null,
    val textOnly: Boolean? = null,
    val unsubscribeHeader: Boolean? = null,
    val riskyEmails: Boolean? = null,
    val dailyLimit: Int? = null,
    val unsubscribeMode: String? = null,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val timezone: String? = null,
    val days: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val emailTagIds: List<String>? = null,
    val folderIds: List<String>? = null,
    val senderStrategy: String? = null,
    val rotationMode: String? = null,
    val senders: List<SenderInput>? = null,
    val maxNewLeadsPerDay: Int? = null,
    val entryDelayMinutes: Int? = null,
    val reviewFirstEmails: Boolean? = null,
    val steps: List<StepInput>? = null,
    @Transient val extra: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class CampaignStopResult(val status: String = "")

@Serializable
data class ContactRef(val id: String = "", val name: String = "")

@Serializable
data class Label(val id: String = "", val title: String = "", val color: String = "")

@Serializable
data class ContactCampaignProgress(
    val status: String = "",
    val sent: Int = 0,
    val opened: Int = 0,
    val machineOpened: Int = 0,
    val clicked: Int = 0,
    val replied: Int = 0,
    val bounced: Int = 0,
    val lastActivityAt: String? = null,
    val currentStep: String? = null,
    val failureReason: String? = null,
    val sender: String? = null,
    val hold: JsonElement? = null,
    val cc: JsonElement? = null,
)

/** A contact. `contacts.get` also fills the detail fields (engagement through consentRequired). */
@Serializable
data class Contact(
    val id: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val company: String = "",
    val phone: String = "",
    val customFields: Map<String, String> = emptyMap(),
    val subscribed: Boolean = false,
    val campaigns: List<ContactRef> = emptyList(),
    val categories: List<Label> = emptyList(),
    val verificationStatus: String = "",
    val verificationReason: String = "",
    val isCatchAll: Boolean = false,
    val verificationCheckedAt: String? = null,
    val verificationSource: String = "",
    val verificationProvider: String = "",
    val verificationSubStatus: String = "",
    val verificationConfidence: Int = 0,
    val verificationRequestedAt: String? = null,
    val mailHost: String = "",
    val espProvider: String = "",
    val espResolvedAt: String? = null,
    val consentGivenAt: String? = null,
    val consentSource: String? = null,
    val leadScore: JsonElement? = null,
    val campaignLead: ContactCampaignProgress? = null,
    val outreach: JsonElement? = null,
    val engagement: JsonElement? = null,
    val suppression: JsonElement? = null,
    val verification: JsonElement? = null,
    val source: String? = null,
    val sourceDetail: String? = null,
    val firstSeenAt: String? = null,
    val consentCountry: String? = null,
    val consentRequired: Boolean? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** One contact for `POST /contacts`. An existing address is updated, not duplicated. */
@Serializable
data class ContactInput(
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val phone: String? = null,
    val campaigns: List<String>? = null,
    val categories: List<String>? = null,
    val segments: List<String>? = null,
    val customFields: Map<String, String>? = null,
    val subscribed: Boolean? = null,
    val verificationStatus: String? = null,
    val verificationProvider: String? = null,
)

/** Body of `PATCH /contacts/{id}`. Fields left null are not sent. */
@Serializable
data class ContactUpdateParams(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val company: String? = null,
    val phone: String? = null,
    val customFields: Map<String, String>? = null,
    val subscribed: Boolean? = null,
    val campaigns: List<String>? = null,
    val categories: List<String>? = null,
    val addCategories: List<String>? = null,
    val removeCategories: List<String>? = null,
)

@Serializable
data class CustomFieldFilter(val name: String, val value: String, val type: String = "equal")

/** Body of `POST /contacts/search`. An empty search matches every contact. */
@Serializable
data class ContactSearch(
    val query: String? = null,
    val customFieldFilters: List<CustomFieldFilter>? = null,
    val campaignIds: List<String>? = null,
    val leadStatus: String? = null,
    val engagement: String? = null,
    val categoryIds: List<String>? = null,
    val segmentIds: List<String>? = null,
    val minCampaigns: Int? = null,
    val maxCampaigns: Int? = null,
    val subscribed: Boolean? = null,
    val verificationStatus: String? = null,
    val mailHosts: List<String>? = null,
    val leadGrades: List<String>? = null,
    val createdAfter: String? = null,
    val createdBefore: String? = null,
    val updatedAfter: String? = null,
    val updatedBefore: String? = null,
    val sortBy: String? = null,
    val reverse: Boolean? = null,
)

@Serializable
data class Mailbox(
    val id: String = "",
    val userId: String = "",
    val organizationId: String? = null,
    val workerId: String? = null,
    val email: String = "",
    val name: String = "",
    val signaturePlain: String = "",
    val signatureHtml: String = "",
    val sendAsEmail: String = "",
    val provider: String = "",
    val status: String = "",
    val mailHost: String = "",
    val authMethod: String = "",
    val avatarUrl: String = "",
    val lastSyncedAt: String? = null,
    val campaignLimit: Int = 0,
    val minWaitTime: Int = 0,
    val replyTo: String = "",
    val trackingDomain: String = "",
    val trackingDomainVerified: Boolean = false,
    val authState: String = "",
    val authSpf: Boolean = false,
    val authDkim: Boolean = false,
    val authDmarc: Boolean = false,
    val authCheckedAt: String? = null,
    val warmup: String? = null,
    val warmupPausedAt: String? = null,
    val warmupBase: Int = 0,
    val warmupMax: Int = 0,
    val warmupIncrease: Int = 0,
    val warmupReplyRate: Int = 0,
    val warmupPoolType: String = "",
    val timezone: String = "",
    val saveToSent: Boolean = false,
    val relayFolderMoves: Boolean = false,
    val tags: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class MessagePreview(
    val id: String = "",
    val emailId: String = "",
    val threadId: String = "",
    val fromAddr: List<String> = emptyList(),
    val toAddr: List<String> = emptyList(),
    val subject: String = "",
    val snippet: String = "",
    val internalDate: String = "",
    val seen: Boolean = false,
    val messageCount: Int = 0,
    val hasUnread: Boolean = false,
    val labels: List<Label> = emptyList(),
    val answersMailboxId: String? = null,
    val campaignId: String? = null,
)

/** Filters for `GET /unibox`. Null fields are not sent. */
data class ThreadQuery(
    val from: String? = null,
    val address: String? = null,
    val direction: String? = null,
    val folder: String? = null,
    val includeArchived: Boolean? = null,
    val subject: String? = null,
    val unseen: Boolean? = null,
    val snoozed: String? = null,
    val awaitingReply: Boolean? = null,
    val agentDrafts: Boolean? = null,
    val since: String? = null,
    val until: String? = null,
    val campaignId: String? = null,
    val contactId: String? = null,
    val emailId: String? = null,
    val emailIds: List<String>? = null,
    val uncategorized: Boolean? = null,
    val automated: Boolean? = null,
    val categoryIds: List<String>? = null,
) {
    internal fun toQuery(): Map<String, Any?> = mapOf(
        "from" to from,
        "address" to address,
        "direction" to direction,
        "folder" to folder,
        "include_archived" to includeArchived,
        "subject" to subject,
        "unseen" to unseen,
        "snoozed" to snoozed,
        "awaiting_reply" to awaitingReply,
        "agent_drafts" to agentDrafts,
        "since" to since,
        "until" to until,
        "campaign_id" to campaignId,
        "contact_id" to contactId,
        "email_id" to emailId,
        "email_ids" to emailIds,
        "uncategorized" to uncategorized,
        "automated" to automated,
        "category_ids" to categoryIds,
    )
}

/** A webhook delivery body after its signature verified. */
@Serializable
data class WebhookEvent(
    val id: String = "",
    val eventType: String = "",
    val organizationId: String = "",
    val createdAt: String = "",
    val data: JsonElement? = null,
)
