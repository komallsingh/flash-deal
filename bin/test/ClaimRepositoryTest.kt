package com.nnomm

import com.nnomm.config.DatabaseFactory
import model.ClaimCreationResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import repository.ClaimRepository
import repository.IdempotencyRepository
import service.RequestHasher
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ClaimRepositoryTest {

    private lateinit var claimRepository: ClaimRepository

    private val customerId = UUID.randomUUID()
    private val restaurantId = UUID.randomUUID()
    private val dealId = UUID.randomUUID()

    private val idempotencyKey = "claim_test_123"

    @Before
    fun setup() {
        DatabaseFactory.initialize()

        claimRepository = ClaimRepository()

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO deals (
                    id,
                    restaurant_id,
                    title,
                    price_paise,
                    stock_total,
                    stock_available,
                    starts_at,
                    ends_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->

                statement.setObject(1, dealId)
                statement.setObject(2, restaurantId)
                statement.setString(3, "Test Deal")
                statement.setInt(4, 50000)
                statement.setInt(5, 10)
                statement.setInt(6, 10)
                statement.setTimestamp(
                    7,
                    Timestamp.from(Instant.now().minusSeconds(60))
                )
                statement.setTimestamp(
                    8,
                    Timestamp.from(Instant.now().plusSeconds(3600))
                )

                statement.executeUpdate()
            }
        }
    }

    @After
    fun cleanup() {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM claims WHERE deal_id = ?"
            ).use {
                it.setObject(1, dealId)
                it.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM idempotency_keys WHERE customer_id = ?"
            ).use {
                it.setObject(1, customerId)
                it.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM deals WHERE id = ?"
            ).use {
                it.setObject(1, dealId)
                it.executeUpdate()
            }
        }

        DatabaseFactory.close()
    }

    @Test
    fun `customer can successfully claim a live deal`() {

        val quantity = 2

        val hash = RequestHasher.hash(dealId, quantity)

        val acquired = IdempotencyRepository().acquire(
            customerId,
            idempotencyKey,
            hash
        )

        assertTrue(acquired is model.IdempotencyResult.Acquired)

        val result = claimRepository.createClaim(
            customerId,
            dealId,
            quantity,
            idempotencyKey,
            acquired.leaseToken
        )

        assertEquals(201, result.statusCode)
        assertTrue(result.body.contains("RESERVED"))

        DatabaseFactory.dataSource.connection.use { connection ->

            connection.prepareStatement(
                "SELECT stock_available FROM deals WHERE id = ?"
            ).use { statement ->

                statement.setObject(1, dealId)

                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(8, rows.getInt("stock_available"))
                }
            }

            connection.prepareStatement(
                "SELECT quantity, status FROM claims WHERE deal_id = ?"
            ).use { statement ->

                statement.setObject(1, dealId)

                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt("quantity"))
                    assertEquals("RESERVED", rows.getString("status"))
                }
            }
        }
    }
}