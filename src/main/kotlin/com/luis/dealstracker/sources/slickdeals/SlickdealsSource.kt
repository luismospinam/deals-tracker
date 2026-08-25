package com.luis.dealstracker.sources.slickdeals

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Slickdeals RSS adapter.
 *
 * The feed's `content:encoded` field is an HTML fragment that carries far more
 * than the summary does: `data-store-slug`, `data-store-id` and — for Amazon
 * items — `data-aps-asin`. So no page scraping is needed.
 */
class SlickdealsSource : DealSource {
    override val type = "rss-slickdeals"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        // Slickdeals RSS returns at most 25 items per request. Two ways around it,
        // both established by probing the live endpoint:
        //
        //  1. Different feeds carry different sets -- `mode=frontpage` overlaps
        //     popdeals/newdeals by ZERO. `page` is IGNORED for these mode feeds
        //     (6 pages returned 150 rows but only 25 unique).
        //  2. `q=<term>` searches DO paginate: q=apple over 5 pages gave 114 unique
        //     deals. This is the only way to reach deals older than the ~25 most
        //     recent, which is why a deal visible on slickdeals.net/deals/apple
        //     ("2w ago") never appears in the mode feeds.
        //
        // `filters[store][]` from the web UI is ignored by the RSS endpoint, so
        // merchant filtering still happens here via resolveMerchant.
        val allowed = cfg.list("allowedMerchants").map { it.lowercase() }.toSet()
        val urls = buildFeedUrls(cfg)

        var rawItems = 0
        var parsedCount = 0
        // Deduped by thread id across feeds, so an item appearing in two feeds is one
        // deal -- which works because all feeds share this provider's source id.
        val kept = LinkedHashMap<String, Parsed>()
        val filteredOut = mutableListOf<Parsed>()

        for (url in urls) {
            val result = try {
                http.get(url)
            } catch (e: Exception) {
                println("[${cfg.id}] $url failed: ${e.message}")
                continue
            }
            if (result.notModified) continue
            val body = result.body
            if (body == null || !result.ok) {
                println("[${cfg.id}] $url -> HTTP ${result.status}")
                continue
            }

            rawItems += countItems(body)
            val parsed = parse(body)
            parsedCount += parsed.size
            parsed.forEach { p ->
                if (allowed.isEmpty() || p.merchant?.lowercase() in allowed) {
                    kept.putIfAbsent(p.deal.externalId, p)
                } else {
                    filteredOut += p
                }
            }
        }

        // Report what was dropped and why, so a parser regression shows up as a
        // suspicious count rather than a quietly shrinking feed.
        val unpriced = rawItems - parsedCount
        val byMerchant = filteredOut.groupingBy { it.merchant ?: "unknown" }.eachCount()
        println(
            "[${cfg.id}] ${urls.size} feed(s), $rawItems items -> ${kept.size} kept" +
                (if (unpriced > 0) ", $unpriced skipped (no parseable price)" else "") +
                (if (byMerchant.isNotEmpty()) ", filtered out: $byMerchant" else ""),
        )
        return kept.values.map { it.deal }
    }

    /**
     * Expands config into the concrete list of feed URLs to fetch: the fixed `urls`
     * plus one URL per (search term x page).
     */
    internal fun buildFeedUrls(cfg: ProviderConfig): List<String> {
        val fixed = cfg.list("urls").ifEmpty { listOfNotNull(cfg.url) }
        val searches = cfg.list("searches")
        val pages = cfg.int("searchPages", 5).coerceIn(1, 20)

        val searchUrls = searches.flatMap { term ->
            (1..pages).map { page ->
                "https://slickdeals.net/newsearch.php?q=${term.urlEncode()}" +
                    "&searcharea=deals&searchin=first&rss=1&page=$page"
            }
        }
        return (fixed + searchUrls).distinct()
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, java.nio.charset.StandardCharsets.UTF_8)

    internal data class Parsed(val deal: RawDeal, val merchant: String?, val resolvedVia: String)

    internal fun countItems(xml: String): Int =
        Jsoup.parse(xml, "", Parser.xmlParser()).select("item").size

    internal fun parse(xml: String): List<Parsed> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null

            val encoded = item.getElementsByTag("content:encoded").firstOrNull()?.text().orEmpty()
            val fragment = Jsoup.parse(encoded)
            val rawText = fragment.text()
            // Read the score BEFORE normalizeBody, which strips the "Thumb Score: +N"
            // prefix so it cannot be mistaken for the merchant name.
            val communityScore = extractThumbScore(rawText)
            val postedAt = parsePubDate(item.selectFirst("pubDate")?.text())
            val bodyText = normalizeBody(rawText)

            val (merchant, via) = resolveMerchant(bodyText, fragment)
            val asin = fragment.select("[data-aps-asin]").firstOrNull()?.attr("data-aps-asin")
                ?.takeIf { it.matches(ASIN_RE) }
            val price = extractPrice(bodyText) ?: extractPriceFromTitle(title) ?: return@mapNotNull null

            Parsed(
                deal = RawDeal(
                    externalId = threadId(link) ?: return@mapNotNull null,
                    title = title,
                    // The thread URL, not the data-store link: those are
                    // slickdeals.net/click? redirects with expiring tokens.
                    url = stripTracking(link),
                    price = price,
                    currency = "USD",
                    // Slickdeals publishes no list price, so no advertised discount
                    // is available. Promotion for this source relies on
                    // promoteAllNew instead of the discount floor.
                    listPrice = null,
                    merchant = merchant,
                    asin = asin,
                    category = fragment.select("[data-product-forum]").firstOrNull()
                        ?.attr("data-product-forum")?.takeIf { it.isNotBlank() },
                    imageUrl = fragment.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
                    communityScore = communityScore,
                    postedAt = postedAt,
                ),
                merchant = merchant,
                resolvedVia = via,
            )
        }
    }

    /**
     * Determines which retailer is actually selling the item.
     *
     * Priority matters here and was derived from the real feed rather than assumed:
     *
     *  1. **Body text.** Slickdeals' editorial convention is `"<Merchant> has
     *     <product> for <price>"`, with additional retailers introduced as
     *     `"<Other> also has"`. This is the only reliable primary-merchant signal.
     *  2. **First link's `data-store-slug`**, in document order.
     *  3. **ASIN presence**, which only Amazon items carry.
     *
     * Body text has to win, because a deal can name its primary merchant in plain
     * text while linking only a secondary one. A real example: a LEGO deal whose
     * body read "Amazon.com has … Target [target.com] also has …" contained *only*
     * target.com links, so a link-first rule would have discarded a genuine Amazon
     * deal. Conversely `data-outclick-typeofoutclick` is useless for this — it is
     * "Post Content Link" on every link, primary and secondary alike.
     */
    internal fun resolveMerchant(bodyText: String, fragment: org.jsoup.nodes.Document): Pair<String?, String> {
        MERCHANT_HAS_RE.find(bodyText)?.let { match ->
            // Trailing "also" guards against a body that leads with a secondary
            // retailer, e.g. "Target also has ...".
            val phrase = match.groupValues[1].trim().removeSuffix("also").trim()
            val bracketDomain = match.groupValues[2].takeIf { it.isNotBlank() }

            val candidate = when {
                // "BLUETTI via [ aliexpress.com ] has ..." -- the marketplace exists
                // only inside the brackets, so the domain is the merchant.
                phrase.endsWith("via", ignoreCase = true) && bracketDomain != null ->
                    normalizeMerchant(bracketDomain)
                // "KYY-USA via Amazon has ..." -- third-party seller on a marketplace.
                // The marketplace is what determines shipping, not the seller.
                else -> VIA_RE.find(phrase)?.let { normalizeMerchant(it.groupValues[1]) }
                    ?: normalizeMerchant(phrase)
            }
            // A phrase ending in "via" with no domain to resolve is unusable; fall
            // through rather than inventing a merchant like "bluetti-via".
            if (candidate != null && !candidate.endsWith("-via") && candidate != "via") {
                return candidate to "body"
            }
        }

        fragment.select("[data-store-slug]").firstOrNull()
            ?.attr("data-store-slug")?.takeIf { it.isNotBlank() }
            ?.let { return it.lowercase() to "firstlink" }

        if (fragment.select("[data-aps-asin]").isNotEmpty()) return "amazon" to "asin"

        return null to "none"
    }

    /**
     * Strips the leading "Thumb Score: +15" and collapses whitespace.
     *
     * Brackets are deliberately left intact: in the "<Seller> via [ marketplace.com ]
     * has" form the marketplace name appears *only* inside the brackets, so removing
     * them destroys the merchant signal. [MERCHANT_HAS_RE] captures the bracketed
     * domain as its own group instead.
     */
    internal fun normalizeBody(text: String): String =
        text.replace(THUMB_SCORE_RE, "")
            .replace(WHITESPACE_RE, " ")
            .trim()

    private fun normalizeMerchant(raw: String): String? {
        val cleaned = raw.lowercase().trim().trimEnd('.', ',')
            .removeSuffix(".com")
            .replace(WHITESPACE_RE, " ")
            .trim()
        if (cleaned.isEmpty() || cleaned.length > 40) return null
        // Reject phrases that are clearly prose rather than a retailer name.
        if (cleaned.split(" ").size > 4) return null
        return MERCHANT_ALIASES[cleaned] ?: cleaned.replace(" ", "-")
    }

    /** Current deal price, anchored on "for $X" so "$35+ orders" in shipping text can't match. */
    internal fun extractPrice(bodyText: String): BigDecimal? =
        FOR_PRICE_RE.find(bodyText)?.groupValues?.get(1)?.replace(",", "")
            ?.toBigDecimalOrNull()?.asMoney()

    private fun extractPriceFromTitle(title: String): BigDecimal? =
        TITLE_PRICE_RE.findAll(title).lastOrNull()?.groupValues?.get(1)
            ?.replace(",", "")?.toBigDecimalOrNull()?.asMoney()

    /**
     * Parses the RSS `pubDate`, i.e. when Slickdeals published the deal.
     *
     * The feed uses RFC 822 with a **2-digit year** — `"Wed, 01 Oct 25 22:06:29 +0000"` —
     * which [DateTimeFormatter.RFC_1123_DATE_TIME] rejects, since RFC 1123 requires four
     * digits. Both forms are attempted, 2-digit first because that is what Slickdeals
     * actually sends.
     *
     * This matters because deep search pages reach back over 400 days, and
     * `first_seen_at` records only our own discovery time — so without the real posting
     * date a year-old deal is indistinguishable from a fresh one.
     */
    internal fun parsePubDate(raw: String?): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (fmt in listOf(RFC822_TWO_DIGIT_YEAR, DateTimeFormatter.RFC_1123_DATE_TIME)) {
            runCatching { return ZonedDateTime.parse(text, fmt).toInstant() }
        }
        return null
    }

    /**
     * Slickdeals' community vote score, e.g. "Thumb Score: +170".
     *
     * The only deal-quality signal available for this source, because Slickdeals
     * publishes no list price and so no discount percentage can be computed — which is
     * why its rows show an empty discount column. Observed range −14..+384, median ≈20;
     * 0 or below means the community voted the deal down.
     *
     * Must be read from the raw fragment text: [normalizeBody] deliberately strips this
     * prefix so it can't be mistaken for the merchant name.
     */
    internal fun extractThumbScore(rawText: String): Int? =
        THUMB_SCORE_VALUE_RE.find(rawText)?.groupValues?.get(1)?.toIntOrNull()

    /** Slickdeals thread id, stable across price edits — e.g. /f/19913307-lego-… -> 19913307 */
    internal fun threadId(link: String): String? =
        THREAD_ID_RE.find(link)?.groupValues?.get(1)

    private fun stripTracking(link: String): String = link.substringBefore("?utm_")

    companion object {
        private val ASIN_RE = Regex("^[A-Z0-9]{10}$")
        private val THREAD_ID_RE = Regex("/f/(\\d+)")
        private val THUMB_SCORE_RE = Regex("^\\s*Thumb Score:\\s*[+-]?\\d+\\s*")
        private val THUMB_SCORE_VALUE_RE = Regex("Thumb Score:\\s*([+-]?\\d+)")

        /**
         * RFC 822 with a 2-digit year, as Slickdeals sends it. The base year of 2000
         * maps "25" -> 2025 and "26" -> 2026.
         */
        private val RFC822_TWO_DIGIT_YEAR: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("EEE, dd MMM ")
            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            .appendPattern(" HH:mm:ss Z")
            .toFormatter(Locale.ENGLISH)
        private val WHITESPACE_RE = Regex("\\s+")

        /**
         * Matches Slickdeals' leading "<Merchant> [ domain ] has ..." convention.
         * Group 1 is the merchant phrase, group 2 the optional bracketed domain --
         * which is sometimes the only place the marketplace name appears.
         */
        private val MERCHANT_HAS_RE = Regex(
            "^\\s*([A-Za-z0-9 .&'\\-]{2,60}?)\\s*(?:\\[\\s*([A-Za-z0-9.\\-]+)\\s*\\])?\\s+has\\b",
        )
        private val VIA_RE = Regex("\\bvia\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val FOR_PRICE_RE = Regex("\\bfor\\s+\\$([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        private val TITLE_PRICE_RE = Regex("\\$([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

        /** Maps the retailer names as they appear in prose onto Slickdeals' own store slugs. */
        private val MERCHANT_ALIASES = mapOf(
            "amazon" to "amazon",
            "amzn" to "amazon",
            "woot" to "woot",
            "best buy" to "best-buy",
            "bestbuy" to "best-buy",
            "home depot" to "the-home-depot",
            "the home depot" to "the-home-depot",
            "lowes" to "lowes",
            "lowe's" to "lowes",
            "sam's club" to "sams-club",
            "sams club" to "sams-club",
        )
    }
}
