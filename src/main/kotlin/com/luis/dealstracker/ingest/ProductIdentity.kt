package com.luis.dealstracker.ingest

/**
 * Derives a key two listings must share to be treated as the same physical product.
 *
 * This is what makes "the same fridge is 18% cheaper at Homecenter" sayable, and it is the
 * only deal signal that no retailer can manufacture on its own: a discount percentage is a
 * claim about the seller's own past price, whereas a competitor charging more is
 * independent evidence.
 *
 * Titles cannot carry that weight by themselves — the same Mabe fridge is
 * "Nevera MABE No Frost Congelador Superior 247L RMA247FXCG" at one retailer and something
 * else entirely at the next. So identity rests on published codes instead, in two tiers:
 *
 *  1. **GTIN/EAN**, when the source publishes one that passes its check digit.
 *  2. **Brand + model code** lifted from the title, when it does not.
 *
 * Both are strong enough to alert on. Loose title similarity deliberately is not — see
 * `WatchService`, which shows near-matches in the UI but never notifies on them. A wrong
 * "much cheaper elsewhere" claim sends you shopping for the wrong appliance, which is a
 * worse failure than staying quiet.
 */
object ProductIdentity {

    /**
     * Returns [raw] as a canonical GTIN, or null if it is not one.
     *
     * The check digit is the entire point of this function rather than a nicety. Exito
     * publishes real GS1 codes and its own internal references *in the same `ean` field* —
     * `7705191041773` (a Challenger fridge) alongside `14636` and `IZ20000899908`. Treating
     * the latter as identity keys would cluster unrelated products under a shared internal
     * ref, and the resulting "same item, cheaper here" claim would be nonsense. The
     * checksum rejects both without needing to know anything about Exito.
     *
     * Separators are tolerated because feeds format codes inconsistently; anything else
     * non-numeric is a rejection.
     */
    fun validGtin(raw: String?): String? {
        val digits = raw?.filterNot { it == '-' || it == ' ' } ?: return null
        // GTIN-8, UPC-A (12), EAN-13 and GTIN-14 are the only valid widths. A 5-digit
        // internal ref cannot accidentally pass by being padded, because it is never
        // padded to one of these.
        if (digits.length !in setOf(8, 12, 13, 14)) return null
        if (!digits.all { it.isDigit() }) return null

        // GS1 modulo-10: weight the digits right-to-left from the check digit, 3 then 1.
        val body = digits.dropLast(1).reversed()
        val sum = body.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 3 else 1 }.sum()
        val expected = (10 - sum % 10) % 10
        if (expected != digits.last().digitToInt()) return null

        // Left-pad to GTIN-14, which is how GS1 says shorter forms are compared. Exito really
        // does publish both "757638435765" and "0757638435765" for one MABE fridge -- a UPC-12
        // and the same code as an EAN-13 -- and unpadded those are two different keys for one
        // product. Padding cannot break the checksum: the weights are anchored at the right
        // and leading zeros contribute nothing to the sum, which is precisely why GS1 permits
        // it. EAN-8 is a distinct numbering scheme and is left alone.
        return if (digits.length == 8) digits else digits.padStart(14, '0')
    }

    /**
     * Units that a number is attached to, which must never be mistaken for a model code.
     *
     * Without this, "Congelador Superior 247L RMA247FXCG" yields `247L` — the capacity —
     * and two different fridges that happen to share a litre count would cluster as one
     * product. Capacity is the single most common number in an appliance title, so this is
     * the difference between the feature working and quietly producing garbage.
     */
    private val UNIT_SUFFIX =
        Regex("^\\d+(l|lt|lts|litros?|kg|g|mg|ml|cc|cm|mm|m|w|kw|v|hz|btu|pies|ft|in|pulgadas?|oz|pk|un)$")

    /**
     * Pulls a manufacturer model code out of a product title, or null if there isn't one.
     *
     * Two shapes, both taken from real Colombian appliance listings:
     *
     *  - **Mixed letters and digits** — `RMA247FXCG`, `ERB163HB`, `CR290`, `H6164nt27`.
     *  - **Long runs of digits** — Haceb uses bare numbers like `9003541`. Five digits is
     *    the floor, which keeps capacities (`173`, `272`, `421`) and pack counts out.
     *
     * The longest candidate wins, which resolves the common case of a title repeating a
     * truncated form of the code (`"… ERB163HB ERB163H"` → `ERB163HB`).
     */
    fun modelCode(title: String, brand: String? = null): String? {
        val brandFolded = brand?.let { Categorizer.normalize(it) }
        return title.split(Regex("[^\\p{Alnum}]+"))
            .filter { it.length >= 4 }
            .filter { token ->
                val folded = Categorizer.normalize(token)
                // The brand is not a model, and neither is a bare capacity.
                if (folded == brandFolded || UNIT_SUFFIX.matches(folded)) return@filter false
                val hasDigit = token.any { it.isDigit() }
                val hasLetter = token.any { it.isLetter() }
                if (!hasDigit) false else hasLetter || token.length >= 5
            }
            // Longest first, then latest in the title — a model code usually trails the
            // marketing copy, so position breaks a genuine tie sensibly.
            .maxWithOrNull(compareBy({ it.length }, { title.lastIndexOf(it) }))
            ?.uppercase()
    }

    /**
     * The primary cluster key: the GTIN tier when available, else the model tier.
     *
     * Null is the honest answer for most groceries and most Slickdeals rows, and it must
     * never cluster with another null — hence the partial index in `V8__product_identity`
     * and the `IS NOT NULL` guards in the cross-site query.
     */
    fun matchKey(gtin: String?, brand: String?, model: String?): String? =
        validGtin(gtin)?.let { "gtin:$it" } ?: modelKey(brand, model)

    /**
     * The model-tier key, computed **even when a GTIN is available**.
     *
     * Stored alongside [matchKey] so the two tiers can join each other. Colombian retailers
     * disagree about which identifier they publish — Éxito, Carulla and Easy send GS1 EANs
     * while Falabella and Homecenter send none — so keeping only the strongest key per
     * listing means the same fridge carries `gtin:…` at one retailer and `mdl:…` at another
     * and never matches itself. See `V10__model_key`.
     */
    fun modelKey(brand: String?, model: String?): String? {
        if (brand.isNullOrBlank() || model.isNullOrBlank()) return null
        return "mdl:${Categorizer.normalize(brand)}|${Categorizer.normalize(model)}"
    }
}
