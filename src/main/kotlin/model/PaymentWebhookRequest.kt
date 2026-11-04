
package model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentWebhookRequest(
    val event_id: String,
    val type: String,
    val claim_id: String,
    val amount_paise: Int,
    val occurred_at: String
)
