package com.nnomm

import com.nnomm.config.DatabaseFactory
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import repository.ClaimRepository

fun Application.configureDatabase() {
    DatabaseFactory.initialize()

    Flyway.configure()
        .dataSource(DatabaseFactory.dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    log.info("Database connected and migrations applied!")

    // Background expiry worker
    val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val claimRepository = ClaimRepository()

    expiryScope.launch {
        while (isActive) {
            try {
                val expiredUnits = claimRepository.expireReservations()

                if (expiredUnits > 0) {
                    log.info("Expired reservations and restored $expiredUnits units")
                }
            } catch (exception: Exception) {
                log.error("Claim expiry worker failed", exception)
            }

            delay(1000)
        }
    }

    environment.monitor.subscribe(ApplicationStopped) {
        expiryScope.cancel()
        DatabaseFactory.close()
        log.info("Database connection pool closed")
    }
}