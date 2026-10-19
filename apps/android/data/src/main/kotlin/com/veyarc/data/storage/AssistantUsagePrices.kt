package com.veyarc.data.storage

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AssistantModelPrice(
    val input: Double,
    val output: Double,
    val cached: Double = input,
    val cacheWrite: Double = input,
    val maximumMultiplier: Double = 1.0,
    val source: String = "Custom rates",
) {
    fun validate() {
        require(listOf(input, output, cached, cacheWrite).all { it.isFinite() && it >= 0 })
        require(maximumMultiplier.isFinite() && maximumMultiplier >= 1)
    }
}

/** USD per million tokens. Catalog prices are estimates, never account billing information. */
class AssistantUsagePrices(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("assistant_usage_prices", Context.MODE_PRIVATE)
    fun price(key: String): AssistantModelPrice? = preferences.getString(key, null)?.let {
        runCatching { Json.decodeFromString<AssistantModelPrice>(it).also(AssistantModelPrice::validate) }.getOrNull()
    } ?: catalog[key]

    fun save(key: String, price: AssistantModelPrice) {
        price.validate()
        check(preferences.edit().putString(key, Json.encodeToString(AssistantModelPrice.serializer(), price)).commit()) { "Rates could not be saved." }
    }

    fun reset(key: String) {
        check(preferences.edit().remove(key).commit()) { "Rates could not be reset." }
    }

    companion object {
        @Serializable private data class Catalog(val prices: Map<String, AssistantModelPrice>)
        private val catalog: Map<String, AssistantModelPrice> by lazy {
            val text = checkNotNull(AssistantUsagePrices::class.java.getResourceAsStream("/assistant-prices.json")).bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<Catalog>(text).prices.also { prices -> prices.values.forEach(AssistantModelPrice::validate) }
        }
    }
}
