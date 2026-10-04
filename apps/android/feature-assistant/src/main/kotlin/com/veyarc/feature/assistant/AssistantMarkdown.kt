package com.veyarc.feature.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MarkdownText

private val markdownParser = Parser.builder().build()
private fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()

/** Native text only. HTML stays literal and images show alt text without fetching remote content. */
@Composable
internal fun AssistantMarkdown(source: String, modifier: Modifier = Modifier) {
    val document = remember(source) { markdownParser.parse(source) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        document.children().forEach { MarkdownBlock(it) }
    }
}

/** File previews compose only visible top-level blocks, including large Markdown documents. */
@Composable
internal fun MarkdownAttachmentPreview(source: String) {
    val parsed by produceState<List<Node>?>(null, source) {
        value = withContext(Dispatchers.Default) { markdownParser.parse(source).children() }
    }
    val blocks = parsed ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().testTag("markdown-file-preview"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(blocks) { MarkdownBlock(it) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, depth: Int = 0) {
    // ponytail: render nesting beyond 24 levels as plain text; expand only if real replies need it.
    if (depth >= 24) {
        Text(node.plainText())
        return
    }
    when (node) {
        is Heading -> Text(
            inlineMarkdown(node),
            style = when (node.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
        )

        is Paragraph -> Text(inlineMarkdown(node), style = MaterialTheme.typography.bodyLarge)

        is FencedCodeBlock -> CodeBlock(node.literal, node.info)

        is IndentedCodeBlock -> CodeBlock(node.literal, "")

        is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyLarge)

        is ThematicBreak -> HorizontalDivider()

        is BlockQuote -> Row(Modifier.fillMaxWidth()) {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.children().forEach { MarkdownBlock(it, depth + 1) }
            }
        }

        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children().forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (node is OrderedList) "${node.markerStartNumber + index}." else "•", style = MaterialTheme.typography.bodyLarge)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.children().forEach { MarkdownBlock(it, depth + 1) }
                    }
                }
            }
        }

        else -> node.children().forEach { MarkdownBlock(it, depth + 1) }
    }
}

@Composable
private fun CodeBlock(value: String, language: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (language.isNotBlank()) Text(language.take(40), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.trimEnd('\n'), Modifier.horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

internal fun inlineMarkdown(node: Node): AnnotatedString = buildAnnotatedString {
    fun visit(item: Node, depth: Int) {
        if (depth >= 24) {
            append(item.plainText())
            return
        }
        fun children() = item.children().forEach { visit(it, depth + 1) }
        when (item) {
            is MarkdownText -> append(item.literal)

            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(item.literal) }

            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }

            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }

            is HardLineBreak -> append('\n')

            is SoftLineBreak -> append(' ')

            is HtmlInline -> append(item.literal)

            is Link -> {
                val url = runCatching { java.net.URI(item.destination) }.getOrNull()
                if (url?.scheme in listOf("https", "http") && !url?.host.isNullOrBlank()) {
                    pushLink(LinkAnnotation.Url(item.destination))
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { children() }
                    pop()
                } else {
                    children()
                }
            }

            else -> children()
        }
    }
    node.children().forEach { visit(it, 0) }
}

private fun Node.plainText(): String = buildString {
    val pending = java.util.ArrayDeque<Node>()
    pending.add(this@plainText)
    while (pending.isNotEmpty()) {
        when (val item = pending.removeLast()) {
            is MarkdownText -> append(item.literal)
            is Code -> append(item.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            else -> item.children().asReversed().forEach(pending::add)
        }
    }
}
