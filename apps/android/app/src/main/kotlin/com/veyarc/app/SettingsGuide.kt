package com.veyarc.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyarc.feature.assistant.AssistantPanel
import com.veyarc.feature.editor.ExpansionScrollAnchor

internal enum class GuideCategory(val title: String) {
    PREREQUISITES("Prerequisites"),
    FEATURES("Features"),
    HOW_IT_WORKS("How it works"),
    RECOMMENDED("Recommended way to use"),
}

@Composable
internal fun SettingsGuideLinks(onOpen: (GuideCategory) -> Unit) {
    GuideCategory.entries.forEach { category ->
        Surface(onClick = { onOpen(category) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    when (category) {
                        GuideCategory.PREREQUISITES -> Icons.Outlined.CheckCircleOutline
                        GuideCategory.FEATURES -> Icons.Outlined.PlayCircleOutline
                        GuideCategory.HOW_IT_WORKS -> Icons.Outlined.Settings
                        GuideCategory.RECOMMENDED -> Icons.Outlined.Lightbulb
                    },
                    null,
                    Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(category.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SettingsGuide(category: GuideCategory, onDismiss: () -> Unit, onShizukuSetup: () -> Unit, onNavigate: (String) -> Unit) {
    val topics = GUIDE_TOPICS.getValue(category)
    var expandedTopic by rememberSaveable(category) { mutableStateOf<String?>(topics.first().first) }
    val list = rememberLazyListState()
    AssistantPanel(category.title, onDismiss, scrollBody = false, bodyState = list) {
        LazyColumn(state = list, overscrollEffect = null, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(topics, key = { it.first }) { (title, bullets) ->
                val expanded = expandedTopic == title
                val section = when {
                    category == GuideCategory.PREREQUISITES && title == "Shizuku" -> "connections" to "Connections and permissions"
                    category == GuideCategory.PREREQUISITES && title == "Edge latch" -> "latch" to "Edge latch"
                    category == GuideCategory.FEATURES && title == "AI connection" -> "assistant" to "AI connection"
                    category == GuideCategory.FEATURES && title == "Import and export" -> "data" to "Local data"
                    else -> null
                }
                val links = when {
                    category == GuideCategory.PREREQUISITES && title == "Before recording or running" -> mapOf("Accessibility" to "connections", "notifications" to "connections", "edge latch" to "latch")
                    category == GuideCategory.FEATURES && title == "Recording" -> mapOf("Home" to "home", "latch" to "latch")
                    category == GuideCategory.FEATURES && title == "Statistics and AI usage" -> mapOf("Home" to "home", "Local data" to "data")
                    category == GuideCategory.HOW_IT_WORKS && title == "Android access" -> mapOf("Accessibility" to "connections", "Shizuku" to "connections")
                    else -> emptyMap()
                }
                Surface(
                    onClick = { expandedTopic = if (expanded) null else title },
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (section == null) 16.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium, color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            section?.let { (target, label) ->
                                IconButton(onClick = { onNavigate(target) }) {
                                    Icon(Icons.Outlined.NorthEast, "Open $label", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        AnimatedVisibility(
                            expanded,
                            enter = expandVertically(tween(140)) + fadeIn(tween(100)),
                            exit = shrinkVertically(tween(120)) + fadeOut(tween(80)),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                bullets.forEach { bullet -> GuideBullet(bullet, links, onNavigate) }
                                if (category == GuideCategory.PREREQUISITES && title == "Shizuku") {
                                    TextButton(onClick = onShizukuSetup, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Shizuku setup instructions") }
                                }
                            }
                        }
                        ExpansionScrollAnchor(expanded)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideBullet(text: String, links: Map<String, String>, onNavigate: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            buildAnnotatedString {
                append(text)
                val labelEnd = text.indexOf(':')
                if (labelEnd in 1..32) {
                    addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, labelEnd + 1)
                }
                links.forEach { (label, target) ->
                    var start = text.indexOf(label)
                    while (start >= 0) {
                        addLink(LinkAnnotation.Clickable(target, TextLinkStyles(SpanStyle(color = linkColor))) { onNavigate(target) }, start, start + label.length)
                        start = text.indexOf(label, start + label.length)
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val GUIDE_TOPICS = mapOf(
    GuideCategory.PREREQUISITES to listOf(
        "Shizuku" to listOf(
            "A helper that lets Veyarc record exact touches in other apps.",
            "Install Shizuku through Connections and permissions → Shizuku.",
            "In phone Settings, find Build number and tap it 7 times to enable Developer options.",
            "On Wi-Fi, enable USB debugging and Wireless debugging in Developer options.",
            "Shizuku → Pairing. In Wireless debugging, tap Pair device with pairing code.",
            "Enter the code in Shizuku's notification, then tap Start.",
            "Veyarc → Connections and permissions → Request permission.",
            "Start Shizuku again after rebooting your phone.",
        ),
        "Edge latch" to listOf(
            "Enable in Settings. Tap to open; drag to move.",
            "Record: start recording. Stop and save: finish it.",
            "Play: use Last saved, Last played, Favorite, or Pick directly, as configured.",
            "Library (gear): open workflows. Stop (×): end a paused run.",
            "Pause / Continue recording: pause to edit, then carry on. Add recording block uses the current screen.",
            "Hide (×): turn off the latch and screen sharing. Enable the latch again in Settings.",
            "Opening the latch during a run stops that run.",
        ),
        "Before recording or running" to listOf(
            "Enable Accessibility, notifications, and the edge latch.",
            "Recording needs Android 13+ and a supported touch device.",
            "Match the workflow's orientation. The first run that needs images asks for screen sharing.",
        ),
    ),
    GuideCategory.FEATURES to listOf(
        "Recording" to listOf(
            "Start from Home or the latch. Use apps normally; Pause skips unwanted actions.",
            "While paused, Add from screen creates image or color checks, including conditions for the current nest.",
            "Record here chooses where new actions go. Continue recording returns to the app and starts there.",
            "Finish creates an editable workflow. App switches become Launch app blocks; system screens are skipped.",
        ),
        "Execution blocks" to listOf(
            "Tap point / Tap region: tap a spot or area.",
            "Long press / Swipe path: hold or follow a path.",
            "Launch app: open an allowed app.",
            "Wait / Wait until: delay, or wait for a condition.",
            "If: choose a branch.",
            "Repeat / While: repeat a count, or while a condition holds.",
            "Leave loop: exit the loop. Next repeat: skip to its next turn.",
            "Stage boundary / Go to: group steps or jump, including outside nests.",
            "Stop: end the run.",
        ),
        "Conditions" to listOf(
            "Check colors, images, screen state, variables, current app, or orientation.",
            "All: every check passes. Any: one passes. Not: reverse a check.",
            "Test image or color checks against a background before running. Results show the score or closest color.",
        ),
        "Variables" to listOf(
            "Store a Number, Text, or Boolean. Set variable changes its value during a run.",
            "Use values in conditions, repeat counts, or timings.",
            "For Text, choose Choices. Use + to add an option, double-tap to edit it, and tap to set the default.",
            "Review workflow lets you change starting values for one run without changing saved defaults.",
        ),
        "Saved blocks" to listOf(
            "Save a block range as Linked to follow its edits, or Copy for an independent sequence.",
            "Run saved blocks calls a sequence. Its Play button tests that sequence alone.",
            "Inputs pass values in; outputs return results to the calling workflow.",
            "Finish sequence returns to the block after the call.",
        ),
        "Image search and captures" to listOf(
            "Capture image saves a temporary screenshot or crop for this run.",
            "Use a captured crop as an image target; a full-screen capture is for screen-state checks.",
            "Find image stores whether it matched, its score, and position for later blocks.",
        ),
        "Image templates" to listOf(
            "Crop a screenshot from the same device and screen size.",
            "Crops are matched; saved backgrounds help you edit blocks. Crop / BG labels show step usage.",
            "Choose a search area. Higher thresholds are stricter.",
            "Edit an image, then replace it or save a copy. Resizing a background removes it from blocks using it.",
        ),
        "Allowed apps" to listOf(
            "Only listed apps receive workflow touches, keeping other apps safe.",
            "Tap to open. Hold and drag to reorder; drop on red to remove.",
        ),
        "AI connection" to listOf(
            "Add a provider or custom connection, your API key, and models.",
            "Pick default model and effort. Test checks connections; Enable AI lasts for the session.",
            "Effort controls thinking depth when the model supports it.",
        ),
        "AI chat" to listOf(
            "Ask AI to explain, create, or edit workflows. Choose model and effort in chat.",
            "Replies stream; you can leave while waiting. Thinking appears when the model shares it.",
            "Reply icons copy, branch, or resend; Stop cancels.",
            "History: rename, pin, search, select. Titles also generate automatically.",
            "Workflow context shows shared data. Chat storage clears this workflow's history or all chats.",
        ),
        "Images and files in chat" to listOf(
            "+ adds camera, photos, or files. Tap images to crop, rotate, draw, or erase; undo/redo fixes edits.",
            "Preview PDF, Markdown, text, CSV, JSON/XML, or DOCX text. Swipe PDF pages.",
            "Image support depends on the model. AI can add crops and backgrounds.",
        ),
        "Review AI changes" to listOf(
            "Open Changes to review edits. Apply and save creates a revision; Dismiss keeps the workflow as it is.",
            "Check the saved revision before running. AI cannot start a run for you.",
        ),
        "Manage workflows and runs" to listOf(
            "Find items with folders, search, and filters. Favorite workflows for quick access.",
            "Hold for actions; Select manages several together. Name, organize, or delete items.",
        ),
        "Run details" to listOf(
            "See activity grouped by stage, loop, and sequence. Expand earlier activity for more.",
            "Checks show image scores or color differences. Variable changes show the values used.",
            "Tap a step to open its block in the saved workflow revision.",
        ),
        "Saving and validation" to listOf(
            "Save creates a revision. Revision opens earlier versions.",
            "Tap a validation issue to find the block. Fix errors before running.",
            "Ready to run? links to needed settings.",
        ),
        "Copy and paste blocks" to listOf(
            "Select blocks, Copy, then paste into this workflow or another. Tap Select again to pick a range.",
            "Images come too; identical images are reused.",
        ),
        "Import and export" to listOf(
            "Local data imports/exports workflows and finished runs.",
            "Workflow exports include a revision and images. Run exports contain history only.",
            "Keep exports outside Veyarc; they aren't a full app backup.",
        ),
        "Statistics and AI usage" to listOf(
            "Home shows runs and usage by provider/model: tokens, estimated cost, or replies.",
            "Tap a cost rate to edit. Estimates aren't a bill; cancelled requests may still cost.",
            "Local data → Export statistics saves aggregate data as CSV.",
        ),
    ),
    GuideCategory.HOW_IT_WORKS to listOf(
        "Android access" to listOf(
            "Accessibility sends gestures and checks the current app.",
            "Shizuku reads touch events for recording.",
            "Screen capture (MediaProjection) provides temporary image-check frames, not a saved video.",
            "Sharing stays available between runs. Lock the phone, turn off the latch, or use Stop capture to end it.",
        ),
        "From recording to a run" to listOf(
            "Touches, waits, and app switches become blocks.",
            "Runs follow blocks; conditions choose branches and loops.",
            "Image score below threshold = no match.",
        ),
        "Local data and AI" to listOf(
            "Your saved work and keys stay on this device.",
            "Messages, workflow context, and attachments go to your chosen provider.",
            "Deleting local history doesn't erase provider records.",
        ),
    ),
    GuideCategory.RECOMMENDED to listOf(
        "Record, edit, test" to listOf(
            "Record a short example. Pause to add checks, or finish and edit later.",
            "Trim extra taps/waits. Check Allowed apps and orientation.",
            "Add image checks, If, While, or Repeat as needed.",
            "Save and test a short workflow before making it larger.",
        ),
        "Let AI handle the logic" to listOf(
            "Start with a recording; describe the changes you want.",
            "Example: \"Repeat these steps while the image is visible.\"",
            "Attach screenshots when useful. Review Changes, choose Apply and save, then test.",
        ),
    ),
)
