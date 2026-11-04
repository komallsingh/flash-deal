
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
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.sql.Timestamp

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
    private fun createWebhookSignature(
    body: ByteArray,
    timestamp: Long
): String {
    val secret = System.getenv("WEBHOOK_SECRET")
        ?: error("WEBHOOK_SECRET is missing")

    val message = "$timestamp.".toByteArray(StandardCharsets.UTF_8) + body

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(
        SecretKeySpec(
            secret.toByteArray(StandardCharsets.UTF_8),
            "HmacSHA256"
        )
    )

    val signature = mac.doFinal(message)
        .joinToString("") { "%02x".format(it) }

    return "t=$timestamp,v1=$signature"
}
@Test
fun `valid webhook succeeds without JWT`() = testApplication {
    application {
        configureSerialization()
        configureStatusPages()
        configureDatabase()
        configureRouting()
    }

    val eventId = "integration-unknown-${UUID.randomUUID()}"
    val body = """
        {
            "event_id": "$eventId",
            "type": "payment.succeeded",
            "claim_id": "${UUID.randomUUID()}",
            "amount_paise": 50000,
            "occurred_at": "${Instant.now()}"
        }
    """.trimIndent().toByteArray()

    val timestamp = Instant.now().epochSecond

    try {
        val response = client.post("/v1/webhooks/payments") {
            contentType(ContentType.Application.Json)
            header(
                "X-Payment-Signature",
                createWebhookSignature(body, timestamp)
            )
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // No Authorization header was supplied.
        assertNull(response.headers[HttpHeaders.WWWAuthenticate])

    } finally {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM payment_events WHERE event_id = ?"
            ).use { statement ->
                statement.setString(1, eventId)
                statement.executeUpdate()
            }
        }
    }
}
@Test
fun `webhook rejects missing signature`() = testApplication {
    application {
        configureSerialization()
        configureStatusPages()
        configureDatabase()
        configureRouting()
    }

    val response = client.post("/v1/webhooks/payments") {
        contentType(ContentType.Application.Json)
        setBody("""{"event_id":"evt_missing"}""")
    }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
}

@Test
fun `webhook rejects incorrect signature`() = testApplication {
    application {
        configureSerialization()
        configureStatusPages()
        configureDatabase()
        configureRouting()
    }

    val body = """{"event_id":"evt_invalid"}"""
    val timestamp = Instant.now().epochSecond

    val response = client.post("/v1/webhooks/payments") {
        contentType(ContentType.Application.Json)
        header(
            "X-Payment-Signature",
            "t=$timestamp,v1=${"0".repeat(64)}"
        )
        setBody(body)
    }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
}

@Test
fun `webhook rejects malformed JSON after signature verification`() = testApplication {
    application {
        configureSerialization()
        configureStatusPages()
        configureDatabase()
        configureRouting()
    }

    val body = """{"event_id":"""
        .toByteArray(StandardCharsets.UTF_8)

    val timestamp = Instant.now().epochSecond

    val response = client.post("/v1/webhooks/payments") {
        contentType(ContentType.Application.Json)
        header(
            "X-Payment-Signature",
            createWebhookSignature(body, timestamp)
        )
        setBody(body)
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
}
@Test
fun `valid webhook pays real claim and duplicate delivery changes nothing`() = testApplication {
    application {
        configureSerialization()
        configureStatusPages()
        configureDatabase()
        configureRouting()
    }

    val dealId = UUID.randomUUID()
    val claimId = UUID.randomUUID()
    val customerId = UUID.randomUUID()
    val eventId = "http-payment-${UUID.randomUUID()}"

    assertEquals(HttpStatusCode.OK, client.get("/").status)

    try {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO deals (
                    id, restaurant_id, title, price_paise,
                    stock_total, stock_available, starts_at, ends_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, dealId)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, "Webhook Integration Deal")
                statement.setInt(4, 50000)
                statement.setInt(5, 10)
                statement.setInt(6, 9)
                statement.setTimestamp(7, Timestamp.from(Instant.now().minusSeconds(60)))
                statement.setTimestamp(8, Timestamp.from(Instant.now().plusSeconds(3600)))
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO claims (
                    id, deal_id, customer_id, quantity,
                    amount_paise, status, expires_at
                )
                VALUES (?, ?, ?, 1, 50000, 'RESERVED', ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, claimId)
                statement.setObject(2, dealId)
                statement.setObject(3, customerId)
                statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(600)))
                statement.executeUpdate()
            }
        }

        val body = """
            {
                "event_id": "$eventId",
                "type": "payment.succeeded",
                "claim_id": "$claimId",
                "amount_paise": 50000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent()

        val timestamp = Instant.now().epochSecond
        val signature = createWebhookSignature(
            body.toByteArray(StandardCharsets.UTF_8),
            timestamp
        )

        // First delivery processes the payment.
        val firstResponse = client.post("/v1/webhooks/payments") {
            contentType(ContentType.Application.Json)
            header("X-Payment-Signature", signature)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, firstResponse.status)

        // Second delivery is acknowledged but must not apply the event again.
        val duplicateResponse = client.post("/v1/webhooks/payments") {
            contentType(ContentType.Application.Json)
            header("X-Payment-Signature", signature)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, duplicateResponse.status)

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status FROM claims WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, claimId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("PAID", result.getString("status"))
                }
            }

            connection.prepareStatement(
                "SELECT stock_available FROM deals WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, dealId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(9, result.getInt("stock_available"))
                }
            }

            connection.prepareStatement(
                "SELECT COUNT(*) FROM payment_events WHERE event_id = ?"
            ).use { statement ->
                statement.setString(1, eventId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    } finally {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM payment_events WHERE event_id = ?"
            ).use {
                it.setString(1, eventId)
                it.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM claims WHERE id = ?"
            ).use {
                it.setObject(1, claimId)
                it.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM deals WHERE id = ?"
            ).use {
                it.setObject(1, dealId)
                it.executeUpdate()
            }
        }
    }
}
@Test
fun `webhook rejects stale signature`() = testApplication {
    application {
        configureSerialization()
        configureStatusPages()
        configureDatabase()
        configureRouting()
    }

    val body = """
        {
            "event_id": "stale-${UUID.randomUUID()}",
            "type": "payment.succeeded",
            "claim_id": "${UUID.randomUUID()}",
            "amount_paise": 50000,
            "occurred_at": "${Instant.now()}"
        }
    """.trimIndent()

    val staleTimestamp = Instant.now().epochSecond - 301

    val response = client.post("/v1/webhooks/payments") {
        contentType(ContentType.Application.Json)
        header(
            "X-Payment-Signature",
            createWebhookSignature(
                body.toByteArray(StandardCharsets.UTF_8),
                staleTimestamp
            )
        )
        setBody(body)
    }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
}
}
