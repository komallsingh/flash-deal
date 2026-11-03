package com.nnomm

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.config.DatabaseFactory
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ErrorContractTest {

    private val secret = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET is missing")

    private fun createToken(
        role: String,
        expiresAt: Date = Date(System.currentTimeMillis() + 3_600_000),
        userId: UUID = UUID.randomUUID()
    ): String {
        val builder = JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", role)
            .withExpiresAt(expiresAt)

        if (role == "restaurant_admin") {
            builder.withClaim("restaurant_id", UUID.randomUUID().toString())
        }

        return builder.sign(Algorithm.HMAC256(secret))
    }

    @Test
    fun `authentication validation and missing resources use the error contract`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureDatabase()
            configureRouting()
        }

        val customerToken = createToken("customer")
        val adminToken = createToken("restaurant_admin")
        val dealBody = """{"title":"Test","price_paise":100,"stock_total":1,"starts_at":"2030-01-01T00:00:00Z","ends_at":"2030-01-02T00:00:00Z"}"""
        val claimBody = """{"quantity":1}"""

        assertError(
            client.get("/v1/deals/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer invalid.token")
            },
            HttpStatusCode.Unauthorized,
            "UNAUTHENTICATED"
        )
        assertError(
            client.get("/v1/deals/${UUID.randomUUID()}") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${createToken("customer", Date(System.currentTimeMillis() - 60_000))}"
                )
            },
            HttpStatusCode.Unauthorized,
            "UNAUTHENTICATED"
        )
        assertError(
            client.post("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
                contentType(ContentType.Application.Json)
                setBody(dealBody)
            },
            HttpStatusCode.Forbidden,
            "FORBIDDEN"
        )
        assertError(
            client.get("/v1/deals/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
            },
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED"
        )
        assertError(
            client.get("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
            },
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED"
        )
        assertError(
            client.post("/v1/deals/${UUID.randomUUID()}/claims") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
                contentType(ContentType.Application.Json)
                setBody("""{"quantity":4}""")
                header("Idempotency-Key", "missing-deal-key")
            },
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED"
        )
        assertError(
            client.post("/v1/deals/${UUID.randomUUID()}/claims") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
                contentType(ContentType.Application.Json)
                setBody(claimBody)
            },
            HttpStatusCode.BadRequest,
            "IDEMPOTENCY_KEY_INVALID"
        )
        assertError(
            client.post("/v1/deals/${UUID.randomUUID()}/claims") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
                header("Idempotency-Key", "bad key")
                contentType(ContentType.Application.Json)
                setBody(claimBody)
            },
            HttpStatusCode.BadRequest,
            "IDEMPOTENCY_KEY_INVALID"
        )
        assertError(
            client.post("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"title":""")
            },
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED"
        )
        assertError(
            client.post("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"${"x".repeat(17 * 1024)}"}""")
            },
            HttpStatusCode.PayloadTooLarge,
            "PAYLOAD_TOO_LARGE"
        )
        assertError(
            client.get("/v1/no-such-route"),
            HttpStatusCode.NotFound,
            "NOT_FOUND"
        )
    }

    @Test
    fun `idempotency conflicts use the error contract`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureDatabase()
            configureRouting()
        }

        val customerId = UUID.randomUUID()
        val token = createToken("customer", userId = customerId)
        val dealId = UUID.randomUUID()
        val key = "contract-idempotency-key"

        try {
            assertError(
                client.post("/v1/deals/$dealId/claims") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity":1}""")
                },
                HttpStatusCode.NotFound,
                "NOT_FOUND"
            )
            assertError(
                client.post("/v1/deals/$dealId/claims") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity":1}""")
                },
                HttpStatusCode.Conflict,
                "REQUEST_IN_PROGRESS",
                "Request is already being processed"
            )
            assertError(
                client.post("/v1/deals/$dealId/claims") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity":2}""")
                },
                HttpStatusCode.UnprocessableEntity,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was used with a different request"
            )
        } finally {
            DatabaseFactory.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM idempotency_keys WHERE customer_id = ?"
                ).use { statement ->
                    statement.setObject(1, customerId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `claim business outcomes use the error contract`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureDatabase()
            configureRouting()
        }

        val customerId = UUID.randomUUID()
        val customerToken = createToken("customer", userId = customerId)
        val adminToken = createToken("restaurant_admin")
        val createdDeals = mutableListOf<UUID>()

        suspend fun createDeal(
            stock: Int,
            startsAt: Date,
            endsAt: Date
        ): UUID {
            val start = startsAt.toInstant().toString()
            val end = endsAt.toInstant().toString()
            val response = client.post("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"title":"Contract test","price_paise":100,"stock_total":$stock,"starts_at":"$start","ends_at":"$end"}"""
                )
            }
            assertEquals(HttpStatusCode.Created, response.status)
            return UUID.fromString(
                Json.parseToJsonElement(response.bodyAsText())
                    .jsonObject.getValue("id").jsonPrimitive.content
            ).also { createdDeals.add(it) }
        }

        suspend fun submitClaim(dealId: UUID, key: String) = client.post(
            "/v1/deals/$dealId/claims"
        ) {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody("""{"quantity":1}""")
        }

        try {
            val liveStart = Date(System.currentTimeMillis() - 60_000)
            val liveEnd = Date(System.currentTimeMillis() + 3_600_000)

            val soldOutDeal = createDeal(1, liveStart, liveEnd)
            DatabaseFactory.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE deals SET stock_available = 0 WHERE id = ?"
                ).use { statement ->
                    statement.setObject(1, soldOutDeal)
                    statement.executeUpdate()
                }
            }
            assertError(
                submitClaim(soldOutDeal, "contract-sold-out"),
                HttpStatusCode.Conflict,
                "SOLD_OUT",
                "Not enough units available"
            )

            val claimedDeal = createDeal(2, liveStart, liveEnd)
            val firstClaim = submitClaim(claimedDeal, "contract-first-claim")
            assertEquals(HttpStatusCode.Created, firstClaim.status)
            assertError(
                submitClaim(claimedDeal, "contract-second-claim"),
                HttpStatusCode.Conflict,
                "ALREADY_CLAIMED",
                "Customer already holds an active claim on this deal"
            )

            val notLiveDeal = createDeal(
                1,
                Date(System.currentTimeMillis() + 3_600_000),
                Date(System.currentTimeMillis() + 7_200_000)
            )
            assertError(
                submitClaim(notLiveDeal, "contract-not-live"),
                HttpStatusCode.Conflict,
                "DEAL_NOT_LIVE",
                "Deal has not started or has ended"
            )
        } finally {
            DatabaseFactory.dataSource.connection.use { connection ->
                createdDeals.forEach { dealId ->
                    connection.prepareStatement(
                        "DELETE FROM claims WHERE deal_id = ?"
                    ).use { statement ->
                        statement.setObject(1, dealId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        "DELETE FROM deals WHERE id = ?"
                    ).use { statement ->
                        statement.setObject(1, dealId)
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    "DELETE FROM idempotency_keys WHERE customer_id = ?"
                ).use { statement ->
                    statement.setObject(1, customerId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `unexpected exceptions return a generic internal error`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/failure") {
                    error("private database detail")
                }
            }
        }

        val response = client.get("/failure")
        assertError(response, HttpStatusCode.InternalServerError, "INTERNAL")
        assertFalse(response.bodyAsText().contains("private database detail"))
    }

    private suspend fun assertError(
        response: HttpResponse,
        expectedStatus: HttpStatusCode,
        expectedCode: String,
        expectedMessage: String? = null
    ) {
        assertEquals(expectedStatus, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject.getValue("error").jsonObject
        assertEquals(expectedCode, error.getValue("code").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertFalse(message.isBlank())
        if (expectedMessage != null) {
            assertEquals(expectedMessage, message)
        }
    }
}
