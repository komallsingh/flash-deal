
package com.nnomm

import com.nnomm.config.DatabaseFactory
import model.IdempotencyResult
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.Before
import org.junit.Test
import repository.ClaimRepository
import repository.IdempotencyRepository
import service.RequestHasher
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwoServiceInstanceConcurrencyTest {

    private lateinit var poolA: com.zaxxer.hikari.HikariDataSource
    private lateinit var poolB: com.zaxxer.hikari.HikariDataSource

    private lateinit var claimRepositoryA: ClaimRepository
    private lateinit var claimRepositoryB: ClaimRepository

    private lateinit var idempotencyRepositoryA: IdempotencyRepository
    private lateinit var idempotencyRepositoryB: IdempotencyRepository

    private val customerIds = mutableListOf<UUID>()

    private val restaurantId = UUID.randomUUID()
    private val dealId = UUID.randomUUID()

    @Before
    fun setup() {
        DatabaseFactory.initialize()

        Flyway.configure()
            .dataSource(DatabaseFactory.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        poolA = DatabaseFactory.createDataSource("ServiceInstanceA")
        poolB = DatabaseFactory.createDataSource("ServiceInstanceB")

        idempotencyRepositoryA = IdempotencyRepository(poolA)
        idempotencyRepositoryB = IdempotencyRepository(poolB)

        claimRepositoryA = ClaimRepository(
            poolA,
            idempotencyRepositoryA
        )

        claimRepositoryB = ClaimRepository(
            poolB,
            idempotencyRepositoryB
        )

        DatabaseFactory.dataSource.connection.use { connection ->
            val sql = """
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
                VALUES (
                    ?, ?, 'Two Instance Test',
                    10000, 50, 50,
                    CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    CURRENT_TIMESTAMP + INTERVAL '1 hour'
                )
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, dealId)
                statement.setObject(2, restaurantId)
                statement.executeUpdate()
            }
        }
    }

    @After
    fun cleanup() {
        try {
            if (::poolA.isInitialized) poolA.close()
            if (::poolB.isInitialized) poolB.close()

            DatabaseFactory.dataSource.connection.use { connection ->
                connection.autoCommit = false

                try {
                    connection.prepareStatement(
                        "DELETE FROM claims WHERE deal_id = ?"
                    ).use {
                        it.setObject(1, dealId)
                        it.executeUpdate()
                    }

                    connection.prepareStatement(
                        "DELETE FROM idempotency_keys WHERE customer_id = ANY (?)"
                    ).use { statement ->
                        val array = connection.createArrayOf(
                            "uuid",
                            customerIds.toTypedArray()
                        )
                        statement.setArray(1, array)
                        statement.executeUpdate()
                    }

                    connection.prepareStatement(
                        "DELETE FROM deals WHERE id = ?"
                    ).use {
                        it.setObject(1, dealId)
                        it.executeUpdate()
                    }

                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                }
            }
        } finally {
            DatabaseFactory.close()
        }
    }

    @Test
    fun `two service instances cannot oversell shared deal`() {
        val executor = Executors.newFixedThreadPool(200)

        val readyGate = CountDownLatch(200)
        val startGate = CountDownLatch(1)

        try {
            val futures = (0 until 200).map { index ->
                executor.submit<Boolean> {
                    val customerId = UUID.randomUUID()

                    synchronized(customerIds) {
                        customerIds.add(customerId)
                    }

                    val key = "two-instance-key-$index"
                    val quantity = 1
                    val requestHash = RequestHasher.hash(dealId, quantity)

                    val useInstanceA = index % 2 == 0

                    val idempotencyRepository =
                        if (useInstanceA) idempotencyRepositoryA
                        else idempotencyRepositoryB

                    val claimRepository =
                        if (useInstanceA) claimRepositoryA
                        else claimRepositoryB

                    val acquisition = idempotencyRepository.acquire(
                        customerId,
                        key,
                        requestHash
                    )

                    val leaseToken = when (acquisition) {
                        is IdempotencyResult.Acquired -> acquisition.leaseToken
                        else -> error("Expected a new idempotency key")
                    }

                    readyGate.countDown()
                    check(startGate.await(30, TimeUnit.SECONDS))

                    val result = claimRepository.createClaim(
                        customerId,
                        dealId,
                        quantity,
                        key,
                        leaseToken
                    )

                    result.statusCode == 201
                }
            }

            assertTrue(
                readyGate.await(60, TimeUnit.SECONDS),
                "Not all requests reached the start gate"
            )

            startGate.countDown()

            val results = futures.map {
                it.get(120, TimeUnit.SECONDS)
            }

            val successfulClaims = results.count { it }
            val rejectedClaims = results.count { !it }

            assertEquals(50, successfulClaims)
            assertEquals(150, rejectedClaims)

            assertInventory()
        } finally {
            startGate.countDown()
            executor.shutdownNow()
            executor.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    private fun assertInventory() {
        DatabaseFactory.dataSource.connection.use { connection ->
            val sql = """
                SELECT
                    d.stock_total,
                    d.stock_available,
                    COALESCE(
                        SUM(c.quantity) FILTER (
                            WHERE c.status IN (
                                'RESERVED',
                                'PAID',
                                'NEEDS_REVIEW'
                            )
                        ),
                        0
                    ) AS held_units
                FROM deals d
                LEFT JOIN claims c ON c.deal_id = d.id
                WHERE d.id = ?
                GROUP BY d.id
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, dealId)

                statement.executeQuery().use { result ->
                    assertTrue(result.next())

                    assertEquals(50, result.getInt("stock_total"))
                    assertEquals(0, result.getInt("stock_available"))
                    assertEquals(50, result.getInt("held_units"))

                    assertTrue(result.getInt("stock_available") >= 0)
                }
            }
        }
    }
}
