package com.jack.dschat

import com.jack.dschat.data.Tts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTest {
    @Test
    fun countsChineseAsTwo() {
        assertEquals(2 * 3 + 2, Tts.billed("你好吗ab"))
    }

    @Test
    fun dropsCodeBlocks() {
        assertEquals("先看代码\n\n就这样", Tts.readable("先看代码\n```kotlin\nval x = 1\n```\n\n\n就这样"))
        assertEquals("没写完", Tts.readable("没写完\n```py\nprint(1)"))
    }

    @Test
    fun shortTextIsOneSegment() {
        assertEquals(listOf("第一段\n第二段"), Tts.split("第一段\n第二段"))
    }

    @Test
    fun longTextSplitsUnderLimitWithoutLosingText() {
        val para = "今天天气很好。".repeat(50) // 350 字，700 计费
        val text = List(10) { para }.joinToString("\n") + "\n" + "长".repeat(900)
        val parts = Tts.split(text, limit = 1000)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue("too long: ${Tts.billed(it)}", Tts.billed(it) <= 1000) }
        assertEquals(text.replace("\n", ""), parts.joinToString("").replace("\n", ""))
    }

    @Test
    fun requestBodyAsksForPcm() {
        val o = Json.parseToJsonElement(Tts.requestBody("你好", "tide")).jsonObject
        assertEquals("tide", o["voice_id"]!!.jsonPrimitive.content)
        assertEquals("pcm", o["format"]!!.jsonPrimitive.content)
        assertEquals("你好", o["text"]!!.jsonPrimitive.content)
    }
}

class SpeechFilterTest {
    private fun run(vararg deltas: String): String {
        val f = com.jack.dschat.data.SpeechFilter()
        val out = StringBuilder()
        deltas.forEach { out.append(f.feed(it)) }
        out.append(f.finish())
        return out.toString()
    }

    @Test
    fun sendsAtSentenceEnds() {
        val f = com.jack.dschat.data.SpeechFilter()
        assertEquals("", f.feed("你好"))
        assertEquals("你好，", f.feed("，今天"))
        assertEquals("今天天气不错。", f.feed("天气不错。明"))
        assertEquals("明天\n", f.feed("天\n"))
        assertEquals("", f.feed("见"))
        assertEquals("见", f.finish())
    }

    @Test
    fun skipsCodeBlocksAcrossDeltas() {
        assertEquals("看代码：\n就这样。", run("看代码：\n``", "`kotlin\nval a = 1", "。\n```\n就这样。"))
    }

    @Test
    fun unclosedCodeIsDropped() {
        assertEquals("开头\n", run("开头\n```py\nprint(1)"))
    }

    @Test
    fun keepsInlineBackticksOutsideLineStart() {
        assertEquals("用 `ls` 看看。", run("用 `ls` 看看。"))
    }
}
