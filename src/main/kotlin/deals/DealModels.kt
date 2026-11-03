package com.nnomm.deals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

// Data received from POST /v1/deals
@Serializable
data class CreateDealRequest(
    val title: String,

    @SerialName("price_paise")
    val pricePaise: Int,

    @SerialName("stock_total")
    val stockTotal: Int,

    @SerialName("starts_at")
    val startsAt: String,

    @SerialName("ends_at")
    val endsAt: String
)

// Internal application representation
data class Deal(
    val id: UUID,
    val restaurantId: UUID,
    val title: String,
    val pricePaise: Int,
    val stockTotal: Int,
    val stockAvailable: Int,
    val startsAt: Instant,
    val endsAt: Instant
)

// Data returned by POST and GET
@Serializable
data class DealResponse(
    val id: String,

    @SerialName("restaurant_id")
    val restaurantId: String,

    val title: String,

    @SerialName("price_paise")
    val pricePaise: Int,

    @SerialName("stock_total")
    val stockTotal: Int,

    @SerialName("stock_available")
    val stockAvailable: Int,

    @SerialName("starts_at")
    val startsAt: String,

    @SerialName("ends_at")
    val endsAt: String
)