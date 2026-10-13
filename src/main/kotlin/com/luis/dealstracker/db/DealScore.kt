package com.luis.dealstracker.db

/**
 * The feed's relevance score, defined exactly once as a SQL expression.
 *
 * ## Why a score at all
 *
 * The default sort used to lead with `d.promoted DESC`. Measured against the live database,
 * **500 of 618 active deals were promoted** — Slickdeals 243/243 (by design, via
 * `promoteAllNew`), Éxito 186/227, Carulla 71/148. A leading sort key that is true for 81% of
 * rows sorts almost nothing, so the feed was in practice ordered by its second key, recency,
 * and the discount term at the end almost never got to break a tie.
 *
 * That is the same failure this project already documents for badges — "a badge that appears
 * on every row conveys nothing" — arriving at the level of the primary ranking signal.
 * `promoted` is still a useful *fact* about what happened at ingest, and it keeps its column
 * and its filter; it is simply not a ranking.
 *
 * ## Why SQL, and why only here
 *
 * The score has to order and then LIMIT, so it cannot live in Kotlin. And it is needed by
 * three different queries — the feed, a single deal, and the verification queue — so it is
 * declared once here for the same reason [DealRepository.criteria] is built once: two copies
 * of a definition drift, and the failure is silent.
 *
 * Age is part of the score, so it is deliberately **not** a stored column: a persisted value
 * would be wrong a minute after it was written.
 *
 * ## Reading the weights
 *
 * The three evidence terms total 85 and age adds up to 15 more, so a brand-new deal with every
 * signal in its favour approaches 100. The freight term alone can cancel all of it. Each weight
 * below says what evidence it rests on; the evidence terms are weighted by how much they can be
 * trusted rather than by how loud they are.
 *
 * Two terms can go **negative**, and for different reasons: freight, because a shipping fee can
 * make a real bargain a bad buy, and age, because a row nobody has retired must not sit at the
 * top of the page forever. See each for the measurements behind it.
 *
 * [SQL] is the honest score — shown in the UI and used to order the verification queue.
 * [rotating] adds a bounded, random offset and is used *only* for the feed's default
 * ordering, so the page varies between visits without the reported number ever lying.
 *
 * **Requires the `po` and `agg` lateral joins** to be in scope — the latest observation and
 * the min/max aggregate. Every query using this must join both.
 */
internal object DealScore {

    /** 14 days in seconds, matching `maxAgeDays` — the window the sources are filtered to. */
    private const val AGE_WINDOW_SECONDS = 14 * 86_400

    /**
     * How far the rotation term may move a row, in points, either way.
     *
     * 10 because the previous 4 changed nothing visible: the best deal (61.8) sat 16.4 points above
     * the 20th, so a +-4 swing could not reorder the top of the page, and the top of the page is what
     * gets read. At 10 the leaders still tend to lead but the top ~20 differ between loads. A wider
     * value lets weak rows reach the top; narrower brings the static page back.
     */
    private const val ROTATION_AMPLITUDE = 10.0

    val SQL: String = """
        (
            -- Advertised discount, 0..30. The lowest-weighted positive because it is the least
            -- trustworthy: Exito flags ~94% of its catalog as discounted, so a large "off %"
            -- computed from the retailer's own list price is a claim, not evidence. Saturates
            -- at 60% off, where the UI's discount ramp already tops out.
            LEAST(GREATEST(COALESCE((1 - po.price / NULLIF(po.list_price, 0)) * 100, 0), 0) / 60.0, 1) * 30

            -- A decline we observed ourselves, 0..30. Weighted as high as the advertised
            -- discount despite being far rarer -- only 246 of 1,687 tracked deals have ever
            -- moved -- because it is the one positive signal an inflated list price cannot
            -- manufacture. Saturates at a 40% fall from our recorded peak.
          + LEAST(GREATEST(COALESCE((agg.max_price - po.price) / NULLIF(po.price, 0), 0), 0) / 0.40, 1) * 30

            -- Community vote, 0..25. The only deal-quality evidence that exists for Slickdeals,
            -- which publishes no list price at all and can therefore never earn a single point
            -- from the discount term above. A NULL score earns 0 rather than being penalised:
            -- VTEX publishes no equivalent, and a missing vote is not a downvote.
            --
            -- **Logarithmic, and saturating at 60 rather than at 100.** Both were forced by
            -- measuring the live distribution instead of trusting the range in README.md. Among
            -- active deals the median score is 3, p75 is 10 and p90 is 23 — against a maximum of
            -- 219. A linear ramp saturating at 100 therefore mapped the *median* Slickdeals deal
            -- to 0.75 of a possible 25 points, while the median Éxito deal collected 19 from its
            -- list price alone (median 38% off). That is the failure this project already
            -- documents — "sorting purely by discount buried Slickdeals entirely" — rebuilt in a
            -- subtler form, and it put only 5 Slickdeals rows in the top 50 despite the source
            -- being 39% of the feed.
            --
            -- A log curve also matches what the number means: votes accumulate multiplicatively,
            -- so 200 upvotes is not 66 times better evidence than 3. At this scale the median
            -- earns 8.4, p90 earns 19.3, and only a genuinely exceptional score reaches 25.
          + LEAST(LN(1 + GREATEST(COALESCE(d.community_score, 0), 0)) / LN(61.0), 1) * 25

            -- Age, **-15..+15**: +15 the day it appears, 0 at 14 days, then continuing down to
            -- -15 at 28 days and holding there. COALESCE for the same reason the sort does it --
            -- VTEX publishes no posting date.
            --
            -- The negative half is what keeps the feed from freezing, and it is not hypothetical.
            -- This term used to be `GREATEST(0, ...)`, flooring at zero after 14 days, which
            -- interacted badly with two other facts:
            --
            --   * VTEX publishes no `posted_at`, so age is measured from `first_seen_at` -- when
            --     *we* first saw it, not when it was posted.
            --   * Éxito and Carulla are re-fetched every hour, so `staleAfterHours` never trips
            --     and their rows never retire. Slickdeals rows age out on their own (max age
            --     observed: 6 days); Éxito's median age is 15 days.
            --
            -- Past 14 days such a row scored zero for age and could fall no further, and with no
            -- community score and no freight quote every one of its remaining terms is static --
            -- so **its score was a constant, permanently**. Measured: 253 of 894 active deals
            -- (28%) sat at the old floor, 180 of them Éxito, and 30 of the top 50 were Éxito
            -- rows that could never move. Letting the term go negative means an item we have
            -- been staring at for a month sinks below a fresh one on its own.
          + GREATEST(-1.0, 1 - EXTRACT(EPOCH FROM (now() - COALESCE(d.posted_at, d.first_seen_at)))
                                 / ${AGE_WINDOW_SECONDS}.0) * 15

            -- Freight, 0..-60: the largest single term, and deliberately able to cancel every
            -- positive above. Of the 27 verified deals carrying a quoted shipping fee, 23 had a
            -- fee above half the item's price and 10 above the entire price -- $248.68 of
            -- shipping on a $267.99 television, $112.93 on an $89.32 chair. All of them rendered
            -- as a green "ships" verdict and sorted near the top. A fee equal to the price now
            -- zeroes the row's positive score.
            --
            -- NULL means Amazon quoted nothing, which is not the same as free and must not be
            -- penalised; Colombian sources never carry a cost at all.
            --
            -- Written as an explicit CASE rather than the obvious
            -- `COALESCE(LEAST(cost / price, 1.0), 0)` because **Postgres LEAST/GREATEST ignore
            -- NULL arguments instead of propagating them**: `LEAST(NULL, 1.0)` is `1.0`, not
            -- NULL. That version therefore charged the full 60-point penalty to every deal that
            -- had never been quoted a shipping cost -- which is most of them -- and scored 613
            -- of 618 active deals negative. The COALESCE could never fire, because nothing ever
            -- reached it as NULL.
          - CASE
                WHEN d.co_shipping_cost IS NULL OR po.price <= 0 THEN 0
                ELSE LEAST(d.co_shipping_cost / po.price, 1.0) * 60
            END

            -- Import charges apply, -8. Amazon quotes these separately from freight and they are
            -- usually covered (105 of 110 verdicts said "No Import Charges"), so this penalises
            -- the exception rather than rewarding the norm. NULL = Amazon said nothing.
          - CASE WHEN d.co_shipping_import_free IS FALSE THEN 8 ELSE 0 END

            -- Confirmed unshippable, -40. Still visible, because the Colombia filter is the
            -- right way to exclude these and a verdict is worth seeing -- but never ranked above
            -- something that can actually arrive.
          - CASE WHEN d.co_shipping = 'NO' THEN 40 ELSE 0 END
        )
    """.trimIndent()

    /**
     * [SQL] plus a random offset, for the feed's default ordering only.
     *
     * ## Why the feed needs this and the score does not
     *
     * A score that is a pure function of stored facts produces a page whose top is identical
     * every time you open it, because most of those facts never change: only 14.6% of tracked
     * deals have ever moved on price, and nothing here records that you have already looked at
     * something -- every active deal is still `state = NEW`, since `SEEN` is in the enum and in
     * the filter but no code path has ever written it.
     *
     * The offset is **random per query**, so every page load reshuffles. This replaced a hash of
     * the deal id and a 3-hour bucket: reloads inside a bucket were identical, and the +-4 swing
     * was too small to move the top of the page at all (the best deal sat 16 points above the
     * 20th). The cost is that two loads disagree on order; the Rank column is unaffected because
     * it shows plain [SQL].
     *
     * Bounded by [ROTATION_AMPLITUDE], and a downvoted deal is held below every upvoted one
     * whatever the jitter says -- see [DOWNVOTED_LAST].
     *
     * ## Deliberately not applied everywhere
     *
     * Only the default sort uses this. The value **displayed** in the Rank column is plain [SQL],
     * because showing a jittered number would make the column disagree with itself between
     * refreshes. The verification queue also stays on plain [SQL]: its budget is scarce and
     * should go to the best candidates in a defensible order.
     *
     * @param enabled false returns [SQL] unchanged, so the feature can be turned off in config
     *   without a rebuild.
     */
    fun rotating(enabled: Boolean): String {
        if (!enabled) return SQL
        return """
            (
                $SQL
              + (random() * 2 - 1) * $ROTATION_AMPLITUDE
            )
        """.trimIndent()
    }

    /**
     * Leading sort key for the default ordering: false (voted down, score <= 0) sorts after true.
     * A NULL score counts as fine, since VTEX publishes none and a missing vote is not a downvote.
     * Without it the random offset could float a -14 listing above a +40 one.
     */
    const val DOWNVOTED_LAST = "(COALESCE(d.community_score, 1) > 0)"
}
