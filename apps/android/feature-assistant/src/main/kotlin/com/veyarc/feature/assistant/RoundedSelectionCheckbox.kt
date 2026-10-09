package com.veyarc.feature.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** A rounded selection indicator; interactive uses retain a full touch target. */
@Composable
fun RoundedSelectionCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, checkedColor: Color = MaterialTheme.colorScheme.primary, checkmarkColor: Color = MaterialTheme.colorScheme.onPrimary) {
    val tint = if (enabled) 1f else 0.38f
    val fill by animateColorAsState(if (checked) checkedColor.copy(alpha = tint) else Color.Transparent, tween(120), label = "selection fill")
    val checkAlpha by animateFloatAsState(if (checked) tint else 0f, tween(90), label = "selection check")
    val control = if (onCheckedChange == null) {
        Modifier.size(20.dp).semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        }
    } else {
        Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, interactionSource = null, indication = null, onValueChange = onCheckedChange)
    }
    Box(modifier.then(control), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.size(20.dp), shape = RoundedCornerShape(6.dp), color = fill, border = if (checked) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = tint))) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, Modifier.size(16.dp).graphicsLayer { alpha = checkAlpha }, tint = checkmarkColor)
            }
        }
    }
}
