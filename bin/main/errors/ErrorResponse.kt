package com.nnomm.errors

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String
)