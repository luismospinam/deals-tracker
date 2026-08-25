package com.luis.dealstracker.sources

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal

/**
 * An adapter for one *protocol*, not one site.
 *
 * That distinction is what makes most provider changes config-only: `vtex`
 * serves Exito, Carulla and any other VTEX retailer; `rss-slickdeals` serves any
 * Slickdeals feed; `mercadolibre` serves any ML country site. Only a genuinely
 * new platform needs a new implementation.
 */
interface DealSource {
    /** Protocol key matched against `type` in config/sources.yaml. */
    val type: String

    suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal>
}

/** Raised when a provider is configured but its protocol has no adapter. */
class UnknownProviderTypeException(type: String, known: Set<String>) :
    IllegalStateException("no adapter for provider type '$type'. Known types: ${known.sorted()}")

class SourceRegistry(sources: List<DealSource>) {
    private val byType: Map<String, DealSource> = sources.associateBy { it.type }

    val knownTypes: Set<String> get() = byType.keys

    fun forType(type: String): DealSource =
        byType[type] ?: throw UnknownProviderTypeException(type, byType.keys)

    /**
     * Validates config against the registry up front, so a typo'd `type` fails at
     * boot with a clear message rather than silently producing an empty feed.
     */
    fun validate(providers: List<ProviderConfig>): List<String> =
        providers.filter { it.enabled }
            .filter { it.type !in byType }
            .map { "provider '${it.id}' has unknown type '${it.type}' (known: ${byType.keys.sorted()})" }
}
