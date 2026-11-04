
package com.nnomm

import com.nnomm.deals.CreateDealRequest
import com.nnomm.deals.DealService
import com.nnomm.errors.AppError
import com.nnomm.security.JwtService
import com.nnomm.security.requireUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import model.ClaimRequest
import io.ktor.http.ContentType
import repository.ClaimRepository
import repository.IdempotencyRepository
import model.IdempotencyResult
import service.RequestHasher
import model.PaymentWebhookRequest
import repository.PaymentRepository
import security.WebhookSignatureVerifier
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant

fun Application.configureRouting() {

    val dealService = DealService()
    val jwtService = JwtService()
    val claimRepository = ClaimRepository()
    val idempotencyRepository = IdempotencyRepository()
    val paymentRepository = PaymentRepository()
val webhookSignatureVerifier = WebhookSignatureVerifier()
    routing {

        get("/") {
            call.respondText("Hello, World!")
        }

        get("/v1/deals") {
            call.requireUser(jwtService)
            throw AppError(
                HttpStatusCode.BadRequest,
                "VALIDATION_FAILED",
                "Deal ID is required"
            )
        }

        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }

        // Create a deal (restaurant admin only)
        post("/v1/deals") {

            val user = call.requireUser(jwtService)

            if (user.role != "restaurant_admin") {
                throw AppError(
                    HttpStatusCode.Forbidden,
                    "FORBIDDEN",
                    "Only restaurant admins can create deals"
                )
            }

            val restaurantId = user.restaurantId
                ?: throw AppError(
                    HttpStatusCode.Forbidden,
                    "FORBIDDEN",
                    "Restaurant ID is missing"
                )

            val request = call.receiveValidatedJson<CreateDealRequest>()

            val response = dealService.createDeal(
                request,
                restaurantId
            )

            call.respond(HttpStatusCode.Created, response)
        }

        // Retrieve a deal
        get("/v1/deals/{dealId}") {

            call.requireUser(jwtService)

            val dealId = parseUuid(call.parameters["dealId"], "Deal")

            val response = dealService.getDeal(dealId)

            call.respond(HttpStatusCode.OK, response)
        }
        post("/v1/deals/{dealId}/claims") {

    // 1. Authenticate customer
    val user = call.requireUser(jwtService)

    if (user.role != "customer") {
        throw AppError(
            HttpStatusCode.Forbidden,
            "FORBIDDEN",
            "Only customers can claim deals"
        )
    }

    // 2. Validate deal ID
    val dealId = parseUuid(call.parameters["dealId"], "Deal")

    // 3. Validate Idempotency-Key
    val idempotencyKey = call.request.headers["Idempotency-Key"]

    if (idempotencyKey == null ||
        !idempotencyKey.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))
    ) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "IDEMPOTENCY_KEY_INVALID",
            "A valid Idempotency-Key header is required"
        )
    }

    // 4. Receive and validate request
    val request = call.receiveValidatedJson<ClaimRequest>()

    if (request.quantity !in 1..3) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "Quantity must be between 1 and 3"
        )
    }

    // 5. Calculate request hash
    val requestHash = RequestHasher.hash(
        dealId,
        request.quantity
    )

    // 6. Acquire idempotency key
    when (
        val result = idempotencyRepository.acquire(
            user.userId,
            idempotencyKey,
            requestHash
        )
    ) {

        is IdempotencyResult.Acquired -> {

            // 7. Execute claim transaction
            val claimResult = claimRepository.createClaim(
                customerId = user.userId,
                dealId = dealId,
                quantity = request.quantity,
                idempotencyKey = idempotencyKey,
                leaseToken = result.leaseToken
            )

            call.respondText(
                text = claimResult.body,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.fromValue(claimResult.statusCode)
            )
        }

        is IdempotencyResult.Replay -> {

            // Return previously stored response
            call.response.headers.append(
                "Idempotent-Replayed",
                "true"
            )

            call.respondText(
                text = result.body,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.fromValue(result.status)
            )
        }

        is IdempotencyResult.InProgress -> {

            throw AppError(
                HttpStatusCode.Conflict,
                "REQUEST_IN_PROGRESS",
                "Request is already being processed"
            )
        }

        is IdempotencyResult.KeyReused -> {

            throw AppError(
                HttpStatusCode.UnprocessableEntity,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was used with a different request"
            )
        }
    }
}
post("/v1/deals/claims") {
    val user = call.requireUser(jwtService)
    if (user.role != "customer") {
        throw AppError(
            HttpStatusCode.Forbidden,
            "FORBIDDEN",
            "Only customers can claim deals"
        )
    }

    throw AppError(
        HttpStatusCode.BadRequest,
        "VALIDATION_FAILED",
        "Deal ID is required"
    )
}
get("/v1/claims") {
    call.requireUser(jwtService)
    throw AppError(
        HttpStatusCode.BadRequest,
        "VALIDATION_FAILED",
        "Claim ID is required"
    )
}
// Retrieve a claim (customer owner or restaurant admin)
get("/v1/claims/{claimId}") {

    // 1. Authenticate user
    val user = call.requireUser(jwtService)

    // 2. Validate claim ID
    val claimId = parseUuid(call.parameters["claimId"], "Claim")

    // 3. Fetch claim with ownership authorization
    val claim = claimRepository.getClaim(
        claimId = claimId,
        userId = user.userId,
        role = user.role,
        restaurantId = user.restaurantId
    ) ?: throw AppError(
        HttpStatusCode.NotFound,
        "NOT_FOUND",
        "Claim not found"
    )

    // 4. Return claim
    call.respond(HttpStatusCode.OK, claim)
}
post("/v1/webhooks/payments") {
    // 1. Read the original bytes. This also enforces the 16 KB limit
    // and checks the JSON content type.
    val rawBody = call.receiveRawValidatedBody()

    // 2. Verify the signature BEFORE parsing the JSON.
    webhookSignatureVerifier.verify(
        signatureHeader = call.request.headers["X-Payment-Signature"],
        rawBody = rawBody
    )

    // 3. Deserialize the exact bytes that were signed.
    val event = try {
        Json.decodeFromString<PaymentWebhookRequest>(
            rawBody.decodeToString()
        )
    } catch (exception: SerializationException) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "Payment webhook body is malformed or missing required fields"
        )
    }

    // 4. Validate the event fields.
    if (event.event_id.isBlank() || event.event_id.length > 255) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "event_id must contain between 1 and 255 characters"
        )
    }

    if (event.type != "payment.succeeded" &&
        event.type != "payment.failed"
    ) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "Unsupported payment event type"
        )
    }

    val claimId = try {
        UUID.fromString(event.claim_id)
    } catch (exception: IllegalArgumentException) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "claim_id must be a valid UUID"
        )
    }

    if (event.amount_paise <= 0) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "amount_paise must be positive"
        )
    }

    try {
        Instant.parse(event.occurred_at)
    } catch (exception: Exception) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "occurred_at must be a valid ISO-8601 timestamp"
        )
    }

    // 5. Process the event transactionally.
    paymentRepository.processEvent(event)

    // Both newly processed and duplicate events receive HTTP 200.
    call.respond(HttpStatusCode.OK)
}
    }
    

}

private fun parseUuid(value: String?, resource: String): UUID {
    return try {
        UUID.fromString(value ?: throw IllegalArgumentException())
    } catch (exception: IllegalArgumentException) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "Invalid $resource ID"
        )
    }
}
