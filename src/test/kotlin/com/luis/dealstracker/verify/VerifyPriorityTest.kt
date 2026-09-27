package com.luis.dealstracker.verify

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.DbConfig
import com.luis.dealstracker.config.Defaults
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.verifyConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shipping queue checks the Tracked tab's rows first.
 *
 * Priority is derived from the same `trackSearches` rule that decides what the Tracked tab lists,
 * so adding a tracking provider moves it to the front of the queue without a second list to keep
 * in step.
 */
class VerifyPriorityTest {

    private fun app(vararg providers: ProviderConfig) = AppConfig(
        defaults = Defaults(),
        providers = providers.toList(),
        db = DbConfig("jdbc:none", "u", "p"),
        mlClientId = null,
        mlClientSecret = null,
    )

    @Test
    fun `tracking providers are the priority sources`() {
        val cfg = verifyConfig(
            app(
                ProviderConfig(id = "slickdeals", type = "rss-slickdeals"),
                ProviderConfig(id = "amazon", type = "amazon", listOptions = mapOf("trackSearches" to listOf("nike men shoes"))),
                // A watch provider fetches by watch terms, not trackSearches, so it is not Tracked.
                ProviderConfig(id = "amazon-watches", type = "amazon-watch"),
            ),
        )
        assertEquals(listOf("amazon"), cfg.prioritySources)
    }

    /** A disabled tracker has no rows being refreshed, so it earns no place at the front. */
    @Test
    fun `a disabled tracker is not prioritised`() {
        val cfg = verifyConfig(
            app(
                ProviderConfig(
                    id = "amazon", type = "amazon", enabled = false,
                    listOptions = mapOf("trackSearches" to listOf("nike men shoes")),
                ),
            ),
        )
        assertEquals(emptyList(), cfg.prioritySources)
    }
}
