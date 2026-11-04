package com.nnomm

import com.nnomm.errors.AppError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MAX_REQUEST_BODY_BYTES = 16 * 1024

fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validate<String> { bodyText ->
            if (!bodyText.startsWith("Hello"))
                ValidationResult.Invalid("Body text should start with 'Hello'")
            else ValidationResult.Valid
        }
    }
}

internal suspend inline fun <reified T> ApplicationCall.receiveValidatedJson(): T {
    val body = receiveRawValidatedBody()

    return try {
        Json.decodeFromString(body.decodeToString())
    } catch (exception: SerializationException) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "Request body is malformed or missing required fields"
        )
    }
}

@PublishedApi
internal suspend fun ApplicationCall.receiveRawValidatedBody(): ByteArray {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()

    if (contentLength != null && contentLength > MAX_REQUEST_BODY_BYTES) {
        throw AppError(
            HttpStatusCode.PayloadTooLarge,
            "PAYLOAD_TOO_LARGE",
            "Request body must not exceed 16 KB"
        )
    }

    val contentType = request.headers[HttpHeaders.ContentType]
        ?.substringBefore(';')
        ?.trim()

    if (contentType == null ||
        (!contentType.equals(ContentType.Application.Json.toString(), ignoreCase = true) &&
            !contentType.endsWith("+json", ignoreCase = true))
    ) {
        throw AppError(
            HttpStatusCode.BadRequest,
            "VALIDATION_FAILED",
            "Request body must be valid JSON"
        )
    }

    val channel = receiveChannel()
    val body = ByteArray(MAX_REQUEST_BODY_BYTES + 1)
    var bytesRead = 0

    while (bytesRead < body.size) {
        val read = channel.readAvailable(
            body,
            bytesRead,
            body.size - bytesRead
        )

        when {
            read == -1 -> break
            read == 0 -> continue
            else -> bytesRead += read
        }
    }

    if (bytesRead > MAX_REQUEST_BODY_BYTES) {
        throw AppError(
            HttpStatusCode.PayloadTooLarge,
            "PAYLOAD_TOO_LARGE",
            "Request body must not exceed 16 KB"
        )
    }

    return body.copyOf(bytesRead)
}