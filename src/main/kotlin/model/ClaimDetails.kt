package model

import kotlinx.serialization.Serializable

@Serializable
data class ClaimDetails(
    val id: String,
    val deal_id: String,
    val customer_id: String,
    val quantity: Int,
    val amount_paise: Int,
    val status: String,
    val expires_at: String,
    val created_at: String
)