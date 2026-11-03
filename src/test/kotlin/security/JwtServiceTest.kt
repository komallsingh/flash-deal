package com.nnomm.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.errors.AppError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import java.util.Date
import java.util.UUID

class JwtServiceTest {

    private val secret = "test-secret-key-for-local-tests"
    private val jwtService = JwtService(secret)

    private fun generateToken(
        role: String,
        restaurantId: UUID? = null,
        signingSecret: String = secret,
        expiry: Date = Date(System.currentTimeMillis() + 3600000)
    ): String {
        val builder = JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("role", role)
            .withExpiresAt(expiry)

        if (restaurantId != null) {
            builder.withClaim("restaurant_id", restaurantId.toString())
        }

        return builder.sign(Algorithm.HMAC256(signingSecret))
    }

    private fun assertUnauthorized(token: String) {
        val exception = assertFailsWith<AppError> {
            jwtService.verify(token)
        }

        assertEquals(HttpStatusCode.Unauthorized, exception.status)
        assertEquals("UNAUTHENTICATED", exception.code)
    }

    @Test
    fun `valid customer token is accepted`() {
        val token = generateToken("customer")

        val user = jwtService.verify(token)

        assertEquals("customer", user.role)
        assertNull(user.restaurantId)
    }

    @Test
    fun `valid restaurant admin token is accepted`() {
        val restaurantId = UUID.randomUUID()
        val token = generateToken(
            role = "restaurant_admin",
            restaurantId = restaurantId
        )

        val user = jwtService.verify(token)

        assertEquals("restaurant_admin", user.role)
        assertEquals(restaurantId, user.restaurantId)
    }

    @Test
    fun `expired token is rejected`() {
        val token = generateToken(
            role = "customer",
            expiry = Date(System.currentTimeMillis() - 60000)
        )

        assertUnauthorized(token)
    }

    @Test
    fun `invalid signature is rejected`() {
        val token = generateToken(
            role = "customer",
            signingSecret = "different-test-secret"
        )

        assertUnauthorized(token)
    }

    @Test
    fun `malformed token is rejected`() {
        assertUnauthorized("not-a-jwt")
    }

    @Test
    fun `unsupported signing algorithm is rejected`() {
        val token = JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("role", "customer")
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC384(secret))

        assertUnauthorized(token)
    }

    @Test
    fun `invalid role is rejected`() {
        val token = generateToken("admin")

        assertUnauthorized(token)
    }

    @Test
    fun `restaurant admin without restaurant id is rejected`() {
        val token = generateToken("restaurant_admin")

        assertUnauthorized(token)
    }
}