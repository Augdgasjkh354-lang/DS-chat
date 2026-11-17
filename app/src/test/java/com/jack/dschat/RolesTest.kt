package com.jack.dschat

import com.jack.dschat.data.CardException
import com.jack.dschat.data.Character
import com.jack.dschat.data.CharacterCard
import com.jack.dschat.data.Conversation
import com.jack.dschat.data.DeepSeek
import com.jack.dschat.data.Message
import com.jack.dschat.data.Persona
import com.jack.dschat.data.RoleMemory
import com.jack.dschat.data.RolePrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32

class RolesTest {
    private val v2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"小雨","description":"{{char}}是{{user}}的同桌",
        "personality":"开朗","scenario":"放学后","first_mes":"嘿，{{user}}！","mes_example":"<START>\n{{user}}：你好\n{{char}}：你好呀",
        "system_prompt":"保持轻松","post_history_instructions":"","alternate_greetings":["另一句"],"tags":[]}}"""

    @Test
    fun readsV2Json() {
        val c = CharacterCard.parse(v2.toByteArray(), "bad")
        assertEquals("小雨", c.name)
        assertEquals("{{char}}是{{user}}的同桌", c.description)
        assertEquals("嘿，{{user}}！", c.greeting)
        assertEquals("保持轻松", c.instructions)
    }

    @Test
    fun readsV1Json() {
        val c = CharacterCard.parse("""{"name":"老王","description":"邻居","first_mes":"","alternate_greetings":["早"]}""".toByteArray(), "bad")
        assertEquals("老王", c.name)
        assertEquals("早", c.greeting) // 没有开场白时用备选的第一句
    }

    @Test(expected = CardException::class)
    fun rejectsNonCards() {
        CharacterCard.parse("""{"hello":1}""".toByteArray(), "bad")
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val body = type.toByteArray(Charsets.ISO_8859_1) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        out.write(chunk("IHDR", ByteArray(13)))
        chunks.forEach { out.write(it) }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun text(key: String, json: String) =
        chunk("tEXt", (key + "\u0000" + Base64.getEncoder().encodeToString(json.toByteArray())).toByteArray(Charsets.ISO_8859_1))

    @Test
    fun readsPngCardsPreferringV3() {
        val bytes = png(text("chara", v2), text("ccv3", v2.replace("小雨", "小雨V3")))
        assertTrue(CharacterCard.isPng(bytes))
        assertEquals("小雨V3", CharacterCard.parse(bytes, "bad").name)
        assertEquals("小雨", CharacterCard.parse(png(text("chara", v2)), "bad").name)
        assertNull(CharacterCard.pngCardText(png()))
    }

    private val role = Character("r1", "小雨", description = "{{char}}是{{user}}的同桌", greeting = "嘿", examples = "<START>\n{{user}}：你好", creativity = 1.4f)

    @Test
    fun systemPromptFillsNamesAndMemory() {
        val s = RolePrompt.system(role, Persona("阿杰", "喜欢跑步"), "上次一起去了海边", "Jack")
        assertTrue(s.contains("扮演「小雨」"))
        assertTrue(s.contains("小雨是阿杰的同桌"))
        assertTrue(s.contains("喜欢跑步"))
        assertTrue(s.contains("阿杰：你好"))
        assertFalse(s.contains("<START>"))
        assertTrue(s.contains("上次一起去了海边"))
        // 没写「我」的名字时用默认的
        assertTrue(RolePrompt.system(role, Persona(), "", "Jack").contains("小雨是Jack的同桌"))
    }

    @Test
    fun roleRequestHasSystemTemperatureAndNoTools() {
        val history = listOf(Message("a", "assistant", "旧的"), Message("b", "user", "最近的问题"))
        val body = Json.parseToJsonElement(
            DeepSeek.requestBody(history.drop(1), think = false, search = false, wantTitle = false, system = "设定", temperature = 1.4f, readUrl = false),
        ).jsonObject
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("title"))
        assertEquals(1.4, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("system", messages[0]["role"]!!.jsonPrimitive.content)
        assertEquals(listOf("设定", "最近的问题"), messages.map { it["content"]!!.jsonPrimitive.content })
    }

    @Test
    fun memoryIsDueOnlyWhenLong() {
        fun conv(n: Int, len: Int, upTo: Int = 0) = Conversation("c", "t", 0, List(n) { i ->
            Message("$i", if (i % 2 == 0) "user" else "assistant", "字".repeat(len))
        }, characterId = "r1", memoryUpTo = upTo)
        assertNull(RoleMemory.due(conv(20, 100)))
        // 够长了：整理到「最近 16 条」之前
        assertEquals(40 - RoleMemory.KEEP_RECENT, RoleMemory.due(conv(40, 1000)))
        // 已经整理过的部分不算
        assertNull(RoleMemory.due(conv(40, 1000, upTo = 30)))
        // 手动整理：短对话也行，只留最近 6 条
        assertEquals(14, RoleMemory.due(conv(20, 10), force = true))
        assertNull(RoleMemory.due(conv(4, 10), force = true))
    }

    @Test
    fun memoryInputNamesSpeakers() {
        val input = RoleMemory.input("旧回忆", listOf(Message("1", "user", "去海边吧"), Message("2", "assistant", "好呀")), "小雨", "阿杰")
        assertTrue(input.contains("旧回忆"))
        assertTrue(input.contains("阿杰：去海边吧"))
        assertTrue(input.contains("小雨：好呀"))
    }
}
