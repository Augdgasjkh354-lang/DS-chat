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
import androidx.compose.ui.unit.em
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import kotlin.math.min
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import ru.noties.jlatexmath.JLatexMathDrawable

/** 模型回复里常见的 Markdown：标题、列表、引用、代码块、表格、分隔线，以及粗体、斜体、行内代码、链接 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    /** marker 是「•」或「1.」，depth 是缩进层级 */
    data class Item(val marker: String, val text: String, val depth: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    /** aligns：每列的对齐，'l' 左、'c' 中、'r' 右（来自分隔行的冒号） */
    data class Table(val rows: List<List<String>>, val aligns: List<Char> = emptyList()) : Block
    data object Rule : Block
    /** 单独占一段的公式：$$…$$ 或 \[…\] */
    data class Math(val tex: String) : Block
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
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val code = StringBuilder()
                i++
                // 流式输出时代码块可能还没闭合，那就一直到结尾
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += Block.Code(lang, code.toString())
            }
            line.isBlank() -> flush()
            trimmed.startsWith("$$") || trimmed.startsWith("\\[") -> {
                val close = if (trimmed.startsWith("$$")) "$$" else "\\]"
                val first = trimmed.substring(2)
                val tex = StringBuilder()
                var j = i
                var rest = first
                var closed = false
                // 收到结束符为止（可能和开头在同一行）
                while (true) {
                    val end = rest.indexOf(close)
                    if (end >= 0) {
                        tex.append(rest.substring(0, end))
                        closed = rest.substring(end + 2).isBlank()
                        break
                    }
                    tex.append(rest).append('\n')
                    j++
                    if (j >= lines.size) break
                    rest = lines[j]
                }
                if (closed && tex.isNotBlank()) {
                    flush()
                    blocks += Block.Math(tex.toString().trim())
                    i = j + 1
                    continue
                }
                // 还没写完（流式输出中）或者结束符后面还有字：当普通文字
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trim())
            }
            headingRe.matches(trimmed) -> {
                flush()
                val m = headingRe.find(trimmed)!!
                blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
            }
            ruleRe.matches(line) && para.isEmpty() -> { flush(); blocks += Block.Rule }
            trimmed.startsWith("|") && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) -> {
                flush()
                val rows = mutableListOf(cells(line))
                val aligns = cells(lines[i + 1]).map {
                    when {
                        it.startsWith(":") && it.endsWith(":") -> 'c'
                        it.endsWith(":") -> 'r'
                        else -> 'l'
                    }
                }
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i]); i++ }
                blocks += Block.Table(rows, aligns)
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
            // 列表项下面缩进的续行接在那一项后面
            line.startsWith("  ") && para.isEmpty() && blocks.lastOrNull() is Block.Item -> {
                val last = blocks.removeAt(blocks.lastIndex) as Block.Item
                blocks += last.copy(text = last.text + "\n" + line.trim())
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

/** 一行表格切成格子：去掉两头的竖线；转义的 \| 是格子里的竖线，不算分隔 */
private fun cells(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.drop(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    return t.split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }
}

/**
 * 按空行切段，每段单独排版：写完的段落内容不变就不再重排，流式输出时只有正在写的最后一段在刷新。
 * 代码块（``` ~~~）和公式块（$$、\[ \]）里的空行不切
 */
fun splitBlocks(md: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var fence: String? = null
    var math = false
    for (line in md.replace("\r\n", "\n").split('\n')) {
        val t = line.trim()
        if (fence == null && (t.startsWith("```") || t.startsWith("~~~"))) fence = t.take(3)
        else if (fence != null && t.startsWith(fence)) fence = null
        else if (fence == null) {
            if (t == "$$") math = !math
            else if (t == "\\[") math = true
            else if (t == "\\]") math = false
        }
        if (t.isEmpty() && fence == null && !math) {
            if (cur.isNotBlank()) out += cur.toString().trimEnd('\n')
            cur.clear()
        } else cur.append(line).append('\n')
    }
    if (cur.isNotBlank()) out += cur.toString().trimEnd('\n')
    return out
}

/** 「**小标题**：说明」「**小标题：**说明」这样的列表项拆成（小标题，说明）；冒号、破折号或换行才算分开 */
private val itemTitleRe = Regex("""^\*\*([^*\n]{1,40}?)\*\*(?:\s*[：:—–]\s*|\s*-\s+|\s*\n\s*)(.+)$""", RegexOption.DOT_MATCHES_ALL)
private val itemTitleColonRe = Regex("""^\*\*([^*\n]{1,40}?[：:])\*\*\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

fun itemTitle(text: String): Pair<String?, String> {
    val t = text.trim()
    val m = itemTitleRe.matchEntire(t) ?: itemTitleColonRe.matchEntire(t) ?: return null to text
    val rest = m.groupValues[2].trim()
    return if (rest.isEmpty()) null to text else m.groupValues[1].trim().trimEnd('：', ':') to rest
}

/** 裸露的网址：到空白或中文标点为止，末尾的英文标点不算 */
private val bareUrlRe = Regex("""^https?://[^\s<>()（）「」『』【】，。；：！？、“”]+""")

/** 行内公式在文字里占位用的 id 前缀，后面接它在 math 列表里的序号 */
const val MATH_ID = "math:"

/**
 * 行内公式的结束位置（结束符的下标），不是公式返回 -1。
 * $…$ 要防误认金额：$ 后面紧跟的不能是空白，结束的 $ 前面不能是空白、后面不能是数字（「$5 和 $10」不算公式）
 */
fun mathEnd(rest: String): Int {
    when {
        rest.startsWith("\\(") -> return rest.indexOf("\\)", 2)
        rest.startsWith("$$") -> return rest.indexOf("$$", 2).takeIf { it > 2 } ?: -1
        rest.startsWith("$") -> {
            if (rest.length < 3 || rest[1].isWhitespace() || rest[1] == '$') return -1
            var k = 1
            while (true) {
                k = rest.indexOf('$', k)
                if (k < 0) return -1
                if (rest.substring(1, k).contains('\n')) return -1
                val after = rest.getOrNull(k + 1)
                if (rest[k - 1] != '\\' && !rest[k - 1].isWhitespace() && (after == null || !after.isDigit())) return k
                k++
            }
        }
    }
    return -1
}

private fun mathTex(rest: String, end: Int): String {
    val open = if (rest.startsWith("$") && !rest.startsWith("$$")) 1 else 2
    return rest.substring(open, end).trim()
}

/** 行内样式：**粗体**、*斜体*、`代码`、[文字](链接)、~~删除线~~、公式。公式依次放进 math，文字里留一个占位 */
fun inline(
    text: String, code: SpanStyle, link: SpanStyle,
    math: MutableList<String>? = null,
    /** 这段公式能不能画出来；画不出来的就原样显示 */
    accept: (String) -> Boolean = { true },
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        val mathEnd = if (math != null) mathEnd(rest) else -1
        when {
            rest.startsWith("\\$") -> { append('$'); i += 2 }
            mathEnd > 0 && accept(mathTex(rest, mathEnd)) -> {
                val close = if (rest.startsWith("$") && !rest.startsWith("$$")) 1 else 2
                math!!.add(mathTex(rest, mathEnd))
                appendInlineContent(MATH_ID + (math.size - 1), rest.substring(0, mathEnd + close))
                i += mathEnd + close
            }
            rest.startsWith("`") && rest.indexOf('`', 1) > 0 -> {
                val end = rest.indexOf('`', 1)
                withStyle(code) { append(rest.substring(1, end)) }
                i += end + 1
            }
            (rest.startsWith("**") || rest.startsWith("__")) && rest.indexOf(rest.take(2), 2) > 2 -> {
                val end = rest.indexOf(rest.take(2), 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(rest.substring(2, end), code, link, math, accept)) }
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
            rest.startsWith("<br") && Regex("""^<br\s*/?>""").find(rest) != null -> {
                append('\n')
                i += Regex("""^<br\s*/?>""").find(rest)!!.value.length
            }
            (rest.startsWith("http://") || rest.startsWith("https://")) && (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                bareUrlRe.find(rest) != null -> {
                val url = bareUrlRe.find(rest)!!.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
                withLink(LinkAnnotation.Url(url, TextLinkStyles(link))) { append(url) }
                i += url.length
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
fun MarkdownText(text: String, modifier: Modifier = Modifier, fade: Int = 0) {
    val chunks = remember(text) { splitBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chunks.forEachIndexed { i, chunk ->
            // 按位置记：写完的段落文字不变，里面的东西都不会重新解析、排版
            key(i) { MarkdownChunk(chunk, if (i == chunks.lastIndex) fade else 0) }
        }
    }
}

/** 一段（空行之间）的 Markdown */
@Composable
private fun MarkdownChunk(text: String, fade: Int) {
    val colors = LocalColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.group, fontSize = 14.sp)
    val link = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            // 流式输出时，最后一块末尾刚冒出来的几个字是淡入的
            val tail = if (index == blocks.lastIndex) fade else 0
            when (block) {
                is Block.Heading -> RichText(
                    block.text, code, link, colors.text,
                    when (block.level) { 1 -> 21.sp; 2 -> 19.sp; 3 -> 17.5.sp; else -> 16.sp },
                    when (block.level) { 1 -> 29.sp; 2 -> 27.sp; else -> 25.sp },
                    fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp), fade = tail, spacing = 0.sp,
                )
                is Block.Paragraph -> RichText(block.text, code, link, colors.text, BODY, BODY_LINE, fade = tail)
                is Block.Item -> ListItem(block, code, link, tail)
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.border))
                    RichText(block.text, code, link, colors.sub, BODY, BODY_LINE, modifier = Modifier.padding(start = 12.dp), fade = tail)
                }
                is Block.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.group)) {
                    if (block.lang.isNotEmpty()) Text(block.lang, color = colors.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
                    Text(
                        block.code, color = colors.text, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace, softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                    )
                }
                is Block.Table -> TableBlock(block, code, link)
                Block.Rule -> Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(colors.border))
                is Block.Math -> MathBlock(block.tex, colors.text)
            }
        }
    }
}

private val BODY = 16.sp
private val BODY_LINE = 26.sp

/**
 * 列表项：第一层的编号放进浅灰小圆里，第二层起的圆点是空心的；
 * 「**小标题**：说明」拆成加粗的小标题一行、颜色浅一点的说明一行
 */
@Composable
private fun ListItem(block: Block.Item, code: SpanStyle, link: SpanStyle, fade: Int) {
    val colors = LocalColors.current
    val ordered = block.marker != "•"
    Row(Modifier.padding(start = (block.depth.coerceAtMost(3) * 18).dp, top = if (ordered && block.depth == 0) 2.dp else 0.dp)) {
        if (ordered && block.depth == 0) {
            Box(
                Modifier.padding(top = 1.dp).size(24.dp).clip(CircleShape).background(colors.bubble),
                contentAlignment = Alignment.Center,
            ) {
                Text(block.marker.trimEnd('.', ')'), color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Text(
                if (ordered) block.marker else if (block.depth == 0) "•" else "◦",
                color = if (ordered) colors.text else colors.sub, fontSize = BODY, lineHeight = BODY_LINE,
                fontWeight = if (ordered) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.width(if (ordered) 26.dp else 18.dp),
            )
        }
        val (title, rest) = remember(block.text) { itemTitle(block.text) }
        if (title != null) Column(Modifier.weight(1f)) {
            RichText(title, code, link, colors.text, BODY, BODY_LINE, fontWeight = FontWeight.SemiBold)
            RichText(rest, code, link, colors.sub, BODY, BODY_LINE, fade = fade)
        } else RichText(block.text, code, link, colors.text, BODY, BODY_LINE, modifier = Modifier.weight(1f), fade = fade)
    }
}

/** 一段文字，里面的行内公式画成图嵌在字里 */
@Composable
private fun RichText(
    src: String, code: SpanStyle, link: SpanStyle, color: Color, fontSize: TextUnit, lineHeight: TextUnit,
    modifier: Modifier = Modifier, fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign? = null,
    /** 末尾几个字淡入：越靠后越透明 */
    fade: Int = 0,
    /** 字距：正文稍微放开一点读着松快，标题不放 */
    spacing: TextUnit = 0.02.em,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val argb = color.toArgb()
    val (text, formulas) = remember(src, code, link, sizePx, argb) {
        val list = mutableListOf<String>()
        inline(src, code, link, list) { Latex.drawable(it, sizePx, argb) != null } to list.toList()
    }
    val shown = if (fade <= 0 || text.isEmpty()) text else AnnotatedString.Builder(text).apply {
        val n = min(fade, text.length)
        for (k in 0 until n) {
            val pos = text.length - n + k
            addStyle(SpanStyle(color = color.copy(alpha = color.alpha * (n - k) / (n + 1f))), pos, pos + 1)
        }
    }.toAnnotatedString()
    val inlineContent = formulas.mapIndexedNotNull { i, tex ->
        val d = Latex.drawable(tex, sizePx, argb) ?: return@mapIndexedNotNull null
        val placeholder = with(density) {
            Placeholder(d.intrinsicWidth.toSp(), d.intrinsicHeight.toSp(), PlaceholderVerticalAlign.TextCenter)
        }
        (MATH_ID + i) to InlineTextContent(placeholder) { Formula(d, Modifier.fillMaxSize()) }
    }.toMap()
    Text(
        shown, color = color, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight,
        textAlign = textAlign, letterSpacing = spacing, inlineContent = inlineContent, modifier = modifier,
    )
}

/**
 * 表格：每列按内容定宽（最窄 56dp，最宽 240dp，超了就换行）；整张表比屏幕窄时各列平分多出的空间，
 * 比屏幕宽时先把宽列压窄（多换几行），压到 110dp 还放不下才左右滑。
 * 表头加底色加粗，隔行浅底色，细线分格；对齐跟着分隔行的冒号走
 */
@Composable
private fun TableBlock(block: Block.Table, code: SpanStyle, link: SpanStyle) {
    val colors = LocalColors.current
    val cols = block.rows.maxOf { it.size }
    val shape = RoundedCornerShape(12.dp)
    val line = colors.border
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        Box(Modifier.clip(shape).border(1.dp, line, shape).horizontalScroll(rememberScrollState())) {
            TableLayout(cols, available) {
                block.rows.forEachIndexed { r, row ->
                    for (c in 0 until cols) {
                        val align = block.aligns.getOrElse(c) { 'l' }
                        Box(
                            Modifier
                                .background(if (r == 0) colors.bubble else if (r % 2 == 0) colors.card else Color.Transparent)
                                .drawBehind {
                                    val w = 1.dp.toPx() / 2
                                    if (c < cols - 1) drawLine(line, Offset(size.width - w, 0f), Offset(size.width - w, size.height), w * 2)
                                    if (r < block.rows.lastIndex) drawLine(line, Offset(0f, size.height - w), Offset(size.width, size.height - w), w * 2)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = when (align) { 'c' -> Alignment.TopCenter; 'r' -> Alignment.TopEnd; else -> Alignment.TopStart },
                        ) {
                            RichText(
                                row.getOrElse(c) { "" }, code, link, colors.text, 14.sp, 21.sp,
                                fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                                textAlign = when (align) { 'c' -> TextAlign.Center; 'r' -> TextAlign.End; else -> TextAlign.Start },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把列宽调到正好占满 available：窄了平分多出的，宽了从宽列身上按比例扣，但不扣到 floor 以下 */
fun fitWidths(widths: IntArray, available: Int, floor: Int) {
    val extra = available - widths.sum()
    if (extra >= 0) {
        for (c in widths.indices) widths[c] += extra / widths.size + if (c < extra % widths.size) 1 else 0
        return
    }
    val room = widths.sumOf { (it - floor).coerceAtLeast(0) }
    if (room <= 0) return
    val cut = minOf(-extra, room)
    var left = cut
    for (c in widths.indices) {
        val give = ((widths[c] - floor).coerceAtLeast(0).toLong() * cut / room).toInt()
        widths[c] -= give
        left -= give
    }
    // 取整剩下的零头从最宽的列扣
    while (left > 0) {
        val c = widths.indices.maxBy { widths[it] }
        if (widths[c] <= floor) break
        widths[c]--
        left--
    }
}

/** 按行排好的格子（行优先，每行 cols 个）；列宽取这一列最宽的内容，行高取这一行最高的格子 */
@Composable
private fun TableLayout(cols: Int, available: Int, content: @Composable () -> Unit) {
    Layout(content) { cells, _ ->
        val rows = (cells.size + cols - 1) / cols
        val minW = 56.dp.roundToPx()
        val maxW = 240.dp.roundToPx()
        val widths = IntArray(cols) { c ->
            (0 until rows).maxOf { r -> cells.getOrNull(r * cols + c)?.maxIntrinsicWidth(Constraints.Infinity) ?: 0 }.coerceIn(minW, maxW)
        }
        if (available != Constraints.Infinity) fitWidths(widths, available, 110.dp.roundToPx())
        val heights = IntArray(rows) { r ->
            (0 until cols).maxOf { c -> cells.getOrNull(r * cols + c)?.maxIntrinsicHeight(widths[c]) ?: 0 }
        }
        val placeables = cells.mapIndexed { i, cell -> cell.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until cols) {
                    placeables.getOrNull(r * cols + c)?.place(x, y)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}

/** 单独一段的公式：放得下就居中，太宽就左右滑；画不出来就按源码显示 */
@Composable
private fun MathBlock(tex: String, color: Color) {
    val density = LocalDensity.current
    val d = Latex.drawable(tex, with(density) { 18.sp.toPx() }, color.toArgb())
    if (d == null) {
        Text(
            tex, color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalColors.current.group).padding(14.dp),
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val min = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = min).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Formula(d, with(density) { Modifier.size(d.intrinsicWidth.toDp(), d.intrinsicHeight.toDp()) })
            }
        }
    }
}

@Composable
private fun Formula(d: Drawable, modifier: Modifier) {
    Box(modifier.drawBehind {
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        drawIntoCanvas { d.draw(it.nativeCanvas) }
    })
}

/** 用 JLaTeXMath 把公式画成 Drawable；同一个公式、字号、颜色只画一次。写错的公式返回 null */
private object Latex {
    private class Entry(val drawable: Drawable?)
    private val cache = LruCache<String, Entry>(300)

    fun drawable(tex: String, sizePx: Float, color: Int): Drawable? {
        val key = "$sizePx|$color|$tex"
        cache.get(key)?.let { return it.drawable }
        val d = try {
            JLatexMathDrawable.builder(tex).textSize(sizePx).color(color).align(JLatexMathDrawable.ALIGN_LEFT).build()
                .takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
        } catch (e: Throwable) {
            null
        }
        cache.put(key, Entry(d))
        return d
    }
}
