package com.veyarc.feature.editor

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Follow user expansion after its 120–140 ms layout animation, never initial composition. */
@Composable
fun ExpansionScrollAnchor(expanded: Boolean) {
    val requester = remember { BringIntoViewRequester() }
    val previous = remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        val opened = expanded && !previous.value
        previous.value = expanded
        if (opened) {
            delay(160)
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(requester))
}
