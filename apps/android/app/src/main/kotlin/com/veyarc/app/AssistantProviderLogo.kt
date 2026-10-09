package com.veyarc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

// Lobe Icons marks: https://github.com/lobehub/lobe-icons, pinned source and MIT notice in each vector.
// Ant Ling's mark: https://gw.alipayobjects.com/render/p/yuyan/180020010001289651/favicon.ico
// Logos identify their providers; all trademarks belong to their respective owners.
internal fun providerLogoResource(id: String?): Int? = when (id) {
    "deepseek" -> R.drawable.ic_deepseek
    "openai" -> R.drawable.ic_provider_openai
    "anthropic" -> R.drawable.ic_provider_anthropic
    "google" -> R.drawable.ic_provider_google
    "moonshotai", "moonshotai-cn" -> R.drawable.ic_provider_moonshot
    "ant-ling" -> R.drawable.ic_provider_ant_ling
    "azure-openai-responses" -> R.drawable.ic_provider_azure
    "cerebras" -> R.drawable.ic_provider_cerebras
    "cloudflare-ai-gateway", "cloudflare-workers-ai" -> R.drawable.ic_provider_cloudflare
    "fireworks" -> R.drawable.ic_provider_fireworks
    "groq" -> R.drawable.ic_provider_groq
    "huggingface" -> R.drawable.ic_provider_huggingface
    "kimi-coding" -> R.drawable.ic_provider_kimi
    "minimax", "minimax-cn" -> R.drawable.ic_provider_minimax
    "mistral" -> R.drawable.ic_provider_mistral
    "nvidia" -> R.drawable.ic_provider_nvidia
    "opencode", "opencode-go" -> R.drawable.ic_provider_opencode
    "openrouter" -> R.drawable.ic_provider_openrouter
    "qwen-token-plan", "qwen-token-plan-cn" -> R.drawable.ic_provider_qwen
    "together" -> R.drawable.ic_provider_together
    "vercel-ai-gateway" -> R.drawable.ic_provider_vercel
    "xiaomi", "xiaomi-token-plan-ams", "xiaomi-token-plan-cn", "xiaomi-token-plan-sgp" -> R.drawable.ic_provider_xiaomimimo
    "zai", "zai-coding-cn" -> R.drawable.ic_provider_zai
    "xai" -> R.drawable.ic_provider_xai
    else -> null
}

@Composable
internal fun ProviderLogo(id: String?, modifier: Modifier = Modifier) {
    val resource = providerLogoResource(id)
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            if (resource != null) {
                Icon(painterResource(resource), null, Modifier.testTag(if (id == "deepseek") "deepseek-logo" else "provider-logo-$id"), tint = if (id in listOf("deepseek", "ant-ling")) Color.Unspecified else MaterialTheme.colorScheme.onSurface)
            } else {
                Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
