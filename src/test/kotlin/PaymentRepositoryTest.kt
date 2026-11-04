
package com.nnomm

import com.nnomm.config.DatabaseFactory
import model.PaymentWebhookRequest
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.Before
import org.junit.Test
import repository.ClaimRepository
import repository.PaymentRepository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentRepositoryTest {

    private lateinit var paymentRepository: PaymentRepository

    private val dealId = UUID.randomUUID()
    private val restaurantId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private val eventIds = mutableListOf<String>()
    private val claimIds = mutableListOf<UUID>()

    @Before
    fun setup() {
        eventIds.clear()
        claimIds.clear()

        DatabaseFactory.initialize()

        Flyway.configure()
            .dataSource(DatabaseFactory.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        paymentRepository = PaymentRepository()

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
                statement.setString(3, "Payment Test Deal")
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

            eventIds.forEach { eventId ->
                connection.prepareStatement(
                    "DELETE FROM payment_events WHERE event_id = ?"
                ).use { statement ->
                    statement.setString(1, eventId)
                    statement.executeUpdate()
                }
            }

            claimIds.forEach { claimId ->
                connection.prepareStatement(
                    "DELETE FROM claims WHERE id = ?"
                ).use { statement ->
                    statement.setObject(1, claimId)
                    statement.executeUpdate()
                }
            }

            connection.prepareStatement(
                "DELETE FROM deals WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, dealId)
                statement.executeUpdate()
            }
        }

        DatabaseFactory.close()
    }

    @Test
    fun `successful payment changes reserved claim to paid`() {
        val claimId = createClaim()

        processPayment(
            claimId = claimId,
            eventId = "payment-success-1",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "PAID")
        assertStockAvailable(9)
    }

    @Test
    fun `successful payment with incorrect amount needs review`() {
        val claimId = createClaim()

        processPayment(
            claimId = claimId,
            eventId = "payment-wrong-amount",
            type = "payment.succeeded",
            amountPaise = 40000
        )

        assertClaimStatus(claimId, "NEEDS_REVIEW")
        assertStockAvailable(9)
    }

    @Test
    fun `payment received after expiry becomes refund pending and restores stock`() {
        val claimId = createClaim(
            expiresAt = Instant.now().minusSeconds(60)
        )

        processPayment(
            claimId = claimId,
            eventId = "payment-after-expiry",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "REFUND_PENDING")
        assertStockAvailable(10)
    }

    @Test
    fun `expiry worker followed by late payment does not restore stock twice`() {
        val claimId = createClaim(
            expiresAt = Instant.now().minusSeconds(60)
        )

        val expiredUnits = ClaimRepository().expireReservations()

        assertEquals(1, expiredUnits)
        assertStockAvailable(10)
        assertClaimStatus(claimId, "EXPIRED")

        processPayment(
            claimId = claimId,
            eventId = "payment-after-worker-expiry",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "REFUND_PENDING")
        assertStockAvailable(10)
    }

    @Test
    fun `concurrent payment and expiry restore stock exactly once`() {
        val claimId = createClaim(
            expiresAt = Instant.now().minusSeconds(60)
        )

        val event = createEvent(
            claimId = claimId,
            eventId = "concurrent-payment-expiry",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            val paymentTask = executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                paymentRepository.processEvent(event)
            }

            val expiryTask = executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                ClaimRepository().expireReservations()
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            paymentTask.get(10, TimeUnit.SECONDS)
            expiryTask.get(10, TimeUnit.SECONDS)

        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertClaimStatus(claimId, "REFUND_PENDING")
        assertStockAvailable(10)
        assertEquals(1, countEvents(event.event_id))
    }

    @Test
    fun `concurrent duplicate payment events are processed only once`() {
        val claimId = createClaim()

        val event = createEvent(
            claimId = claimId,
            eventId = "concurrent-duplicate-payment",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            val tasks = (1..2).map {
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    paymentRepository.processEvent(event)
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            tasks.forEach {
                it.get(10, TimeUnit.SECONDS)
            }

        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertClaimStatus(claimId, "PAID")
        assertStockAvailable(9)
        assertEquals(1, countEvents(event.event_id))
    }

    @Test
    fun `duplicate event does not apply payment twice`() {
        val claimId = createClaim()

        val event = createEvent(
            claimId = claimId,
            eventId = "duplicate-payment-event",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        paymentRepository.processEvent(event)
        paymentRepository.processEvent(event)

        assertClaimStatus(claimId, "PAID")
        assertStockAvailable(9)
        assertEquals(1, countEvents(event.event_id))
    }

    @Test
    fun `failed payment leaves reservation unchanged`() {
        val claimId = createClaim()

        processPayment(
            claimId = claimId,
            eventId = "payment-failed-1",
            type = "payment.failed",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "RESERVED")
        assertStockAvailable(9)
    }

    @Test
    fun `unknown claim event is recorded without changing stock`() {
        val unknownClaimId = UUID.randomUUID()

        processPayment(
            claimId = unknownClaimId,
            eventId = "unknown-claim-event",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        assertEquals(1, countEvents("unknown-claim-event"))
        assertStockAvailable(10)
    }

    @Test
    fun `successful payment after failed event changes reservation to paid`() {
        val claimId = createClaim()

        processPayment(
            claimId = claimId,
            eventId = "failed-before-success",
            type = "payment.failed",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "RESERVED")
        assertStockAvailable(9)

        processPayment(
            claimId = claimId,
            eventId = "success-after-failure",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "PAID")
        assertStockAvailable(9)
    }

    @Test
    fun `failed event after successful payment does not reverse paid claim`() {
        val claimId = createClaim()

        processPayment(
            claimId = claimId,
            eventId = "success-before-failure",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        processPayment(
            claimId = claimId,
            eventId = "failure-after-success",
            type = "payment.failed",
            amountPaise = 50000
        )

        assertClaimStatus(claimId, "PAID")
        assertStockAvailable(9)
    }

    @Test
    fun `new successful event for already paid claim does not change it`() {
        val claimId = createClaim()

        processPayment(
            claimId = claimId,
            eventId = "first-success-event",
            type = "payment.succeeded",
            amountPaise = 50000
        )

        processPayment(
            claimId = claimId,
            eventId = "second-success-event",
            type = "payment.succeeded",
            amountPaise = 40000
        )

        assertClaimStatus(claimId, "PAID")
        assertStockAvailable(9)
    }

    private fun createClaim(
        quantity: Int = 1,
        expiresAt: Instant = Instant.now().plusSeconds(600)
    ): UUID {
        val claimId = UUID.randomUUID()
        claimIds.add(claimId)

        DatabaseFactory.dataSource.connection.use { connection ->
            connection.autoCommit = false

            try {
                connection.prepareStatement(
                    """
                    UPDATE deals
                    SET stock_available = stock_available - ?
                    WHERE id = ?
                      AND stock_available >= ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, quantity)
                    statement.setObject(2, dealId)
                    statement.setInt(3, quantity)

                    assertEquals(1, statement.executeUpdate())
                }

                connection.prepareStatement(
                    """
                    INSERT INTO claims (
                        id,
                        deal_id,
                        customer_id,
                        quantity,
                        amount_paise,
                        status,
                        expires_at
                    )
                    VALUES (?, ?, ?, ?, ?, 'RESERVED', ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, claimId)
                    statement.setObject(2, dealId)
                    statement.setObject(3, customerId)
                    statement.setInt(4, quantity)
                    statement.setInt(5, 50000 * quantity)
                    statement.setTimestamp(6, Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }

                connection.commit()

            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }

        return claimId
    }

    private fun processPayment(
        claimId: UUID,
        eventId: String,
        type: String,
        amountPaise: Int
    ) {
        paymentRepository.processEvent(
            createEvent(claimId, eventId, type, amountPaise)
        )
    }

    private fun createEvent(
        claimId: UUID,
        eventId: String,
        type: String,
        amountPaise: Int
    ): PaymentWebhookRequest {
        eventIds.add(eventId)

        return PaymentWebhookRequest(
            event_id = eventId,
            type = type,
            claim_id = claimId.toString(),
            amount_paise = amountPaise,
            occurred_at = Instant.now().toString()
        )
    }

    private fun assertClaimStatus(
        claimId: UUID,
        expectedStatus: String
    ) {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status FROM claims WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, claimId)

                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(expectedStatus, result.getString("status"))
                }
            }
        }
    }

    private fun assertStockAvailable(expected: Int) {
        DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT stock_available FROM deals WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, dealId)

                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(expected, result.getInt("stock_available"))
                }
            }
        }
    }

    private fun countEvents(eventId: String): Int {
        return DatabaseFactory.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM payment_events WHERE event_id = ?"
            ).use { statement ->
                statement.setString(1, eventId)

                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    result.getInt(1)
                }
            }
        }
    }
}
