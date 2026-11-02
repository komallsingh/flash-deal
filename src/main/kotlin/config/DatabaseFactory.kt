package com.nnomm.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object DatabaseFactory {

    lateinit var dataSource: HikariDataSource
        private set

    fun initialize() {
        dataSource = createDataSource("FlashDealPool")

        // Verify the connection during application startup
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result ->
                    check(result.next()) {
                        "Database connection test failed"
                    }
                }
            }
        }
    }

    internal fun createDataSource(poolName: String): HikariDataSource =
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://127.0.0.1:5433/flash_deal"

            username = System.getenv("DATABASE_USER")
                ?: "flash_user"

            password = System.getenv("DATABASE_PASSWORD")
                ?: throw IllegalStateException(
                    "DATABASE_PASSWORD environment variable is missing"
                )

            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000
            this.poolName = poolName
        })

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }
}