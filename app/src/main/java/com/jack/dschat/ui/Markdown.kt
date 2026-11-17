package com.jack.dschat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 模型回复里常见的 Markdown：标题、列表、引用、代码块、表格、分隔线，以及粗体、斜体、行内代码、链接 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    /** marker 是「•」或「1.」，depth 是缩进层级 */
    data class Item(val marker: String, val text: String, val depth: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
}

private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")
private val bulletRe = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val orderedRe = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val ruleRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val tableSepRe = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

fun parseMarkdown(src: String): List<Block> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) blocks += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                // 流式输出时代码块可能还没闭合，那就一直到结尾
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += Block.Code(lang, code.toString())
            }
            line.isBlank() -> flush()
            headingRe.matches(trimmed) -> {
                flush()
                val m = headingRe.find(trimmed)!!
                blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
            }
            ruleRe.matches(line) && para.isEmpty() -> { flush(); blocks += Block.Rule }
            trimmed.startsWith("|") && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) -> {
                flush()
                val rows = mutableListOf(cells(line))
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i]); i++ }
                blocks += Block.Table(rows)
                continue
            }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                blocks += Block.Item("•", m.groupValues[2], m.groupValues[1].length / 2)
            }
            orderedRe.matches(line) -> {
                flush()
                val m = orderedRe.find(line)!!
                blocks += Block.Item(m.groupValues[2] + ".", m.groupValues[3], m.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> {
                flush()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trimStart().removePrefix(">").trimStart())
                    i++
                }
                blocks += Block.Quote(quote.toString())
                continue
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trim())
            }
        }
        i++
    }
    flush()
    return blocks
}

private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** 行内样式：**粗体**、*斜体*、`代码`、[文字](链接)、~~删除线~~ */
fun inline(text: String, code: SpanStyle, link: SpanStyle): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("`") && rest.indexOf('`', 1) > 0 -> {
                val end = rest.indexOf('`', 1)
                withStyle(code) { append(rest.substring(1, end)) }
                i += end + 1
            }
            (rest.startsWith("**") || rest.startsWith("__")) && rest.indexOf(rest.take(2), 2) > 2 -> {
                val end = rest.indexOf(rest.take(2), 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(rest.substring(2, end), code, link)) }
                i += end + 2
            }
            rest.startsWith("~~") && rest.indexOf("~~", 2) > 2 -> {
                val end = rest.indexOf("~~", 2)
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(rest.substring(2, end)) }
                i += end + 2
            }
            rest.startsWith("*") && !rest.startsWith("* ") && rest.indexOf('*', 1) > 1 -> {
                val end = rest.indexOf('*', 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                i += end + 1
            }
            rest.startsWith("[") -> {
                val m = Regex("""^\[([^\]]+)]\(([^)\s]+)\)""").find(rest)
                if (m != null) {
                    withLink(LinkAnnotation.Url(m.groupValues[2], TextLinkStyles(link))) { append(m.groupValues[1]) }
                    i += m.value.length
                } else { append('['); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.group, fontSize = 14.sp)
    val link = SpanStyle(color = colors.accent)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Heading -> Text(
                    inline(block.text, code, link), color = colors.text, fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 21.sp; 2 -> 19.sp; else -> 17.sp }, lineHeight = 28.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Paragraph -> Text(inline(block.text, code, link), color = colors.text, fontSize = 16.sp, lineHeight = 26.sp)
                is Block.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(block.marker, color = colors.sub, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.width(if (block.marker == "•") 16.dp else 24.dp))
                    Text(inline(block.text, code, link), color = colors.text, fontSize = 16.sp, lineHeight = 26.sp)
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.border))
                    Text(inline(block.text, code, link), color = colors.sub, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.padding(start = 12.dp))
                }
                is Block.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.group)) {
                    if (block.lang.isNotEmpty()) Text(block.lang, color = colors.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
                    Text(
                        block.code, color = colors.text, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace, softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                    )
                }
                is Block.Table -> Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(colors.group).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                ) {
                    block.rows.forEachIndexed { r, row ->
                        Row {
                            row.forEach { cell ->
                                Text(
                                    inline(cell, code, link), color = colors.text, fontSize = 14.sp, lineHeight = 21.sp,
                                    fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width(140.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            }
        }
    }
}
