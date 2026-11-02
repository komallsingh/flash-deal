package com.nnomm

import com.nnomm.errors.AppError
import com.nnomm.errors.ErrorDetail
import com.nnomm.errors.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {

        exception<AppError> { call, cause ->
            call.respond(
                status = cause.status,
                message = ErrorResponse(
                    error = ErrorDetail(
                        code = cause.code,
                        message = cause.message
                    )
                )
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                status = HttpStatusCode.NotFound,
                message = ErrorResponse(
                    error = ErrorDetail(
                        code = "NOT_FOUND",
                        message = "The requested resource was not found"
                    )
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error(
    "Unhandled application error",
    cause
)

            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ErrorResponse(
                    error = ErrorDetail(
                        code = "INTERNAL",
                        message = "An unexpected error occurred"
                    )
                )
            )
        }
    }
}