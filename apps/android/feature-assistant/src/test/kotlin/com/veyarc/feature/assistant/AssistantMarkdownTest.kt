package com.veyarc.feature.assistant

import androidx.compose.ui.text.font.FontWeight
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMarkdownTest {
    @Test
    fun formatsNestedEmphasisCodeAndLinksWithoutExecutingHtmlOrFetchingImages() {
        val node = Parser.builder().build().parse("**Review _first_** and `Wait`. [Docs](https://example.com) ![Image](https://example.com/private.png) <b>literal</b>").firstChild
        val text = inlineMarkdown(node)
        assertEquals("Review first and Wait. Docs Image <b>literal</b>", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
        val unsafe = inlineMarkdown(Parser.builder().build().parse("[Open](javascript:alert%281%29) [App](intent:launch)").firstChild)
        assertTrue(unsafe.getLinkAnnotations(0, unsafe.length).isEmpty())
        assertEquals("Open App", unsafe.text)
    }
}
