package com.novaflow.cueflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrompterMarkdownTest {
    @Test
    fun formatsMarkdownWithoutPuttingMarkupOrUrlsInTheScript() {
        val rendered = renderPrompterMarkdown("# Title\n\nRead **this** and [this link](https://example.com).")
        assertEquals("Title\n\nRead this and this link.", rendered.display.text)
        assertEquals(rendered.display.text, rendered.spoken)
        assertTrue(rendered.display.spanStyles.size >= 3)
    }

    @Test
    fun numberingDoesNotInterruptMatchesAndOffsetsPointToRenderedWords() {
        val rendered = renderPrompterMarkdown("1. Read **this**\n2. sentence *out loud*")
        assertEquals("1. Read this\n2. sentence out loud", rendered.display.text)
        val end = checkNotNull(ScriptMatcher(rendered.spoken).advance("read this sentence"))
        assertEquals("sentence", rendered.display.text.substring(end - 8, end))
        assertEquals(rendered.display.length, rendered.spoken.length)
    }
}
