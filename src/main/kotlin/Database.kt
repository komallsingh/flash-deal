package com.nnomm

import com.nnomm.config.DatabaseFactory
import io.ktor.server.application.*
import org.flywaydb.core.Flyway

fun Application.configureDatabase() {
    DatabaseFactory.initialize()

    Flyway.configure()
        .dataSource(DatabaseFactory.dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    log.info("Database connected and migrations applied!")

    environment.monitor.subscribe(ApplicationStopped) {
        DatabaseFactory.close()
        log.info("Database connection pool closed")
    }
}