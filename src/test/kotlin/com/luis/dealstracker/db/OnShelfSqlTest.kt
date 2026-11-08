package com.luis.dealstracker.db

import kotlin.test.Test
import kotlin.test.assertTrue

class OnShelfSqlTest {
    @Test
    fun `on the shelf needs the latest crawl and no sold-out report since it began`() {
        val sql = GroceryRepository.ON_SHELF
        assertTrue(sql.contains("d.last_seen_at >= s.since"))
        assertTrue(sql.contains("d.sold_out_at IS NULL OR d.sold_out_at < s.since"), "a crawl started after the report puts it back")
    }
}
