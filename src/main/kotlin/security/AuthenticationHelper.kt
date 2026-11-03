package com.nnomm.security

import com.nnomm.errors.AppError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

fun ApplicationCall.requireUser(
    jwtService: JwtService
): AuthenticatedUser {

    val authorization = request.headers["Authorization"]
        ?: throw AppError(
            HttpStatusCode.Unauthorized,
            "UNAUTHENTICATED",
            "Authorization header is required"
        )

    if (!authorization.startsWith("Bearer ")) {
        throw AppError(
            HttpStatusCode.Unauthorized,
            "UNAUTHENTICATED",
            "A bearer token is required"
        )
    }

    val token = authorization.removePrefix("Bearer ").trim()

    if (token.isEmpty()) {
        throw AppError(
            HttpStatusCode.Unauthorized,
            "UNAUTHENTICATED",
            "A bearer token is required"
        )
    }

    return jwtService.verify(token)
}
