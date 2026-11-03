package com.nnomm.security

import java.util.UUID

data class AuthenticatedUser(
    val userId: UUID,
    val role: String,
    val restaurantId: UUID?
)