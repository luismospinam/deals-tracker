package com.luis.dealstracker.db

import javax.sql.DataSource

/** `app_settings`: the few values the UI sets and a restart must keep. See V19. */
class SettingsRepository(private val ds: DataSource) {

    fun get(key: String): String? =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT value FROM app_settings WHERE key = ?").use { st ->
                st.setString(1, key)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    fun set(values: Map<String, String>) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO app_settings (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """.trimIndent(),
            ).use { st ->
                values.forEach { (k, v) ->
                    st.setString(1, k)
                    st.setString(2, v)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    companion object {
        const val LANGUAGE = "language"

        /** ISO-8601 instant of the last finished price-history prune. */
        const val HISTORY_PRUNED_AT = "history_pruned_at"

        /** "true" once the language was picked with the switch rather than read from a browser. */
        const val LANGUAGE_CHOSEN = "language_chosen"

        /** The pasted exito.com `VtexIdclientAutCookie_exitocol` cookie the cart button acts as. Blank when cleared. */
        const val EXITO_COOKIE = "exito_cookie"

        /** The shopper's own cart id (the `checkout.vtex.com` cookie's `__ofid`), which the cart button adds to. */
        const val EXITO_CART_ID = "exito_cart_id"
    }
}
