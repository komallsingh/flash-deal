package com.nnomm.deals

import com.nnomm.errors.AppError
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

class DealService(
    private val repository: DealRepository = DealRepository()
) {

    fun createDeal(
        request: CreateDealRequest,
        restaurantId: UUID
    ): DealResponse {

        // 1. Validate title
        val title = request.title.trim()

        if (title.isEmpty() || title.length > 100) {
            throw validationError("Title must contain 1–100 characters")
        }

        // 2. Validate price
        if (request.pricePaise !in 1..1_000_000) {
            throw validationError("Price must be between 1 and 1000000 paise")
        }

        // 3. Validate stock
        if (request.stockTotal !in 1..10_000) {
            throw validationError("Stock must be between 1 and 10000")
        }

        // 4. Parse timestamps
        val startsAt = parseTimestamp(request.startsAt)
        val endsAt = parseTimestamp(request.endsAt)

        // 5. Validate time range
        if (!startsAt.isBefore(endsAt)) {
            throw validationError("starts_at must be before ends_at")
        }

        // 6. Create internal Deal object
        val deal = Deal(
            id = UUID.randomUUID(),
            restaurantId = restaurantId,
            title = title,
            pricePaise = request.pricePaise,
            stockTotal = request.stockTotal,
            stockAvailable = request.stockTotal,
            startsAt = startsAt,
            endsAt = endsAt
        )

        // 7. Save in PostgreSQL
        val savedDeal = repository.create(deal)

        // 8. Convert to API response
        return toResponse(savedDeal)
    }

    fun getDeal(dealId: UUID): DealResponse {
        val deal = repository.findById(dealId)
            ?: throw AppError(
                status = HttpStatusCode.NotFound,
                code = "NOT_FOUND",
                message = "Deal not found"
            )

        return toResponse(deal)
    }

    private fun parseTimestamp(value: String): Instant {
        return try {
            val timestamp = OffsetDateTime.parse(value)
            if (timestamp.offset != ZoneOffset.UTC) {
                throw DateTimeParseException(
                    "Timestamp must be in UTC",
                    value,
                    0
                )
            }

            timestamp.toInstant()
        } catch (exception: DateTimeParseException) {
            throw validationError(
                "Timestamp must be a valid ISO 8601 UTC timestamp"
            )
        }
    }

    private fun toResponse(deal: Deal): DealResponse {
        return DealResponse(
            id = deal.id.toString(),
            restaurantId = deal.restaurantId.toString(),
            title = deal.title,
            pricePaise = deal.pricePaise,
            stockTotal = deal.stockTotal,
            stockAvailable = deal.stockAvailable,
            startsAt = deal.startsAt.toString(),
            endsAt = deal.endsAt.toString()
        )
    }

    private fun validationError(message: String): AppError {
        return AppError(
            status = HttpStatusCode.BadRequest,
            code = "VALIDATION_FAILED",
            message = message
        )
    }
}