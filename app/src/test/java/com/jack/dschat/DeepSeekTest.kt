package com.jack.dschat

import com.jack.dschat.data.DeepSeek
import com.jack.dschat.data.DeepSeek.Event
import com.jack.dschat.data.Message
import com.jack.dschat.data.Source
import com.jack.dschat.ui.Block
import com.jack.dschat.ui.parseMarkdown
import com.jack.dschat.ui.inline
import androidx.compose.ui.text.SpanStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekTest {
    private fun user(text: String) = Message("u", "user", text)

    @Test
    fun requestHasToolsThinkingAndTitle() {
        val body = Json.parseToJsonElement(DeepSeek.requestBody(listOf(user("hi")), think = true, search = true, wantTitle = true)).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("deepseek_web_search", "deepseek_read_url"), tools)
        assertTrue(body.containsKey("title"))
    }

    @Test
    fun searchOffKeepsOnlyReadUrl() {
        val body = Json.parseToJsonElement(DeepSeek.requestBody(listOf(user("hi")), think = false, search = false, wantTitle = false)).jsonObject
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("deepseek_read_url"), body["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertFalse(body.containsKey("title"))
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun historyPassesBackToolCallsAndSkipsEmptyReplies() {
        val calls = """[{"index":0,"reasoning_content":"step0","calls":[]}]"""
        val history = listOf(
            user("a"),
            Message("x", "assistant", "", error = "boom"),
            user("a"),
            Message("y", "assistant", "answer", reasoning = "step0final", toolCalls = calls),
            user("b"),
        )
        val messages = Json.parseToJsonElement(DeepSeek.requestBody(history, true, true, false)).jsonObject["messages"]!!.jsonArray
        assertEquals(4, messages.size)
        val reply = messages[2].jsonObject
        assertEquals("final", reply["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(1, reply["server_tool_calls"]!!.jsonArray.size)
    }

    @Test
    fun parsesDeltas() {
        assertEquals(listOf(Event.Reasoning("想")), DeepSeek.parse("""{"choices":[{"index":0,"delta":{"reasoning_content":"想"}}]}"""))
        assertEquals(listOf(Event.Content("好")), DeepSeek.parse("""{"choices":[{"index":0,"delta":{"content":"好","reasoning_content":null}}]}"""))
        assertEquals(emptyList<Event>(), DeepSeek.parse("not json"))
    }

    @Test
    fun parsesToolEvents() {
        val started = DeepSeek.parse(
            """{"choices":[{"index":0,"delta":{},"server_tool_event":{"type":"call_started","step":0,"call":{"action":{"type":"search","queries":["天气","北京"]}}}}]}""",
        )
        assertEquals(listOf(Event.ToolStarted(listOf("天气", "北京"), null)), started)
        val read = DeepSeek.parse(
            """{"choices":[{"index":0,"delta":{},"server_tool_event":{"type":"call_started","step":0,"call":{"action":{"type":"read_link","url":"https://a.com"}}}}]}""",
        )
        assertEquals(listOf(Event.ToolStarted(emptyList(), "https://a.com")), read)
    }

    @Test
    fun parsesFinishWithCitationsAndTitle() {
        val chunk = """{"choices":[{"index":0,"finish_reason":"stop","delta":{"title":" 北京天气 ",
            "annotations":[
              {"type":"url_citation","url_citation":{"url":"https://b.com","title":"https://b.com","start_index":0,"end_index":3}},
              {"type":"url_citation","url_citation":{"url":"https://a.com","title":"A","start_index":4,"end_index":6}},
              {"type":"url_citation","url_citation":{"url":"https://b.com","title":"B","start_index":7,"end_index":9}}],
            "server_tool_calls":[{"index":0,"calls":[{"index":0,"kind":"search","sources":[
              {"type":"url","url":"https://a.com","title":"A 站文章","site_name":"A 站"}]}]}]}}]}"""
        val events = DeepSeek.parse(chunk)
        assertEquals(Event.Title("北京天气"), events[0])
        val finish = events[1] as Event.Finish
        assertEquals("stop", finish.reason)
        assertEquals(listOf(Source("https://b.com"), Source("https://a.com", "A 站文章", "A 站")), finish.cited)
        assertTrue(finish.toolCalls.contains("sources"))
    }

    @Test
    fun parsesMidStreamError() {
        assertEquals(
            listOf(Event.Failure("The model failed")),
            DeepSeek.parse("""{"error":{"type":"api_error","message":"The model failed"},"choices":[]}"""),
        )
    }

    @Test
    fun parsesMarkdownBlocks() {
        val blocks = parseMarkdown(
            """
            # 标题
            第一段
            接着第一段

            - 一
              - 二
            1. 三
            > 引用
            ---
            | a | b |
            |---|:-:|
            | 1 | 2 |
            ```kotlin
            val x = 1
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Heading(1, "标题"),
                Block.Paragraph("第一段\n接着第一段"),
                Block.Item("•", "一", 0),
                Block.Item("•", "二", 1),
                Block.Item("1.", "三", 0),
                Block.Quote("引用"),
                Block.Rule,
                Block.Table(listOf(listOf("a", "b"), listOf("1", "2")), listOf('l', 'c')),
                Block.Code("kotlin", "val x = 1"),
            ),
            blocks,
        )
    }

    private fun formulas(text: String): Pair<String, List<String>> {
        val list = mutableListOf<String>()
        val out = inline(text, SpanStyle(), SpanStyle(), list)
        return out.text to list
    }

    @Test
    fun findsInlineMath() {
        assertEquals(listOf("E=mc^2"), formulas("质能方程 ${'$'}E=mc^2${'$'} 很有名").second)
        assertEquals(listOf("\\frac{a}{b}", "x"), formulas("\\(\\frac{a}{b}\\) 和 ${'$'}${'$'}x${'$'}${'$'}").second)
    }

    @Test
    fun moneyIsNotMath() {
        assertEquals(emptyList<String>(), formulas("苹果 ${'$'}5，香蕉 ${'$'}10").second)
        assertEquals(emptyList<String>(), formulas("价格是 ${'$'} 5 到 ${'$'} 8").second)
        assertEquals("花了 ${'$'}3", formulas("花了 \\${'$'}3").first)
    }

    @Test
    fun parsesBlockMath() {
        val d = "${'$'}${'$'}"
        assertEquals(
            listOf(Block.Paragraph("前面"), Block.Math("a^2+b^2=c^2"), Block.Math("\\int_0^1 x\\,dx"), Block.Paragraph("后面")),
            parseMarkdown("前面\n$d\na^2+b^2=c^2\n$d\n\\[ \\int_0^1 x\\,dx \\]\n后面"),
        )
        // 流式输出时还没闭合：先当普通文字
        assertEquals(listOf(Block.Paragraph("$d\nx+1")), parseMarkdown("$d\nx+1"))
    }
}
