package com.nnomm

import com.nnomm.config.DatabaseFactory
import model.IdempotencyResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import repository.IdempotencyRepository
import service.RequestHasher
import java.util.UUID
import kotlin.test.*
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdempotencyRepositoryTest {

    private lateinit var repository: IdempotencyRepository

    private val customerId = UUID.randomUUID()
    private val dealId = UUID.randomUUID()
    private val idempotencyKey = "test_key_123"

    @Before
    fun setup() {
        DatabaseFactory.initialize()
        repository = IdempotencyRepository()
    }

    @After
    fun cleanup() {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM idempotency_keys
                WHERE customer_id = ?
                """.trimIndent()
            ).use {
                it.setObject(1, customerId)
                it.executeUpdate()
            }
        }

        DatabaseFactory.close()
    }

    @Test
    fun `new key can be acquired and completed request can be replayed`() {
        val requestHash = RequestHasher.hash(dealId, 2)

        val acquired = repository.acquire(
            customerId,
            idempotencyKey,
            requestHash
        )

        assertTrue(acquired is IdempotencyResult.Acquired)

        val token = acquired.leaseToken

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.autoCommit = false

            assertTrue(
                repository.verifyLease(
                    connection,
                    customerId,
                    idempotencyKey,
                    token
                )
            )

            repository.complete(
                connection,
                customerId,
                idempotencyKey,
                token,
                201,
                """{"claim_id":"test-claim","status":"RESERVED"}"""
            )

            connection.commit()
        }

        val replay = repository.acquire(
            customerId,
            idempotencyKey,
            requestHash
        )

        assertTrue(replay is IdempotencyResult.Replay)
        assertEquals(201, replay.status)
        assertTrue(replay.body.contains("test-claim"))
    }

    @Test
    fun `same key with different request hash is rejected`() {
        val firstHash = RequestHasher.hash(dealId, 1)

        repository.acquire(
            customerId,
            idempotencyKey,
            firstHash
        )

        val secondHash = RequestHasher.hash(dealId, 2)

        val result = repository.acquire(
            customerId,
            idempotencyKey,
            secondHash
        )

        assertEquals(IdempotencyResult.KeyReused, result)
    }
    @Test
fun `only one concurrent request acquires the same idempotency key`() {
    val threadCount = 20
    val requestHash = RequestHasher.hash(dealId, 2)

    val executor = Executors.newFixedThreadPool(threadCount)
    val barrier = CyclicBarrier(threadCount)

    try {
        val futures = (1..threadCount).map {
            executor.submit<IdempotencyResult> {
                barrier.await()

                repository.acquire(
                    customerId,
                    idempotencyKey,
                    requestHash
                )
            }
        }

        val results = futures.map {
            it.get(30, TimeUnit.SECONDS)
        }

        val acquiredCount = results.count {
            it is IdempotencyResult.Acquired
        }

        val inProgressCount = results.count {
            it is IdempotencyResult.InProgress
        }

        assertEquals(1, acquiredCount)
        assertEquals(19, inProgressCount)

    } finally {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
@Test
fun `expired lease can be acquired again with a new token`() {
    val requestHash = RequestHasher.hash(dealId, 2)

    val first = repository.acquire(
        customerId,
        idempotencyKey,
        requestHash
    )

    assertTrue(first is IdempotencyResult.Acquired)

    val oldToken = first.leaseToken

    // Simulate the original request crashing and its lease expiring.
    DatabaseFactory.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            UPDATE idempotency_keys
            SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
            WHERE customer_id = ?
              AND idempotency_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, customerId)
            statement.setString(2, idempotencyKey)
            assertEquals(1, statement.executeUpdate())
        }
    }

    val second = repository.acquire(
        customerId,
        idempotencyKey,
        requestHash
    )

    assertTrue(second is IdempotencyResult.Acquired)

    val newToken = second.leaseToken

    assertNotEquals(oldToken, newToken)

    DatabaseFactory.dataSource.connection.use { connection ->
        connection.autoCommit = false

        assertFalse(
            repository.verifyLease(
                connection,
                customerId,
                idempotencyKey,
                oldToken
            )
        )

        assertTrue(
            repository.verifyLease(
                connection,
                customerId,
                idempotencyKey,
                newToken
            )
        )

        connection.rollback()
    }
}
}