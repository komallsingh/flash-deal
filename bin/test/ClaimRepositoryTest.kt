
package com.nnomm

import com.nnomm.config.DatabaseFactory
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.Before
import org.junit.Test
import repository.ClaimRepository
import repository.IdempotencyRepository
import service.RequestHasher
import model.IdempotencyResult
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import javax.sql.DataSource
import com.zaxxer.hikari.HikariDataSource

class ClaimRepositoryTest {

    private lateinit var claimRepository: ClaimRepository

    private val customerId = UUID.randomUUID()
    private val restaurantId = UUID.randomUUID()
    private val dealId = UUID.randomUUID()

    private val idempotencyKey = "claim_test_123"

    private val concurrentCustomerIds =
        mutableListOf<UUID>()

    @Before
    fun setup() {
        concurrentCustomerIds.clear()

        DatabaseFactory.initialize()

        Flyway.configure()
            .dataSource(DatabaseFactory.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

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

            synchronized(concurrentCustomerIds) {
                concurrentCustomerIds.forEach { id ->

                    connection.prepareStatement(
                        "DELETE FROM claims WHERE customer_id = ?"
                    ).use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }

                    connection.prepareStatement(
                        "DELETE FROM idempotency_keys WHERE customer_id = ?"
                    ).use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }
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

        val acquired = IdempotencyRepository().acquire(
            customerId,
            idempotencyKey,
            RequestHasher.hash(dealId, quantity)
        )

        assertTrue(acquired is IdempotencyResult.Acquired)

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
            ).use {
                it.setObject(1, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(8, rs.getInt("stock_available"))
                }
            }

            connection.prepareStatement(
                "SELECT quantity, status FROM claims WHERE deal_id = ?"
            ).use {
                it.setObject(1, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt("quantity"))
                    assertEquals("RESERVED", rs.getString("status"))
                }
            }
        }
    }

    @Test
    fun `sold out deal should reject claim`() {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE deals SET stock_available = 0 WHERE id = ?"
            ).use {
                it.setObject(1, dealId)
                it.executeUpdate()
            }
        }

        val quantity = 1
        val key = "sold-out-key"
        val lease = acquireLease(customerId, key, quantity)

        val result = claimRepository.createClaim(
            customerId,
            dealId,
            quantity,
            key,
            lease
        )

        assertEquals(409, result.statusCode)
        assertTrue(result.body.contains("SOLD_OUT"))

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT stock_available FROM deals WHERE id = ?"
            ).use {
                it.setObject(1, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
            }

            connection.prepareStatement(
                "SELECT COUNT(*) FROM claims WHERE deal_id = ?"
            ).use {
                it.setObject(1, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `customer cannot create another active claim for same deal`() {
        val quantity = 1

        val firstKey = "first-claim-key"
        val firstLease = acquireLease(customerId, firstKey, quantity)

        val firstResult = claimRepository.createClaim(
            customerId, dealId, quantity, firstKey, firstLease
        )

        assertEquals(201, firstResult.statusCode)

        val secondKey = "second-claim-key"
        val secondLease = acquireLease(customerId, secondKey, quantity)

        val secondResult = claimRepository.createClaim(
            customerId, dealId, quantity, secondKey, secondLease
        )

        assertEquals(409, secondResult.statusCode)
        assertTrue(secondResult.body.contains("ALREADY_CLAIMED"))

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM claims
                WHERE customer_id = ?
                  AND deal_id = ?
                  AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
                """.trimIndent()
            ).use {
                it.setObject(1, customerId)
                it.setObject(2, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `200 concurrent customers cannot oversell 50 units`() {
        setDealStock(50)

        val totalCustomers = 200
        val executor = Executors.newFixedThreadPool(totalCustomers)

        val ready = CountDownLatch(totalCustomers)
        val startGate = CountDownLatch(1)

        val futures = (1..totalCustomers).map {
            executor.submit(
                Callable {
                    val customer = UUID.randomUUID()

                    synchronized(concurrentCustomerIds) {
                        concurrentCustomerIds.add(customer)
                    }

                    val key = "load-${UUID.randomUUID()}"
                    val quantity = 1

                    val lease = acquireLease(customer, key, quantity)

                    ready.countDown()

                    check(startGate.await(30, TimeUnit.SECONDS)) {
                        "Timed out waiting for concurrent start"
                    }

                    claimRepository.createClaim(
                        customer,
                        dealId,
                        quantity,
                        key,
                        lease
                    )
                }
            )
        }

        try {
            assertTrue(
                ready.await(60, TimeUnit.SECONDS),
                "Not all customers became ready"
            )

            startGate.countDown()

            val results = futures.map {
                it.get(90, TimeUnit.SECONDS)
            }

            val successful = results.count {
                it.statusCode == 201
            }

            val soldOut = results.count {
                it.statusCode == 409 &&
                    it.body.contains("SOLD_OUT")
            }

            assertEquals(50, successful)
            assertEquals(150, soldOut)
            assertEquals(totalCustomers, successful + soldOut)

            assertInventory(50, 0, 50)

        } finally {
            startGate.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun acquireLease(
        customer: UUID,
        key: String,
        quantity: Int
    ): UUID {
        val result = IdempotencyRepository().acquire(
            customer,
            key,
            RequestHasher.hash(dealId, quantity)
        )

        return when (result) {
            is IdempotencyResult.Acquired -> result.leaseToken
            else -> throw AssertionError("Expected lease acquisition, got $result")
        }
    }

    private fun setDealStock(stock: Int) {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE deals
                SET stock_total = ?, stock_available = ?
                WHERE id = ?
                """.trimIndent()
            ).use {
                it.setInt(1, stock)
                it.setInt(2, stock)
                it.setObject(3, dealId)
                it.executeUpdate()
            }
        }
    }

    private fun assertInventory(
        expectedTotal: Int,
        expectedAvailable: Int,
        expectedClaims: Int
    ) {
        DatabaseFactory.dataSource.connection.use { connection ->

            connection.prepareStatement(
                "SELECT stock_total, stock_available FROM deals WHERE id = ?"
            ).use {
                it.setObject(1, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(expectedTotal, rs.getInt("stock_total"))
                    assertEquals(expectedAvailable, rs.getInt("stock_available"))
                }
            }

            connection.prepareStatement(
                """
                SELECT COALESCE(SUM(quantity), 0)
                FROM claims
                WHERE deal_id = ?
                  AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
                """.trimIndent()
            ).use {
                it.setObject(1, dealId)

                it.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(expectedClaims, rs.getInt(1))
                }
            }
        }
    }
    
@Test
fun `two service instances cannot oversell 50 units`() {
    setDealStock(50)

    // Create two independent connection pools.
    val poolA = DatabaseFactory.createDataSource("ServiceInstanceA")
    val poolB = DatabaseFactory.createDataSource("ServiceInstanceB")

    // Each simulated service has its own repositories.
    val repositoryA = ClaimRepository(poolA)
    val repositoryB = ClaimRepository(poolB)

    val idempotencyA = IdempotencyRepository(poolA)
    val idempotencyB = IdempotencyRepository(poolB)

    val totalCustomers = 200
    val executor = Executors.newFixedThreadPool(totalCustomers)

    val ready = CountDownLatch(totalCustomers)
    val startGate = CountDownLatch(1)

    try {
        val futures = (0 until totalCustomers).map { index ->
            executor.submit(
                Callable {
                    // Distribute requests between the two instances.
                    val repository: ClaimRepository
                    val idempotencyRepository: IdempotencyRepository

                    if (index % 2 == 0) {
                        repository = repositoryA
                        idempotencyRepository = idempotencyA
                    } else {
                        repository = repositoryB
                        idempotencyRepository = idempotencyB
                    }

                    val customer = UUID.randomUUID()

                    synchronized(concurrentCustomerIds) {
                        concurrentCustomerIds.add(customer)
                    }

                    val quantity = 1
                    val key = "two-instance-${UUID.randomUUID()}"

                    val acquired = idempotencyRepository.acquire(
                        customer,
                        key,
                        RequestHasher.hash(dealId, quantity)
                    )

                    val lease = when (acquired) {
                        is IdempotencyResult.Acquired -> acquired.leaseToken
                        else -> throw AssertionError(
                            "Expected lease acquisition, got $acquired"
                        )
                    }

                    ready.countDown()

                    check(startGate.await(30, TimeUnit.SECONDS)) {
                        "Timed out waiting for concurrent start"
                    }

                    repository.createClaim(
                        customer,
                        dealId,
                        quantity,
                        key,
                        lease
                    )
                }
            )
        }

        assertTrue(
            ready.await(60, TimeUnit.SECONDS),
            "Not all customers became ready"
        )

        // Start both service instances' requests together.
        startGate.countDown()

        val results = futures.map {
            it.get(90, TimeUnit.SECONDS)
        }

        val successful = results.count {
            it.statusCode == 201
        }

        val soldOut = results.count {
            it.statusCode == 409 &&
                it.body.contains("SOLD_OUT")
        }

        assertEquals(50, successful)
        assertEquals(150, soldOut)
        assertEquals(totalCustomers, successful + soldOut)

        // Verify the shared database, not either connection pool.
        assertInventory(
            expectedTotal = 50,
            expectedAvailable = 0,
            expectedClaims = 50
        )

    } finally {
        startGate.countDown()
        executor.shutdownNow()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        poolA.close()
        poolB.close()
    }
}

}
