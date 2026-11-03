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

    /** Every setting whose key starts with [prefix], keyed without it. */
    fun withPrefix(prefix: String): Map<String, String> =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT key, value FROM app_settings WHERE starts_with(key, ?)").use { st ->
                st.setString(1, prefix)
                st.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1).removePrefix(prefix), rs.getString(2)) }
                }
            }
        }

    /** Removes [keys], so whatever they overrode is in charge again. */
    fun delete(keys: Collection<String>) {
        if (keys.isEmpty()) return
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM app_settings WHERE key = ANY (?)").use { st ->
                st.setArray(1, conn.createArrayOf("text", keys.toTypedArray()))
                st.executeUpdate()
            }
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

        /** ISO-8601 instant of the last slot the pinned-on-sale post was handled for, per section. */
        const val PINNED_ALERT_GROCERIES_SLOT = "pinned_alert_groceries_slot"
        const val PINNED_ALERT_TRACKED_SLOT = "pinned_alert_tracked_slot"

        /** The last watch-digest slot sent, so a restart neither repeats nor skips one. */
        const val WATCH_DIGEST_SLOT = "watch_digest_slot"

        /**
         * The card programmes the operator holds, comma-separated `CardScope` names ("CMR,STORE_CARD").
         * Turns on the CARD_LOW watch rule for those cards; unset or empty, it never fires.
         */
        const val HELD_CARDS = "held_cards"

        /** The last good database dump: when, and its file name. See `ops.DatabaseBackup`. */
        const val BACKUP_LAST_AT = "backup_last_at"
        const val BACKUP_LAST_FILE = "backup_last_file"
        /** Why the last attempt failed, cleared by the next good one. Its time is [BACKUP_FAILED_AT]. */
        const val BACKUP_LAST_ERROR = "backup_last_error"
        const val BACKUP_FAILED_AT = "backup_failed_at"

        /** The sources an "unhealthy" alert went out for and no "recovered" yet, comma-separated. */
        const val HEALTH_ALERTED = "health_alerted"

        /**
         * [HELD_CARDS] as the scopes those cards can pay. Holding the store card also pays every
         * MASTERCARD offer, since Tarjeta Éxito y Carulla is a Mastercard; the reverse is not true.
         * Unknown names are dropped, so a value written by a newer build cannot break an older one.
         */
        fun heldCards(value: String?): Set<com.luis.dealstracker.model.CardScope> {
            val held = pickedCards(value)
            return if (com.luis.dealstracker.model.CardScope.STORE_CARD in held) held + com.luis.dealstracker.model.CardScope.MASTERCARD else held
        }

        /** [HELD_CARDS] exactly as ticked, for the form: no implied scopes added. */
        fun pickedCards(value: String?): Set<com.luis.dealstracker.model.CardScope> =
            value.orEmpty().split(',').mapNotNull { name ->
                runCatching { com.luis.dealstracker.model.CardScope.valueOf(name.trim()) }.getOrNull()
            }.toSet()

        /** The pasted exito.com `VtexIdclientAutCookie_exitocol` cookie the cart button acts as. Blank when cleared. */
        const val EXITO_COOKIE = "exito_cookie"

        /** The shopper's own cart id (the `checkout.vtex.com` cookie's `__ofid`), which the cart button adds to. */
        const val EXITO_CART_ID = "exito_cart_id"
    }
}
