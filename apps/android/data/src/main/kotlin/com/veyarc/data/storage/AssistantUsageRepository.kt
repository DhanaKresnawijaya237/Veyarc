package com.veyarc.data.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class AssistantTokenUsage(
    val input: Long,
    val output: Long,
    val cached: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
) {
    fun validate() {
        require(input in 0..1_000_000_000_000 && output in 0..1_000_000_000_000)
        require(cached in 0..input && cacheWrite in 0..(input - cached) && reasoning in 0..output)
    }
}

@Entity(tableName = "assistant_usage", indices = [Index(value = ["providerId", "modelId"])])
internal data class AssistantUsageEntity(
    @PrimaryKey val requestId: String,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val atMs: Long,
    val input: Long?,
    val output: Long?,
    val cached: Long?,
    val cacheWrite: Long?,
    val reasoning: Long?,
)

data class AssistantUsageSummary(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val replies: Long,
    val measuredReplies: Long,
    val input: Long?,
    val output: Long?,
    val cached: Long?,
    val cacheWrite: Long?,
    val reasoning: Long?,
    val firstUsedAtMs: Long,
    val lastUsedAtMs: Long,
) {
    val key: String get() = "$providerId/$modelId"
    val totalTokens: Long? get() = input?.let { it + (output ?: 0) }
    fun estimate(price: AssistantModelPrice?): Pair<Double, Double>? {
        if (price == null || input == null || output == null) return null
        val minimum = (
            (input - (cached ?: 0) - (cacheWrite ?: 0)) * price.input +
                (cached ?: 0) * price.cached + (cacheWrite ?: 0) * price.cacheWrite + output * price.output
            ) / 1_000_000.0
        return minimum to minimum * price.maximumMultiplier
    }
}

@Dao
internal interface AssistantUsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(rows: List<AssistantUsageEntity>)

    @Query(SUMMARY_QUERY)
    fun summaries(): List<AssistantUsageSummary>

    @Query(SUMMARY_QUERY)
    fun observe(): Flow<List<AssistantUsageSummary>>

    companion object {
        const val SUMMARY_QUERY = "SELECT providerId, MAX(providerName) AS providerName, modelId, MAX(modelName) AS modelName, " +
            "COUNT(*) AS replies, COUNT(input) AS measuredReplies, SUM(input) AS input, SUM(output) AS output, " +
            "SUM(cached) AS cached, SUM(cacheWrite) AS cacheWrite, SUM(reasoning) AS reasoning, " +
            "MIN(atMs) AS firstUsedAtMs, MAX(atMs) AS lastUsedAtMs " +
            "FROM assistant_usage GROUP BY providerId, modelId ORDER BY providerName, modelName"
    }
}

/** Completed replies only, independent of chat deletion; no prompts, API keys or attachments. */
class AssistantUsageRepository internal constructor(private val database: VeyarcDatabase) {
    constructor(context: Context) : this(VeyarcDatabase.create(context))

    fun record(requestId: String, providerId: String, providerName: String, modelId: String, modelName: String, atMs: Long, usage: AssistantTokenUsage?) {
        usage?.validate()
        require(requestId.isNotBlank() && requestId.length <= 128 && providerId.isNotBlank() && modelId.isNotBlank())
        database.assistantUsageDao().insert(listOf(AssistantUsageEntity(requestId, providerId, providerName, modelId, modelName, atMs, usage?.input, usage?.output, usage?.cached, usage?.cacheWrite, usage?.reasoning)))
    }

    fun summaries(): List<AssistantUsageSummary> = database.assistantUsageDao().summaries()
    fun observe(): Flow<List<AssistantUsageSummary>> = database.assistantUsageDao().observe()
    fun close() = database.close()
}
