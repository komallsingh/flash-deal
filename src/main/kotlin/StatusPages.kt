package com.nnomm

import com.nnomm.errors.AppError
import com.nnomm.errors.ErrorDetail
import com.nnomm.errors.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.plugins.requestvalidation.RequestValidationException

fun Application.configureStatusPages() {
    install(StatusPages) {

        exception<AppError> { call, cause ->
            call.respondError(
                cause.status,
                cause.code,
                cause.message
            )
        }

        exception<ContentTransformationException> { call, _ ->
            call.respondError(
                HttpStatusCode.BadRequest,
                "VALIDATION_FAILED",
                "Request body is malformed or missing required fields"
            )
        }

        exception<BadRequestException> { call, _ ->
            call.respondError(
                HttpStatusCode.BadRequest,
                "VALIDATION_FAILED",
                "Request is malformed"
            )
        }

        exception<RequestValidationException> { call, _ ->
            call.respondError(
                HttpStatusCode.BadRequest,
                "VALIDATION_FAILED",
                "Request validation failed"
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(
                HttpStatusCode.NotFound,
                "NOT_FOUND",
                "The requested resource was not found"
            )
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error(
                "Unhandled application error",
                cause
            )

            call.respondError(
                HttpStatusCode.InternalServerError,
                "INTERNAL",
                "An unexpected error occurred"
            )
        }
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String
) {
    if (response.isCommitted || response.isSent) {
        return
    }

    respond(
        status = status,
        message = ErrorResponse(
            error = ErrorDetail(
                code = code,
                message = message
            )
        )
    )
}