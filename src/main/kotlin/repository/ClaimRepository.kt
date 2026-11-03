package repository

import com.nnomm.config.DatabaseFactory
import model.ClaimCreationResult
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import java.time.Instant

class ClaimRepository(
    private val dataSource: DataSource = DatabaseFactory.dataSource,
    private val idempotencyRepository: IdempotencyRepository =
        IdempotencyRepository(dataSource)
) {

    fun createClaim(
        customerId: UUID,
        dealId: UUID,
        quantity: Int,
        idempotencyKey: String,
        leaseToken: UUID
    ): ClaimCreationResult {

        require(quantity in 1..3) {
            "Quantity must be between 1 and 3"
        }

        val connection = dataSource.connection

        try {
            connection.autoCommit = false

            // 1. Verify and lock the idempotency record.
            val ownsLease = idempotencyRepository.verifyLease(
                connection,
                customerId,
                idempotencyKey,
                leaseToken
            )

            check(ownsLease) {
                "Idempotency lease is no longer valid"
            }

            // 2. Lock the deal row.
            // All claim requests for this deal serialize on this row.
            // 2. Lock the deal row first.
val dealSql = """
    SELECT price_paise, starts_at, ends_at
    FROM deals
    WHERE id = ?
    FOR UPDATE
""".trimIndent()

var pricePaise: Int? = null
var startsAt: Instant? = null
var endsAt: Instant? = null

connection.prepareStatement(dealSql).use { statement ->
    statement.setObject(1, dealId)

    statement.executeQuery().use { result ->
        if (result.next()) {
            pricePaise = result.getInt("price_paise")
            startsAt = result.getTimestamp("starts_at").toInstant()
            endsAt = result.getTimestamp("ends_at").toInstant()
        }
    }
}

val price = pricePaise
    ?: throw NoSuchElementException("Deal not found")

// Check deal timing after obtaining the row lock.
val now = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT clock_timestamp()").use { result ->
        result.next()
        result.getTimestamp(1).toInstant()
    }
}

val isDealLive = !now.isBefore(startsAt) && now.isBefore(endsAt)

            // 3. Expire old reservations for this deal.
            // This also frees their stock before checking availability.
            val expiredQuantity = expireOldReservations(connection, dealId)

            if (expiredQuantity > 0) {
                val restoreSql = """
                    UPDATE deals
                    SET stock_available = stock_available + ?
                    WHERE id = ?
                """.trimIndent()

                connection.prepareStatement(restoreSql).use { statement ->
                    statement.setInt(1, expiredQuantity)
                    statement.setObject(2, dealId)
                    statement.executeUpdate()
                }
            }

            // 4. Check whether the deal is currently live.
            if (!isDealLive) {
                return saveOutcome(
                    connection,
                    customerId,
                    idempotencyKey,
                    leaseToken,
                    409,
                    errorBody("DEAL_NOT_LIVE", "Deal is not currently live")
                )
            }

            // 5. Check whether this customer already has an active claim.
            val activeClaimSql = """
                SELECT 1
                FROM claims
                WHERE customer_id = ?
                  AND deal_id = ?
                  AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
                LIMIT 1
            """.trimIndent()

            val alreadyClaimed = connection.prepareStatement(activeClaimSql).use { statement ->
                statement.setObject(1, customerId)
                statement.setObject(2, dealId)

                statement.executeQuery().use { result ->
                    result.next()
                }
            }

            if (alreadyClaimed) {
                return saveOutcome(
                    connection,
                    customerId,
                    idempotencyKey,
                    leaseToken,
                    409,
                    errorBody(
                        "ALREADY_CLAIMED",
                        "Customer already has an active claim for this deal"
                    )
                )
            }

            // 6. Deduct stock atomically.
            val stockSql = """
                UPDATE deals
                SET stock_available = stock_available - ?
                WHERE id = ?
                  AND stock_available >= ?
            """.trimIndent()

            val updatedRows = connection.prepareStatement(stockSql).use { statement ->
                statement.setInt(1, quantity)
                statement.setObject(2, dealId)
                statement.setInt(3, quantity)
                statement.executeUpdate()
            }

            if (updatedRows == 0) {
                return saveOutcome(
                    connection,
                    customerId,
                    idempotencyKey,
                    leaseToken,
                    409,
                    errorBody("SOLD_OUT", "Not enough stock available")
                )
            }

            // 7. Insert the claim.
            val amountPaise = price * quantity
            val ttlSeconds = (
                System.getenv("CLAIM_TTL_SECONDS") ?: "600"
            ).toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException("Invalid CLAIM_TTL_SECONDS")

            val insertClaimSql = """
                INSERT INTO claims (
                    deal_id,
                    customer_id,
                    quantity,
                    amount_paise,
                    status,
                    expires_at
                )
                VALUES (
                    ?, ?, ?, ?, 'RESERVED',
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                )
                RETURNING id, expires_at
            """.trimIndent()

            val claimId: UUID
            val expiresAt: String

            connection.prepareStatement(insertClaimSql).use { statement ->
                statement.setObject(1, dealId)
                statement.setObject(2, customerId)
                statement.setInt(3, quantity)
                statement.setInt(4, amountPaise)
                statement.setDouble(5, ttlSeconds.toDouble())

                statement.executeQuery().use { result ->
                    check(result.next()) {
                        "Claim insertion did not return a record"
                    }

                    claimId = result.getObject("id", UUID::class.java)
                    expiresAt = result.getTimestamp("expires_at")
                        .toInstant()
                        .toString()
                }
            }

            // 8. Save the successful response in the same transaction.
            val responseBody = """
                {
                    "claim_id": "$claimId",
                    "deal_id": "$dealId",
                    "quantity": $quantity,
                    "amount_paise": $amountPaise,
                    "status": "RESERVED",
                    "expires_at": "$expiresAt"
                }
            """.trimIndent()

            idempotencyRepository.complete(
                connection,
                customerId,
                idempotencyKey,
                leaseToken,
                201,
                responseBody
            )

            connection.commit()

            return ClaimCreationResult(201, responseBody)

        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.close()
        }
    }
    fun getClaim(
    claimId: UUID,
    userId: UUID,
    role: String,
    restaurantId: UUID?
): model.ClaimDetails? {

    val sql = """
        SELECT
            c.id,
            c.deal_id,
            c.customer_id,
            c.quantity,
            c.amount_paise,
            CASE
    WHEN c.status = 'RESERVED'
         AND c.expires_at <= clock_timestamp()
    THEN 'EXPIRED'
    ELSE c.status
END AS status,
            c.expires_at,
            c.created_at
        FROM claims c
        JOIN deals d ON c.deal_id = d.id
        WHERE c.id = ?
          AND (
              ( ? = 'customer' AND c.customer_id = ? )
              OR
              ( ? = 'restaurant_admin' AND d.restaurant_id = ? )
          )
    """.trimIndent()

    dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->

            statement.setObject(1, claimId)
            statement.setString(2, role)
            statement.setObject(3, userId)
            statement.setString(4, role)
            statement.setObject(5, restaurantId)

            statement.executeQuery().use { result ->

                if (!result.next()) {
                    return null
                }

                return model.ClaimDetails(
                    id = result.getObject("id", UUID::class.java).toString(),
                    deal_id = result.getObject("deal_id", UUID::class.java).toString(),
                    customer_id = result.getObject("customer_id", UUID::class.java).toString(),
                    quantity = result.getInt("quantity"),
                    amount_paise = result.getInt("amount_paise"),
                    status = result.getString("status"),
                    expires_at = result.getTimestamp("expires_at").toInstant().toString(),
                    created_at = result.getTimestamp("created_at").toInstant().toString()
                )
            }
        }
    }
}
fun expireReservations(): Int {
    var totalExpired = 0

    dataSource.connection.use { connection ->
        try {
            connection.autoCommit = false

            // Find deals that have expired reservations.
            val dealIds = mutableListOf<UUID>()

            val findDealsSql = """
                SELECT DISTINCT deal_id
                FROM claims
                WHERE status = 'RESERVED'
                  AND expires_at <= clock_timestamp()
                LIMIT 100
            """.trimIndent()

            connection.prepareStatement(findDealsSql).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        dealIds.add(result.getObject("deal_id", UUID::class.java))
                    }
                }
            }

            // Always lock the deal before its claims.
            // This follows the same lock order as createClaim().
            for (dealId in dealIds) {

                val lockDealSql = """
                    SELECT id
                    FROM deals
                    WHERE id = ?
                    FOR UPDATE
                """.trimIndent()

                val dealExists = connection.prepareStatement(lockDealSql).use { statement ->
                    statement.setObject(1, dealId)
                    statement.executeQuery().use { result ->
                        result.next()
                    }
                }

                if (!dealExists) continue

                val expiredQuantity = expireOldReservations(connection, dealId)

                if (expiredQuantity > 0) {
                    val restoreSql = """
                        UPDATE deals
                        SET stock_available = stock_available + ?
                        WHERE id = ?
                    """.trimIndent()

                    connection.prepareStatement(restoreSql).use { statement ->
                        statement.setInt(1, expiredQuantity)
                        statement.setObject(2, dealId)
                        statement.executeUpdate()
                    }

                    totalExpired += expiredQuantity
                }
            }

            connection.commit()
            return totalExpired

        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }
}

    private fun expireOldReservations(
        connection: Connection,
        dealId: UUID
    ): Int {

        val sql = """
            UPDATE claims
            SET
                status = 'EXPIRED',
                updated_at = clock_timestamp()
            WHERE deal_id = ?
              AND status = 'RESERVED'
              AND expires_at <= clock_timestamp()
            RETURNING quantity
        """.trimIndent()

        var totalQuantity = 0

        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, dealId)

            statement.executeQuery().use { result ->
                while (result.next()) {
                    totalQuantity += result.getInt("quantity")
                }
            }
        }

        return totalQuantity
    }

    private fun saveOutcome(
        connection: Connection,
        customerId: UUID,
        idempotencyKey: String,
        leaseToken: UUID,
        statusCode: Int,
        responseBody: String
    ): ClaimCreationResult {

        idempotencyRepository.complete(
            connection,
            customerId,
            idempotencyKey,
            leaseToken,
            statusCode,
            responseBody
        )

        connection.commit()

        return ClaimCreationResult(statusCode, responseBody)
    }

    private fun errorBody(
        code: String,
        message: String
    ): String {
        return """
            {
                "error": {
                    "code": "$code",
                    "message": "$message"
                }
            }
        """.trimIndent()
    }
}