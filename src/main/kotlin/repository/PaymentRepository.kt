
package repository

import com.nnomm.config.DatabaseFactory
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class PaymentRepository(
    private val dataSource: DataSource = DatabaseFactory.dataSource
) {

    /**
     * Records a signed payment event and applies its effect, if any.
     *
     * Event insertion and claim updates share one transaction.
     * A duplicate event_id is acknowledged without changing claim state.
     */
    fun processEvent(event: model.PaymentWebhookRequest) {
        val connection = dataSource.connection

        try {
            connection.autoCommit = false

            // 1. Record the event.
            // The primary key on event_id makes this safe across instances.
            val insertEventSql = """
                INSERT INTO payment_events (
                    event_id,
                    claim_id,
                    event_type
                )
                VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
            """.trimIndent()

            val inserted = connection.prepareStatement(insertEventSql).use { statement ->
                statement.setString(1, event.event_id)
                statement.setObject(2, UUID.fromString(event.claim_id))
                statement.setString(3, event.type)
                statement.executeUpdate()
            }

            // A previously processed event must never be applied twice.
            if (inserted == 0) {
                connection.commit()
                return
            }

            val claimId = UUID.fromString(event.claim_id)

            // 2. Find the deal ID. This is only a lookup, not a row lock.
            val dealId = findDealId(connection, claimId)

            // Unknown claim IDs are recorded and acknowledged.
            // This avoids endless provider retries for an unknown reference.
            if (dealId == null) {
                connection.commit()
                return
            }

            // 3. Lock the deal first, matching ClaimRepository's expiry worker.
            lockDeal(connection, dealId)

            // 4. Then lock the claim.
            val claim = lockClaim(connection, claimId)

            // Recheck after locking. The claim may no longer be present.
            if (claim == null) {
                connection.commit()
                return
            }

            // 5. Use the database clock only after acquiring both locks.
            val now = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT clock_timestamp()").use { result ->
                    result.next()
                    result.getTimestamp(1).toInstant()
                }
            }

            when (event.type) {
                "payment.failed" -> {
                    // Failed payments do not change claim state.
                    // The customer may retry while the reservation is live.
                }

                "payment.succeeded" -> {
                    when (claim.status) {
                        "RESERVED" -> {
                            if (!now.isBefore(claim.expiresAt)) {
                                // Reservation has expired. Return stock exactly once.
                                updateClaimStatus(
                                    connection,
                                    claimId,
                                    "REFUND_PENDING"
                                )
                                restoreStock(connection, dealId, claim.quantity)
                            } else if (event.amount_paise == claim.amountPaise) {
                                updateClaimStatus(
                                    connection,
                                    claimId,
                                    "PAID"
                                )
                            } else {
                                // Keep the units held until this discrepancy is reviewed.
                                updateClaimStatus(
                                    connection,
                                    claimId,
                                    "NEEDS_REVIEW"
                                )
                            }
                        }

                        "EXPIRED" -> {
                            // The expiry worker already restored the stock.
                            // Do not restore it a second time.
                            updateClaimStatus(
                                connection,
                                claimId,
                                "REFUND_PENDING"
                            )
                        }

                        "PAID", "NEEDS_REVIEW", "REFUND_PENDING" -> {
                            // Later events do not reverse an established outcome.
                        }
                    }
                }
            }

            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.close()
        }
    }

    private data class LockedClaim(
        val status: String,
        val quantity: Int,
        val amountPaise: Int,
        val expiresAt: java.time.Instant
    )

    private fun findDealId(
        connection: Connection,
        claimId: UUID
    ): UUID? {
        val sql = """
            SELECT deal_id
            FROM claims
            WHERE id = ?
        """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, claimId)

            statement.executeQuery().use { result ->
                if (result.next()) {
                    result.getObject("deal_id", UUID::class.java)
                } else {
                    null
                }
            }
        }
    }

    private fun lockDeal(
        connection: Connection,
        dealId: UUID
    ) {
        val sql = """
            SELECT id
            FROM deals
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()

        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, dealId)

            statement.executeQuery().use { result ->
                check(result.next()) {
                    "Deal disappeared while processing payment"
                }
            }
        }
    }

    private fun lockClaim(
        connection: Connection,
        claimId: UUID
    ): LockedClaim? {
        val sql = """
            SELECT status, quantity, amount_paise, expires_at
            FROM claims
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, claimId)

            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    LockedClaim(
                        status = result.getString("status"),
                        quantity = result.getInt("quantity"),
                        amountPaise = result.getInt("amount_paise"),
                        expiresAt = result.getTimestamp("expires_at").toInstant()
                    )
                }
            }
        }
    }

    private fun updateClaimStatus(
        connection: Connection,
        claimId: UUID,
        status: String
    ) {
        val sql = """
            UPDATE claims
            SET status = ?,
                updated_at = clock_timestamp()
            WHERE id = ?
        """.trimIndent()

        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, status)
            statement.setObject(2, claimId)
            statement.executeUpdate()
        }
    }

    private fun restoreStock(
        connection: Connection,
        dealId: UUID,
        quantity: Int
    ) {
        val sql = """
            UPDATE deals
            SET stock_available = stock_available + ?
            WHERE id = ?
        """.trimIndent()

        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, quantity)
            statement.setObject(2, dealId)

            check(statement.executeUpdate() == 1) {
                "Could not restore deal stock"
            }
        }
    }
}
