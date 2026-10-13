package com.luis.dealstracker.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DealScoreRotationTest {

    @Test
    fun `rotation is random per query, not a function of the clock`() {
        val sql = DealScore.rotating(true)
        assertTrue("random()" in sql)
        assertFalse("hashtext" in sql, "the 3-hour bucket hash is what made reloads identical")
    }

    @Test
    fun `rotation off is the plain score`() {
        assertEquals(DealScore.SQL, DealScore.rotating(false))
    }

    @Test
    fun `a downvoted deal is held below upvoted ones but a missing vote is not a downvote`() {
        assertEquals("(COALESCE(d.community_score, 1) > 0)", DealScore.DOWNVOTED_LAST)
    }
}
