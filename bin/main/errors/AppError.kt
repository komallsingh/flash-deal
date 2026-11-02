package com.nnomm.errors

import io.ktor.http.HttpStatusCode

class AppError(
    val status: HttpStatusCode,
    val code: String,
    override val message: String
) : RuntimeException(message)