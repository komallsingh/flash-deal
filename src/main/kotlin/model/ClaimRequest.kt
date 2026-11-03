package model

import kotlinx.serialization.Serializable

@Serializable
data class ClaimRequest(
    val quantity: Int
)