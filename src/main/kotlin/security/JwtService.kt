package com.nnomm.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.errors.AppError
import io.ktor.http.HttpStatusCode
import java.util.Date
import java.util.UUID

class JwtService(
    secret: String = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET environment variable is missing")
) {

    init {
        require(secret.isNotBlank()) { "JWT_SECRET must not be blank" }
    }

    private val algorithm = Algorithm.HMAC256(secret)

    fun verify(token: String): AuthenticatedUser {
        try {
            val verifier = JWT.require(algorithm).build()

            val decoded = verifier.verify(token)

            if (decoded.algorithm != "HS256") {
                throw IllegalArgumentException("Unsupported signing algorithm")
            }

            val subject = decoded.subject
                ?: throw IllegalArgumentException("Missing subject")
            val userId = UUID.fromString(subject)

            val expiresAt = decoded.expiresAt
                ?: throw IllegalArgumentException("Missing expiration")
            if (!expiresAt.after(Date())) {
                throw IllegalArgumentException("Token has expired")
            }

            val role = decoded.getClaim("role").asString()
                ?: throw IllegalArgumentException("Missing role")

            if (role !in setOf("customer", "restaurant_admin")) {
                throw IllegalArgumentException("Invalid role")
            }

            val restaurantId = if (role == "restaurant_admin") {
                val value = decoded.getClaim("restaurant_id").asString()
                    ?: throw IllegalArgumentException("Missing restaurant ID")
                UUID.fromString(value)
            } else {
                null
            }

            return AuthenticatedUser(
                userId = userId,
                role = role,
                restaurantId = restaurantId
            )

        } catch (exception: Exception) {
            throw AppError(
                status = HttpStatusCode.Unauthorized,
                code = "UNAUTHENTICATED",
                message = "Invalid or expired authentication token"
            )
        }
    }
}