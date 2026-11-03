package com.luis.dealstracker.i18n

/**
 * A watch alert's evidence in [lang].
 *
 * The ledger stores the sentence `WatchEvaluator` wrote, in English, because it is the audit trail:
 * what the rule saw, frozen when it fired. Translating at render rather than at write keeps that
 * record stable, translates every alert already stored, and lets the language switch apply to it like
 * to the rest of the page. Its shapes are few and fixed (one per rule, plus the currency note), so they
 * are matched exactly; anything else is shown as stored, untranslated, rather than mistranslated.
 */
fun localizeEvidence(evidence: String, lang: Lang = I18n.lang): String {
    if (lang == Lang.EN) return evidence
    val (body, note) = evidence.split(CONVERTED, limit = 2).let { it[0] to it.getOrNull(1) }
    val main = CROSS.matchEntire(body)?.destructured?.let { (pct, rival, price, key) ->
        "$pct% más barato que $rival a $price (mismo $key)"
    } ?: LOW.matchEntire(body)?.destructured?.let { (floor, days, holder, saving) ->
        "superó el mínimo de $days días de $floor de $holder, por $saving"
    } ?: CARD.matchEntire(body)?.groupValues?.let { g ->
        val (card, price, floor, days, kind) = g.drop(1)
        val (holder, saving, cash) = g.drop(6)
        val kindEs = if (kind == "cash") "de contado" else "con tarjeta"
        val cardEs = when (card) { "Store card" -> "Tarjeta de la tienda"; "Card" -> "Tarjeta"; else -> card }
        "$cardEs $price superó el mínimo de $days días de $floor ($kindEs, $holder) por $saving; de contado $cash"
    } ?: return evidence
    val noteEs = note?.let { n ->
        val converted = NOTE.matchEntire(n)?.destructured?.let { (what, rate) -> "convertido: $what a $rate" } ?: n
        " — $converted"
    }.orEmpty()
    return main + noteEs
}

private const val CONVERTED = " — converted: "
private val CROSS = Regex("""^([\d.]+)% cheaper than (\S+) at (\S+) \(same (.+)\)$""")
private val LOW = Regex("""^beat the (\S+) (\d+)-day floor held by (\S+), by (\S+)$""")
private val CARD = Regex("""^(.+?) (\S+) beat the (\S+) (\d+)-day floor \((cash|with card), (\S+)\) by (\S+); cash (\S+)$""")
private val NOTE = Regex("""^(.+) at (.+)$""")
