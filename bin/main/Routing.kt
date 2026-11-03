
package com.nnomm

import com.nnomm.deals.CreateDealRequest
import com.nnomm.deals.DealService
import com.nnomm.errors.AppError
import com.nnomm.security.JwtService
import com.nnomm.security.requireUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.configureRouting() {

    val dealService = DealService()
    val jwtService = JwtService()

    routing {

        get("/") {
            call.respondText("Hello, World!")
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

            val request = call.receive<CreateDealRequest>()

            val response = dealService.createDeal(
                request,
                restaurantId
            )

            call.respond(HttpStatusCode.Created, response)
        }

        // Retrieve a deal
        get("/v1/deals/{dealId}") {

            call.requireUser(jwtService)

            val dealIdString = call.parameters["dealId"]
                ?: throw AppError(
                    HttpStatusCode.BadRequest,
                    "VALIDATION_FAILED",
                    "Deal ID is required"
                )

            val dealId = try {
                UUID.fromString(dealIdString)
            } catch (exception: IllegalArgumentException) {
                throw AppError(
                    HttpStatusCode.BadRequest,
                    "VALIDATION_FAILED",
                    "Invalid deal ID"
                )
            }

            val response = dealService.getDeal(dealId)

            call.respond(HttpStatusCode.OK, response)
        }
    }
}
