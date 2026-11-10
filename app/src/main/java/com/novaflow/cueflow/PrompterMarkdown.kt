package com.novaflow.cueflow

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
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
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

internal data class PrompterText(val display: AnnotatedString, val spoken: String)

private val markdownParser = Parser.builder().build()

internal fun renderPrompterMarkdown(markdown: String): PrompterText =
    MarkdownPrompter().render(markdownParser.parse(markdown))

private class MarkdownPrompter {
    private val text = AnnotatedString.Builder()
    private val silent = mutableListOf<IntRange>()
    private val monospace = SpanStyle(fontFamily = FontFamily.Monospace)
    private var trailingBreaks = 0

    fun render(document: Node): PrompterText {
        children(document, 0)
        val full = text.toAnnotatedString()
        val display = full.subSequence(0, full.text.trimEnd().length)
        val spoken = display.text.toCharArray()
        for (range in silent) {
            for (index in range.first..minOf(range.last, spoken.lastIndex)) {
                spoken[index] = ' '
            }
        }
        return PrompterText(display, spoken.concatToString())
    }

    private fun children(node: Node, depth: Int) {
        var child = node.firstChild
        while (child != null) {
            visit(child, depth)
            child = child.next
        }
    }

    private fun visit(node: Node, depth: Int) {
        when (node) {
            is Text -> append(node.literal)
            is HardLineBreak, is SoftLineBreak -> append("\n")
            is Heading -> {
                text.withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.16.em)) { children(node, depth) }
                separate(2)
            }
            is Paragraph -> {
                children(node, depth)
                separate(if (node.parent is ListItem) 1 else 2)
            }
            is StrongEmphasis -> text.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(node, depth) }
            is Emphasis -> text.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(node, depth) }
            is Link -> text.withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { children(node, depth) }
            is Code -> text.withStyle(monospace) { this@MarkdownPrompter.append(node.literal) }
            is FencedCodeBlock -> codeBlock(node.literal)
            is IndentedCodeBlock -> codeBlock(node.literal)
            is BulletList -> list(node, depth, null)
            is OrderedList -> list(node, depth, node.markerStartNumber ?: 1)
            is BlockQuote -> {
                text.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(node, depth) }
                separate(2)
            }
            is ThematicBreak -> {
                separate(1)
                append("―")
                separate(2)
            }
            is HtmlBlock, is HtmlInline -> Unit
            else -> children(node, depth)
        }
    }

    private fun list(list: Node, depth: Int, firstNumber: Int?) {
        separate(1)
        var number = firstNumber
        var item = list.firstChild
        while (item != null) {
            val start = text.length
            append("  ".repeat(depth.coerceAtMost(8)))
            append(if (number == null) "• " else "$number. ")
            silent += start until text.length
            children(item, depth + 1)
            separate(1)
            number = number?.plus(1)
            item = item.next
        }
        if (depth == 0) separate(2)
    }

    private fun codeBlock(code: String) {
        text.withStyle(monospace) { this@MarkdownPrompter.append(code.trimEnd()) }
        separate(2)
    }

    private fun separate(lines: Int) {
        if (text.length > 0 && trailingBreaks < lines) append("\n".repeat(lines - trailingBreaks))
    }

    private fun append(value: String) {
        if (value.isEmpty()) return
        text.append(value)
        val breaks = value.length - value.trimEnd('\n').length
        trailingBreaks = if (breaks == value.length) trailingBreaks + breaks else breaks
    }
}
