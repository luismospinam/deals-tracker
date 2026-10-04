package com.luis.dealstracker.db

import javax.sql.DataSource

/**
 * Which configured `trackSearches` terms are switched off. See V22.
 *
 * Switching a search off only stops it being fetched. Its recorded rows and price history stay, so
 * switching it back on resumes where it left off (rows not seen for 48h are retired by
 * `deactivateStale` and reappear on the next fetch).
 */
class DisabledSearchRepository(private val ds: DataSource) {

    fun disabled(provider: String): Set<String> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT term FROM disabled_searches WHERE provider = ?").use { st ->
            st.setString(1, provider)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun setEnabled(provider: String, term: String, enabled: Boolean) = ds.connection.use { conn ->
        val sql = if (enabled) {
            "DELETE FROM disabled_searches WHERE provider = ? AND term = ?"
        } else {
            "INSERT INTO disabled_searches (provider, term) VALUES (?, ?) ON CONFLICT DO NOTHING"
        }
        conn.prepareStatement(sql).use { st ->
            st.setString(1, provider)
            st.setString(2, term)
            st.executeUpdate()
        }
    }
}
