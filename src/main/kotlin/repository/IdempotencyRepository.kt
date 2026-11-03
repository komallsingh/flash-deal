package repository

import com.nnomm.config.DatabaseFactory
import model.IdempotencyResult
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class IdempotencyRepository(
    private val dataSource: DataSource = DatabaseFactory.dataSource
) {

    fun acquire(
        customerId: UUID,
        idempotencyKey: String,
        requestHash: String
    ): IdempotencyResult {

        val connection = dataSource.connection

        try {
            connection.autoCommit = false

            // Step 1: Try to create a new idempotency record.
            val insertSql = """
                INSERT INTO idempotency_keys (
                    customer_id,
                    idempotency_key,
                    request_hash
                )
                VALUES (?, ?, ?)
                ON CONFLICT (customer_id, idempotency_key)
                DO NOTHING
                RETURNING lease_token
            """.trimIndent()

            connection.prepareStatement(insertSql).use { statement ->
                statement.setObject(1, customerId)
                statement.setString(2, idempotencyKey)
                statement.setString(3, requestHash)

                statement.executeQuery().use { result ->
                    if (result.next()) {
                        val token = result.getObject(
                            "lease_token",
                            UUID::class.java
                        )

                        connection.commit()

                        return IdempotencyResult.Acquired(token)
                    }
                }
            }

            // Step 2: The key already exists.
            // Lock its row while inspecting or updating it.
            val selectSql = """
                SELECT
                    request_hash,
                    response_status,
                    response_body::text AS response_body,
                    completed_at,
                    lease_expires_at,
                    (
                        completed_at IS NOT NULL
                        AND completed_at > CURRENT_TIMESTAMP - INTERVAL '24 hours'
                    ) AS replay_valid,
                    (
                        lease_expires_at <= CURRENT_TIMESTAMP
                    ) AS lease_expired
                FROM idempotency_keys
                WHERE customer_id = ?
                  AND idempotency_key = ?
                FOR UPDATE
            """.trimIndent()

            var existingHash: String? = null
            var responseStatus: Int? = null
            var responseBody: String? = null
            var completed: Boolean = false
            var replayValid: Boolean = false
            var leaseExpired: Boolean = false

            connection.prepareStatement(selectSql).use { statement ->
                statement.setObject(1, customerId)
                statement.setString(2, idempotencyKey)

                statement.executeQuery().use { result ->
                    if (result.next()) {
                        existingHash = result.getString("request_hash")
                        responseStatus = result.getObject("response_status") as? Int
                        responseBody = result.getString("response_body")
                        completed = result.getTimestamp("completed_at") != null
                        replayValid = result.getBoolean("replay_valid")
                        leaseExpired = result.getBoolean("lease_expired")
                    }
                }
            }

            // Step 3: Same key, different request payload.
            if (existingHash != requestHash) {
                connection.commit()
                return IdempotencyResult.KeyReused
            }

            // Step 4: The request was already completed within 24 hours.
            if (completed && replayValid) {
                val status = responseStatus
                    ?: throw IllegalStateException("Completed request has no status")

                val body = responseBody
                    ?: throw IllegalStateException("Completed request has no response body")

                connection.commit()

                return IdempotencyResult.Replay(status, body)
            }

            // Step 5: Another request is still processing this key.
            if (!completed && !leaseExpired) {
                connection.commit()
                return IdempotencyResult.InProgress
            }

            // Step 6: The lease expired, or the replay window ended.
            // Allow this key to be acquired again.
            val newToken = UUID.randomUUID()

            val updateSql = """
                UPDATE idempotency_keys
                SET
                    lease_token = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
                    response_status = NULL,
                    response_body = NULL,
                    completed_at = NULL,
                    created_at = CURRENT_TIMESTAMP
                WHERE customer_id = ?
                  AND idempotency_key = ?
            """.trimIndent()

            connection.prepareStatement(updateSql).use { statement ->
                statement.setObject(1, newToken)
                statement.setObject(2, customerId)
                statement.setString(3, idempotencyKey)

                statement.executeUpdate()
            }

            connection.commit()

            return IdempotencyResult.Acquired(newToken)

        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.close()
        }
    }
    fun complete(
    connection: Connection,
    customerId: UUID,
    idempotencyKey: String,
    leaseToken: UUID,
    responseStatus: Int,
    responseBody: String
) {
    val sql = """
        UPDATE idempotency_keys
        SET
            response_status = ?,
            response_body = ?::jsonb,
            completed_at = CURRENT_TIMESTAMP
        WHERE customer_id = ?
          AND idempotency_key = ?
          AND lease_token = ?
          AND completed_at IS NULL
    """.trimIndent()

    connection.prepareStatement(sql).use { statement ->
        statement.setInt(1, responseStatus)
        statement.setString(2, responseBody)
        statement.setObject(3, customerId)
        statement.setString(4, idempotencyKey)
        statement.setObject(5, leaseToken)

        val updatedRows = statement.executeUpdate()

        check(updatedRows == 1) {
            "Idempotency completion failed: lease ownership lost or record already completed"
        }
    }
}
fun verifyLease(
    connection: Connection,
    customerId: UUID,
    idempotencyKey: String,
    leaseToken: UUID
): Boolean {

    val sql = """
        SELECT lease_token
        FROM idempotency_keys
        WHERE customer_id = ?
          AND idempotency_key = ?
          AND lease_token = ?
          AND completed_at IS NULL
          AND lease_expires_at > CURRENT_TIMESTAMP
        FOR UPDATE
    """.trimIndent()

    connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, customerId)
        statement.setString(2, idempotencyKey)
        statement.setObject(3, leaseToken)

        statement.executeQuery().use { result ->
            return result.next()
        }
    }
}
}