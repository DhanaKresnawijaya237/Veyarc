package com.veyarc.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyarc.data.storage.AssistantModelPrice
import com.veyarc.data.storage.AssistantProviderCatalog
import com.veyarc.data.storage.AssistantUsagePrices
import com.veyarc.data.storage.AssistantUsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun AssistantUsageCard(rows: List<AssistantUsageSummary>) {
    if (rows.isEmpty()) return
    val context = LocalContext.current
    val prices = remember(context) { AssistantUsagePrices(context) }
    var priceVersion by remember { mutableIntStateOf(0) }
    var rates by remember { mutableStateOf<Map<String, AssistantModelPrice?>>(emptyMap()) }
    var ratesReady by remember { mutableStateOf(false) }
    LaunchedEffect(rows, priceVersion) {
        ratesReady = false
        rates = withContext(Dispatchers.IO) { rows.associate { it.key to prices.price(it.key) } }
        ratesReady = true
    }
    var mode by rememberSaveable { mutableStateOf("Tokens") }
    var editing by remember { mutableStateOf<AssistantUsageSummary?>(null) }
    Surface(Modifier.fillMaxWidth().testTag("ai-usage-card"), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp).animateContentSize(tween(160)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("AI usage", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${usageNumber(rows.sumOf { it.replies })} replies", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Tokens", "Cost", "Replies").forEach { option ->
                    Surface(onClick = { mode = option }, shape = RoundedCornerShape(18.dp), color = if (mode == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(option, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = if (mode == option) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val values = rows.associate { row ->
                row.key to when (mode) {
                    "Cost" -> row.estimate(rates[row.key])?.second
                    "Replies" -> row.replies.toDouble()
                    else -> row.totalTokens?.toDouble()
                }
            }
            val maximum = values.values.filterNotNull().maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
            rows.groupBy { it.providerId }.forEach { (providerId, models) ->
                val providerName = AssistantProviderCatalog.providers.firstOrNull { it.id == providerId }?.name ?: models.first().providerName
                val color = usageColor(providerId)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                        Text(providerName, style = MaterialTheme.typography.titleSmall, color = color)
                    }
                    models.forEach { row ->
                        val modelColor = usageColor(providerId, row.modelId)
                        val fraction by animateFloatAsState(((values[row.key] ?: 0.0) / maximum).toFloat(), tween(180), label = "Usage amount")
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.modelName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (mode) {
                                        "Cost" -> if (!ratesReady) "Loading…" else row.estimate(rates[row.key])?.let(::usageCost) ?: if (row.measuredReplies == 0L) "Unavailable" else "Set rates"
                                        "Replies" -> usageNumber(row.replies)
                                        else -> row.totalTokens?.let(::usageNumber) ?: "Unavailable"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = modelColor,
                                )
                                if (mode == "Cost") IconButton(onClick = { editing = row }, enabled = ratesReady, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Edit, "Rates for $providerName ${row.modelName}", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                            }
                            if (mode == "Tokens") row.input?.let { input -> Text("${usageNumber(input)} in · ${usageNumber(row.output ?: 0)} out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(modelColor.copy(alpha = 0.12f))) {
                                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(modelColor))
                            }
                            if (mode != "Replies" && row.measuredReplies < row.replies) Text("Usage available for ${row.measuredReplies} of ${row.replies} replies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(if (mode == "Cost") "Estimated USD · catalog or custom rates" else "Completed replies only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { row ->
        UsagePriceDialog(row, rates[row.key], prices, onDismiss = { editing = null }, onSaved = {
            priceVersion++
            editing = null
        })
    }
}

@Composable
private fun UsagePriceDialog(row: AssistantUsageSummary, current: AssistantModelPrice?, prices: AssistantUsagePrices, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var input by remember { mutableStateOf(current?.input?.toString().orEmpty()) }
    var output by remember { mutableStateOf(current?.output?.toString().orEmpty()) }
    var cached by remember { mutableStateOf(current?.cached?.toString().orEmpty()) }
    var written by remember { mutableStateOf(current?.cacheWrite?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun rate(text: String) = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val valid = listOf(input, output, cached, written).all { rate(it) != null }
    fun save(reset: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (reset) prices.reset(row.key) else prices.save(row.key, AssistantModelPrice(rate(input)!!, rate(output)!!, rate(cached)!!, rate(written)!!))
                }
                onSaved()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = failure.message ?: "Rates could not be saved."
            } finally {
                saving = false
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(row.modelName) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("USD per 1M tokens", style = MaterialTheme.typography.bodySmall)
            current?.let { Text(it.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            listOf(Triple("Input", input) { next: String -> input = next }, Triple("Output", output) { next: String -> output = next }, Triple("Cached input", cached) { next: String -> cached = next }, Triple("Cache writes", written) { next: String -> written = next }).forEach { (label, value, change) ->
                OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !saving)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = valid && !saving, onClick = { save(false) }) { Text("Save") } }, dismissButton = {
        Row {
            TextButton(enabled = !saving, onClick = { save(true) }) { Text("Reset") }
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        }
    })
}

internal fun usageNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)
internal fun usageCost(value: Pair<Double, Double>): String {
    fun amount(number: Double) = if (number > 0 && number < 0.0001) "< $0.0001" else String.format(Locale.ROOT, "$%.4f", number)
    return if (value.first == value.second) amount(value.first) else "${amount(value.first)}–${amount(value.second)}"
}

internal fun usageColor(providerId: String, modelId: String? = null): Color {
    val base = when (providerId) {
        "deepseek" -> Color(0xFF589DEC)
        "openai" -> Color(0xFF6AC4AC)
        "anthropic" -> Color(0xFFDF9F79)
        "google" -> Color(0xFFBBA0EC)
        "moonshotai", "moonshotai-cn", "kimi-coding" -> Color(0xFFA2B1F8)
        "xai" -> Color(0xFFB9C4D3)
        else -> Color.hsv((providerId.hashCode().toUInt() % 360u).toFloat(), 0.4f, 0.85f)
    }
    if (modelId == null) return base
    if (providerId == "deepseek") return if (modelId.contains("flash")) Color(0xFF83C6F6) else Color(0xFF5C88D8)
    return lerp(base, Color.White, (modelId.hashCode().toUInt() % 5u).toFloat() * 0.075f)
}
