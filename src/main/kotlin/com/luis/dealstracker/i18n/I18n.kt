package com.luis.dealstracker.i18n

/**
 * The two languages the app's own text is written in.
 *
 * Only the app's text is translated. Product titles, brands, store names and anything else a
 * retailer wrote are shown exactly as published: that is what you will see on their site, and a
 * machine-translated title would no longer match the listing it came from.
 */
enum class Lang(val code: String) {
    EN("en"),
    ES("es");

    companion object {
        /** `es`, `es-CO`, `ES` all give [ES]; anything unsupported gives null. */
        fun fromCode(code: String?): Lang? {
            val primary = code?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
            return entries.firstOrNull { it.code == primary }
        }

        /**
         * The first supported language in an `Accept-Language` header, in the browser's own order
         * of preference -- `fr-FR, es;q=0.8, en;q=0.5` gives [ES], since French is not offered.
         */
        fun fromAcceptLanguage(header: String?): Lang? =
            header.orEmpty().split(',')
                .mapNotNull { part ->
                    val pieces = part.trim().split(';')
                    val q = pieces.drop(1).map { it.trim() }.firstOrNull { it.startsWith("q=") }
                        ?.removePrefix("q=")?.toDoubleOrNull() ?: 1.0
                    fromCode(pieces[0])?.let { it to q }
                }
                .filter { it.second > 0 }
                // Stable, so equal weights keep the header's order.
                .sortedByDescending { it.second }
                .firstOrNull()?.first
    }
}

/**
 * Which language is being rendered right now.
 *
 * A thread-local rather than a parameter on every view function. Rendering is synchronous --
 * kotlinx.html builders never suspend -- so a request sets the language around the build with
 * [with] and every `tr` call inside it sees that value, without threading a `lang` argument
 * through some fifty functions. Anything outside a [with] block, which includes every test that
 * renders a view directly, gets English: the language the tests' expected strings are written in.
 *
 * The one rule: set it around a *synchronous* build. A coroutine can resume on another thread,
 * so never call [with] around a suspending block and expect it to hold.
 */
object I18n {
    private val current = ThreadLocal<Lang>()

    val lang: Lang get() = current.get() ?: Lang.EN

    fun <T> with(lang: Lang, block: () -> T): T {
        val previous = current.get()
        current.set(lang)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

/**
 * The app's text in the active language: `+tr("Apply", "Aplicar")`.
 *
 * Both languages sit side by side at the call site, so the code still reads as the page does and a
 * string cannot be added in one language only. Values are interpolated into each variant, since
 * word order differs: `tr("$n rows", "$n filas")`.
 */
fun tr(en: String, es: String): String = if (I18n.lang == Lang.ES) es else en

/**
 * Display names for the categories in config/categories.yaml.
 *
 * The English name is what is stored in `deals.category_group` and what the Category filter
 * submits, so it stays the value and only the label changes. A name added to the YAML and not here
 * shows in English rather than blank.
 */
private val CATEGORY_ES = mapOf(
    "Gaming" to "Videojuegos",
    "Computers" to "Computadores",
    "Phones & Wearables" to "Celulares y wearables",
    "Books & Media" to "Libros y medios",
    "TV & Video" to "TV y video",
    "Audio" to "Audio",
    "Appliances" to "Electrodomésticos",
    "Beauty & Health" to "Belleza y salud",
    "Groceries" to "Mercado",
    "Toys & Baby" to "Juguetes y bebés",
    "Fashion" to "Moda",
    "Bathroom & Plumbing" to "Baño y plomería",
    "Tools & Outdoor" to "Herramientas y exterior",
    "Home & Kitchen" to "Hogar y cocina",
    "Other" to "Otros",
)

fun categoryLabel(name: String): String = tr(name, CATEGORY_ES[name] ?: name)

/**
 * Display names for the grocery aisles, which config/sources.yaml maps each store's own taxonomy
 * onto. Same rule as [categoryLabel]: the English name is the stored value and the `group` query
 * parameter, and an unknown one is shown as it is.
 */
private val AISLE_ES = mapOf(
    "Pantry" to "Despensa",
    "Snacks & sweets" to "Snacks y dulces",
    "Dairy & eggs" to "Lácteos y huevos",
    "Drinks" to "Bebidas",
    "Bakery" to "Panadería",
    "Meat & fish" to "Carnes y pescados",
    "Fruit & veg" to "Frutas y verduras",
    "Deli" to "Charcutería",
    "Frozen" to "Congelados",
    "Prepared" to "Comidas preparadas",
    "Baby food" to "Comida para bebé",
)

fun aisleLabel(name: String): String = tr(name, AISLE_ES[name] ?: name)

/** [tr] for code that runs outside a render, where the language is known but not the thread's. */
fun Lang.tr(en: String, es: String): String = if (this == Lang.ES) es else en

/**
 * The language for text that has no browser to ask: the watch alerts and the "Amazon needs a
 * human" banner, both sent by the poller rather than in answer to a request.
 *
 * The last language CHOSEN with the masthead switch, or, until one is chosen, the one the browser
 * last asked for. A choice is kept over later browsing, so opening the app on a phone set to
 * English does not flip the Mac's Spanish banners back.
 *
 * Persisted through [store] so a restart keeps it; in memory otherwise, since it is read on every
 * alert and written only when it changes.
 */
object AppLanguage {
    @Volatile
    var current: Lang = Lang.EN
        private set

    @Volatile
    private var chosen: Boolean = false
    private var store: ((Lang, Boolean) -> Unit)? = null

    fun init(saved: Lang?, savedChosen: Boolean, persist: (Lang, Boolean) -> Unit) {
        saved?.let { current = it }
        chosen = saved != null && savedChosen
        store = persist
    }

    /** True when [lang] would change what is stored, so the caller can skip a DB round-trip. */
    fun wouldChange(lang: Lang, isChoice: Boolean): Boolean =
        if (isChoice) lang != current || !chosen else !chosen && lang != current

    fun remember(lang: Lang, isChoice: Boolean) {
        if (!wouldChange(lang, isChoice)) return
        current = lang
        if (isChoice) chosen = true
        runCatching { store?.invoke(lang, chosen) }
            .onFailure { println("[i18n] could not save the language: ${it.message}") }
    }
}
