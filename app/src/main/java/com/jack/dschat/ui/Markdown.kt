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
    val colors = LocalColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.group, fontSize = 14.sp)
    val link = SpanStyle(color = colors.accent)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            // 流式输出时，最后一段末尾刚冒出来的几个字是淡入的
            val tail = if (index == blocks.lastIndex) fade else 0
            when (block) {
                is Block.Heading -> RichText(
                    block.text, code, link, colors.text, when (block.level) { 1 -> 21.sp; 2 -> 19.sp; else -> 17.sp }, 28.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp), fade = tail,
                )
                is Block.Paragraph -> RichText(block.text, code, link, colors.text, 16.sp, 26.sp, fade = tail)
                is Block.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(block.marker, color = colors.sub, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.width(if (block.marker == "•") 16.dp else 24.dp))
                    RichText(block.text, code, link, colors.text, 16.sp, 26.sp, fade = tail)
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.border))
                    RichText(block.text, code, link, colors.sub, 16.sp, 26.sp, modifier = Modifier.padding(start = 12.dp), fade = tail)
                }
                is Block.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.group)) {
                    if (block.lang.isNotEmpty()) Text(block.lang, color = colors.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
                    Text(
                        block.code, color = colors.text, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace, softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                    )
                }
                is Block.Table -> TableBlock(block, code, link)
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                is Block.Math -> MathBlock(block.tex, colors.text)
            }
        }
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
        textAlign = textAlign, inlineContent = inlineContent, modifier = modifier,
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
