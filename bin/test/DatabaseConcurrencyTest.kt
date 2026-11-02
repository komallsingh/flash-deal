package com.nnomm

import com.nnomm.config.DatabaseFactory
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseConcurrencyTest {

    @Test
    fun `independent database pools cannot reserve the same last unit`() {
        val firstInstance = DatabaseFactory.createDataSource("ConcurrencyPoolA")
        val secondInstance = DatabaseFactory.createDataSource("ConcurrencyPoolB")
        val dealId = UUID.randomUUID()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            Flyway.configure()
                .dataSource(firstInstance)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            createDeal(firstInstance, dealId)

            val firstReservation = executor.submit<Boolean> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS)) {
                    "Timed out waiting to start the first reservation"
                }
                reserveLastUnit(firstInstance, dealId, UUID.randomUUID())
            }
            val secondReservation = executor.submit<Boolean> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS)) {
                    "Timed out waiting to start the second reservation"
                }
                reserveLastUnit(secondInstance, dealId, UUID.randomUUID())
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready")
            start.countDown()

            assertEquals(
                1,
                listOf(
                    firstReservation.get(20, TimeUnit.SECONDS),
                    secondReservation.get(20, TimeUnit.SECONDS)
                ).count { it }
            )
            assertEquals(0, availableStock(firstInstance, dealId))
            assertEquals(1, claimCount(firstInstance, dealId))
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            deleteDeal(firstInstance, dealId)
            secondInstance.close()
            firstInstance.close()
        }
    }

    private fun createDeal(dataSource: HikariDataSource, dealId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO deals (
                    id, restaurant_id, title, price_paise, stock_total,
                    stock_available, starts_at, ends_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, dealId)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, "Concurrency test deal")
                statement.setInt(4, 1000)
                statement.setInt(5, 1)
                statement.setInt(6, 1)
                statement.setTimestamp(7, Timestamp.from(Instant.now().minusSeconds(60)))
                statement.setTimestamp(8, Timestamp.from(Instant.now().plusSeconds(3600)))
                statement.executeUpdate()
            }
        }
    }

    private fun reserveLastUnit(
        dataSource: HikariDataSource,
        dealId: UUID,
        customerId: UUID
    ): Boolean = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val updated = connection.prepareStatement(
                """
                UPDATE deals
                SET stock_available = stock_available - 1
                WHERE id = ? AND stock_available >= 1
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, dealId)
                statement.executeUpdate()
            }

            if (updated == 0) {
                connection.rollback()
                return@use false
            }

            connection.prepareStatement(
                """
                INSERT INTO claims (
                    id, deal_id, customer_id, quantity, amount_paise,
                    status, expires_at
                ) VALUES (?, ?, ?, 1, 1000, 'RESERVED', ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, dealId)
                statement.setObject(3, customerId)
                statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(900)))
                statement.executeUpdate()
            }

            connection.commit()
            true
        } catch (exception: SQLException) {
            connection.rollback()
            throw exception
        }
    }

    private fun availableStock(
        dataSource: HikariDataSource,
        dealId: UUID
    ): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT stock_available FROM deals WHERE id = ?"
        ).use { statement ->
            statement.setObject(1, dealId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Test deal was not found" }
                result.getInt(1)
            }
        }
    }

    private fun claimCount(
        dataSource: HikariDataSource,
        dealId: UUID
    ): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM claims WHERE deal_id = ?"
        ).use { statement ->
            statement.setObject(1, dealId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Claim count query returned no result" }
                result.getInt(1)
            }
        }
    }

    private fun deleteDeal(
        dataSource: HikariDataSource,
        dealId: UUID
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM claims WHERE deal_id = ?").use { statement ->
                statement.setObject(1, dealId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM deals WHERE id = ?").use { statement ->
                statement.setObject(1, dealId)
                statement.executeUpdate()
            }
        }
    }
}
