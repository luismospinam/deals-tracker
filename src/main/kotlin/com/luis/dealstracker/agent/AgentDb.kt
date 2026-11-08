package com.luis.dealstracker.agent

import com.luis.dealstracker.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.SecureRandom
import java.sql.Connection
import javax.sql.DataSource

/**
 * The tables an agent may read through the `sql` tool, and nothing else.
 *
 * An allow-list, not "everything but": a table added later stays unreadable until someone decides it
 * should be readable. `app_settings` is the one that must never be here -- it holds the Exito login
 * cookie and the cart id.
 */
internal val AGENT_READABLE_TABLES = listOf(
    "deals", "price_observations", "observation_promotions", "promotions", "deal_specs", "site_banners",
    "manual_items", "watches", "watch_matches", "watch_hidden_deals", "watch_sourced_deals", "disabled_searches",
    "card_programs", "promo_reminders", "fetch_runs", "purchases", "suggestions", "agent_runs",
)

internal const val AGENT_ROLE = "deals_agent"

/** One `sql` tool answer, as rows of text. [truncated] when the row cap cut it short. */
internal data class QueryResult(val columns: List<String>, val rows: List<List<String?>>, val truncated: Boolean)

/** [r] as tab-separated text, the shape that costs the model fewest tokens to read. */
internal fun QueryResult.toTsv(maxCell: Int = 200): String = buildString {
    appendLine(columns.joinToString("\t"))
    rows.forEach { row -> appendLine(row.joinToString("\t") { it?.replace(Regex("[\t\r\n]+"), " ")?.take(maxCell) ?: "" }) }
    if (truncated) appendLine("… cut at ${rows.size} rows; add a LIMIT or aggregate")
    if (rows.isEmpty()) appendLine("(no rows)")
}

/**
 * The database as an agent sees it: a login role that can only SELECT [AGENT_READABLE_TABLES].
 *
 * Read-only is enforced by Postgres, not by inspecting the SQL. The role is not a superuser and holds
 * no write grant, so an UPDATE fails in the server whatever the query looks like, and the server-side
 * escapes a superuser has (COPY ... TO PROGRAM, pg_read_file) are not open to it. Every session also
 * defaults to a read-only transaction with a statement timeout, as a second wall.
 *
 * The password is random per boot and never written anywhere: [ensure] sets it with the app's own
 * (owner) connection and opens a small pool with it.
 */
class AgentDb private constructor(private val pool: HikariDataSource) {

    /** Runs [sql] read-only, at most [maxRows] rows, cut off after [timeoutSeconds]. */
    internal fun query(sql: String, maxRows: Int = 500, timeoutSeconds: Int = 10): QueryResult =
        pool.connection.use { conn ->
            conn.isReadOnly = true
            conn.autoCommit = false
            try {
                conn.createStatement().use { st ->
                    st.queryTimeout = timeoutSeconds
                    st.maxRows = maxRows + 1
                    st.executeQuery(sql.trim().trimEnd(';')).use { rs ->
                        val meta = rs.metaData
                        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                        val rows = mutableListOf<List<String?>>()
                        while (rs.next() && rows.size <= maxRows) rows += columns.indices.map { rs.getString(it + 1) }
                        val truncated = rows.size > maxRows
                        QueryResult(columns, rows.take(maxRows), truncated)
                    }
                }
            } finally {
                conn.rollback()
            }
        }

    fun close() = pool.close()

    companion object {
        /**
         * Creates or refreshes [AGENT_ROLE] through [owner] (the app's own connection, which owns the
         * schema) and returns a pool logged in as it.
         */
        fun ensure(owner: DataSource, cfg: DbConfig): AgentDb {
            val password = randomToken(24)
            owner.connection.use { conn -> applyRole(conn, password) }
            val pool = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = cfg.jdbcUrl
                    username = AGENT_ROLE
                    this.password = password
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = 2
                    connectionTimeout = 10_000
                    isReadOnly = true
                    poolName = "deals-agent"
                },
            )
            return AgentDb(pool)
        }

        internal fun roleStatements(password: String): List<String> {
            require(password.matches(Regex("[A-Za-z0-9_-]+"))) { "password must be token characters" }
            return listOf(
                """
                DO ${'$'}${'$'} BEGIN
                  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$AGENT_ROLE') THEN CREATE ROLE $AGENT_ROLE; END IF;
                END ${'$'}${'$'}
                """.trimIndent(),
                "ALTER ROLE $AGENT_ROLE WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD '$password'",
                "ALTER ROLE $AGENT_ROLE SET default_transaction_read_only = on",
                "ALTER ROLE $AGENT_ROLE SET statement_timeout = '10s'",
                "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM $AGENT_ROLE",
                "REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM $AGENT_ROLE",
                "REVOKE CREATE ON SCHEMA public FROM $AGENT_ROLE",
                "GRANT USAGE ON SCHEMA public TO $AGENT_ROLE",
                "GRANT SELECT ON ${AGENT_READABLE_TABLES.joinToString(", ")} TO $AGENT_ROLE",
            )
        }

        private fun applyRole(conn: Connection, password: String) {
            conn.createStatement().use { st -> roleStatements(password).forEach { st.execute(it) } }
        }

        private val random = SecureRandom()

        /** A URL-safe random string of [bytes] bytes. */
        fun randomToken(bytes: Int = 32): String {
            val b = ByteArray(bytes).also(random::nextBytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}
