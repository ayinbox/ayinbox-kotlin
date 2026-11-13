import com.ayinbox.SignatureVerificationException
import com.ayinbox.WebhookSignature

// Verifies the published test vector. In a real handler, pass the raw request body bytes,
// the X-Ayinbox-Signature header and your endpoint secret (whsec_...).
fun main() {
    val secret = System.getenv("AYINBOX_WEBHOOK_SECRET") ?: "whsec_test_secret"
    val body = """{"id":"00000000-0000-4000-8000-000000000001","event_type":"campaign.reply_received",""" +
        """"organization_id":"00000000-0000-4000-8000-0000000000aa","created_at":"2023-11-14T22:13:20Z","data":{}}"""
    val header = "t=1700000000,v1=d1efe3010ae2523566985158ab4852f8f3569365a06941ef87e3a641e3171342"

    try {
        val event = WebhookSignature.constructEvent(body.toByteArray(), header, secret, nowSeconds = 1700000000)
        println("verified ${event.eventType} ${event.id}")
    } catch (e: SignatureVerificationException) {
        println("rejected: ${e.message}")
    }
}
