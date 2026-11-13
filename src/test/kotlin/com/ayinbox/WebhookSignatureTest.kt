package com.ayinbox

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignatureTest {
    private val vector = AyInbox.json.parseToJsonElement(
        WebhookSignatureTest::class.java.getResource("/fixtures/webhook_signature.json")!!.readText(),
    ).jsonObject
    private val secret = vector["secret"]!!.jsonPrimitive.content
    private val body = vector["body"]!!.jsonPrimitive.content
    private val header = vector["header"]!!.jsonPrimitive.content
    private val ts = vector["timestamp"]!!.jsonPrimitive.long

    @Test
    fun validVectorPasses() {
        assertTrue(WebhookSignature.verify(body, header, secret, nowSeconds = ts))
        assertTrue(WebhookSignature.verify(body.toByteArray(), header, secret, nowSeconds = ts + 300))
    }

    @Test
    fun tamperedBodyFails() {
        assertFalse(WebhookSignature.verify(body.replace("reply_received", "reply_receivee"), header, secret, nowSeconds = ts))
        assertFalse(WebhookSignature.verify("$body\n", header, secret, nowSeconds = ts))
    }

    @Test
    fun wrongSecretFails() {
        assertFalse(WebhookSignature.verify(body, header, "whsec_other", nowSeconds = ts))
        assertFalse(WebhookSignature.verify(body, header, secret.removePrefix("whsec_"), nowSeconds = ts))
    }

    @Test
    fun outsideToleranceFails() {
        assertFalse(WebhookSignature.verify(body, header, secret, nowSeconds = ts + 301))
        assertFalse(WebhookSignature.verify(body, header, secret, nowSeconds = ts - 301))
    }

    @Test
    fun malformedHeadersFail() {
        val v1 = vector["expected_v1"]!!.jsonPrimitive.content
        assertFalse(WebhookSignature.verify(body, "v1=$v1", secret, nowSeconds = ts))
        assertFalse(WebhookSignature.verify(body, "t=$ts", secret, nowSeconds = ts))
        assertFalse(WebhookSignature.verify(body, "", secret, nowSeconds = ts))
        assertTrue(WebhookSignature.verify(body, "t=$ts,v1=deadbeef,v0=x,v1=$v1", secret, nowSeconds = ts))
    }

    @Test
    fun constructEventParsesOrThrows() {
        val event = WebhookSignature.constructEvent(body.toByteArray(), header, secret, nowSeconds = ts)
        assertEquals("campaign.reply_received", event.eventType)
        assertEquals("00000000-0000-4000-8000-000000000001", event.id)
        assertFailsWith<SignatureVerificationException> {
            WebhookSignature.constructEvent(body.toByteArray(), header, "whsec_other", nowSeconds = ts)
        }
    }

    @Test
    fun clientExposesVerification() {
        val c = AyInbox(apiKey = "aybox_x", environment = { null })
        assertTrue(c.webhooks.verifySignature(body.toByteArray(), header, secret, nowSeconds = ts))
    }
}
