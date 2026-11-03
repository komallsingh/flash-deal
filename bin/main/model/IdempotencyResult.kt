package model

import java.util.UUID

sealed class IdempotencyResult {

    data class Acquired(
        val leaseToken: UUID
    ) : IdempotencyResult()

    data class Replay(
        val status: Int,
        val body: String
    ) : IdempotencyResult()

    data object InProgress : IdempotencyResult()

    data object KeyReused : IdempotencyResult()
}