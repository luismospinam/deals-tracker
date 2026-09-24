package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.FxRate
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The USD→COP rate watches compare in: Colombia's official TRM, with a configured fallback.
 *
 * ## Why the TRM
 *
 * It is the rate Colombian banks and card issuers anchor to, it is published every day by the
 * Superintendencia Financiera, and it is served as open data at `datos.gov.co` with no key — so
 * there is no reason to reach for a commercial FX API. Dataset `32sa-8pi3`, one row per day,
 * newest first with `$order=vigenciadesde DESC`.
 *
 * ## Why refreshed on the poll and read from a cache
 *
 * [current] is called while rendering a watch page and while evaluating every watch, so it must
 * never touch the network. [refresh] runs once per poll cycle and only fetches when the cached
 * rate is not from today, which is one request a day.
 *
 * A failed fetch keeps whatever rate was last good, and before the first success that is the
 * configured fallback. Either way the rate says where it came from (see [FxRate.source]), so a
 * converted comparison resting on the fallback is visible as such rather than passing as live.
 */
class ExchangeRates(
    private val http: PoliteHttp?,
    private val fallbackCopPerUsd: BigDecimal,
    private val url: String = TRM_URL,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    @Volatile
    private var cached: FxRate? = null

    fun current(): FxRate = cached ?: FxRate(fallbackCopPerUsd, "config")

    /** Fetches today's TRM unless it is already cached. Never throws. */
    suspend fun refresh() {
        val have = cached
        // The TRM published today may be dated tomorrow (it is set the business day before it
        // applies), so "not older than today" rather than "exactly today".
        if (have?.asOf != null && !have.asOf.isBefore(today())) return
        val client = http ?: return

        val rate = runCatching { client.get(url).body?.let(::parseTrm) }
            .onFailure { println("[fx] TRM fetch failed: ${it.message}") }
            .getOrNull()
        if (rate == null) {
            println("[fx] no TRM available; comparing at ${current().describe()}")
            return
        }
        if (rate != have) println("[fx] using ${rate.describe()}")
        cached = rate
    }

    companion object {
        const val TRM_URL =
            "https://www.datos.gov.co/resource/32sa-8pi3.json?\$order=vigenciadesde%20DESC&\$limit=1"

        /**
         * The newest row of the dataset, or null when the body is not what it should be.
         *
         * Rejects implausible values rather than trusting them. A rate is multiplied into every
         * USD price on a watch, so a parse that yields 3.26 or 32,643 would silently move every
         * Amazon listing by three orders of magnitude — worse than falling back.
         */
        internal fun parseTrm(body: String): FxRate? = runCatching {
            val row = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
            if (row["unidad"]?.jsonPrimitive?.content != "COP") return null
            val value = BigDecimal(row["valor"]!!.jsonPrimitive.content)
            if (value < BigDecimal(1_000) || value > BigDecimal(10_000)) return null
            val day = row["vigenciadesde"]?.jsonPrimitive?.content?.take(10)?.let(LocalDate::parse)
            FxRate(value, "TRM", day)
        }.getOrNull()
    }
}
