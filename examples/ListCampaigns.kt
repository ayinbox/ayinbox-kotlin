import com.ayinbox.AyInbox
import kotlinx.coroutines.runBlocking

// Reads AYINBOX_API_KEY. Run with: gradle listCampaigns
fun main() = runBlocking {
    val client = AyInbox()
    client.campaigns.listAll(limit = 50).collect { campaign ->
        println("${campaign.id}  ${campaign.status.padEnd(10)}  ${campaign.name}")
    }
}
