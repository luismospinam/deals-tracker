package com.luis.dealstracker.db

import com.luis.dealstracker.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Db {
    lateinit var dataSource: DataSource
        private set

    fun connect(cfg: DbConfig): DataSource {
        val hikari = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = cfg.jdbcUrl
                username = cfg.user
                password = cfg.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 8
                // The poller and the web UI share this pool; fail fast rather than
                // hanging a page render if Docker isn't up.
                connectionTimeout = 10_000
                poolName = "deals-tracker"
            },
        )
        dataSource = hikari
        return hikari
    }

    /** Applies pending migrations. Runs on boot so a fresh `docker compose up` needs no manual setup. */
    fun migrate() {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        println("[db] schema at version ${result.targetSchemaVersion ?: "baseline"}, ${result.migrationsExecuted} migration(s) applied")
    }
}
