package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.DbConfig
import com.luis.dealstracker.config.Defaults
import com.luis.dealstracker.db.DealRepository
import javax.sql.DataSource

fun fakeAppConfig(minDiscountPercent: Double = 25.0) = AppConfig(
    defaults = Defaults(minDiscountPercent = minDiscountPercent),
    providers = emptyList(),
    db = DbConfig("jdbc:postgresql://localhost:5432/unused", "u", "p"),
    mlClientId = null,
    mlClientSecret = null,
)

/**
 * The promotion rule is pure, so these tests need no database. This stands in for the
 * repository and fails loudly if the rule ever starts touching it.
 */
fun throwingRepo(): DealRepository = DealRepository(
    object : DataSource {
        private fun no(): Nothing = error("promotionReason must not touch the database")
        override fun getConnection() = no()
        override fun getConnection(username: String?, password: String?) = no()
        override fun getLogWriter() = no()
        override fun setLogWriter(out: java.io.PrintWriter?) = no()
        override fun setLoginTimeout(seconds: Int) = no()
        override fun getLoginTimeout() = no()
        override fun getParentLogger() = no()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = no()
        override fun isWrapperFor(iface: Class<*>?) = no()
    },
)
