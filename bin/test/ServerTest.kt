
package com.nnomm

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.config.DatabaseFactory
import com.nnomm.deals.DealResponse
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.decodeFromString

class ServerTest {

    private fun createToken(
        role: String,
        restaurantId: UUID? = null
    ): String {
        val secret = System.getenv("JWT_SECRET")
            ?: error("JWT_SECRET is missing")

        val builder = JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))

        if (restaurantId != null) {
            builder.withClaim("restaurant_id", restaurantId.toString())
        }

        return builder.sign(Algorithm.HMAC256(secret))
    }

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureDatabase()
            configureRouting()
        }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `deal API handles authentication creation and retrieval`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureDatabase()
            configureRouting()
        }

        val restaurantId = UUID.randomUUID()
        val adminToken = createToken("restaurant_admin", restaurantId)
        val customerToken = createToken("customer")

        val startsAt = Instant.now().plusSeconds(60).toString()
        val endsAt = Instant.now().plusSeconds(3600).toString()

        val validRequest = """
            {
                "title": "Test Flash Deal",
                "price_paise": 5000,
                "stock_total": 10,
                "starts_at": "$startsAt",
                "ends_at": "$endsAt"
            }
        """.trimIndent()

        // 1. Missing JWT must return 401
        val unauthenticatedResponse = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(validRequest)
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            unauthenticatedResponse.status
        )

        // 2. Customer must not create a deal
        val customerResponse = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            contentType(ContentType.Application.Json)
            setBody(validRequest)
        }

        assertEquals(HttpStatusCode.Forbidden, customerResponse.status)

        // 3. Invalid price must return 400
        val invalidRequest = validRequest.replace(
            "\"price_paise\": 5000",
            "\"price_paise\": 0"
        )

        val invalidResponse = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(invalidRequest)
        }

        assertEquals(HttpStatusCode.BadRequest, invalidResponse.status)

        // 4. Restaurant admin must successfully create a deal
        val createResponse = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(validRequest)
        }

        assertEquals(HttpStatusCode.Created, createResponse.status)

        val createdDeal = Json.decodeFromString<DealResponse>(
            createResponse.bodyAsText()
        )

        try {
            assertEquals(restaurantId.toString(), createdDeal.restaurantId)
            assertEquals("Test Flash Deal", createdDeal.title)
            assertEquals(10, createdDeal.stockAvailable)

            // 5. Retrieve the created deal
            val getResponse = client.get("/v1/deals/${createdDeal.id}") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
            }

            assertEquals(HttpStatusCode.OK, getResponse.status)

            val retrievedDeal = Json.decodeFromString<DealResponse>(
                getResponse.bodyAsText()
            )

            assertEquals(createdDeal.id, retrievedDeal.id)
            assertEquals(createdDeal.title, retrievedDeal.title)

            // 6. Invalid UUID must return 400
            val invalidIdResponse = client.get("/v1/deals/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
            }

            assertEquals(HttpStatusCode.BadRequest, invalidIdResponse.status)

            // 7. Nonexistent deal must return 404
            val missingDealResponse = client.get(
                "/v1/deals/${UUID.randomUUID()}"
            ) {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
            }

            assertEquals(HttpStatusCode.NotFound, missingDealResponse.status)

        } finally {
            // Remove test data
            DatabaseFactory.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM deals WHERE id = ?"
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(createdDeal.id))
                    statement.executeUpdate()
                }
            }
        }
    }
}
