package com.jack.dschat

import com.jack.dschat.data.DeepSeek
import com.jack.dschat.data.Message
import com.jack.dschat.data.finishVersion
import com.jack.dschat.data.startNewVersion
import com.jack.dschat.data.switchTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsImagesTest {
    private val first = Message("r", "assistant", "第一版", reasoning = "想1", thinkSeconds = 2)

    @Test
    fun regenerateKeepsOldVersions() {
        val pending = first.startNewVersion()
        assertEquals("", pending.text)
        assertEquals(1, pending.active) // 指向即将生成的新一版
        assertEquals(listOf("第一版"), pending.versions.map { it.text })

        val done = pending.copy(text = "第二版").finishVersion(previous = 0)
        assertEquals("第二版", done.text)
        assertEquals(1, done.active)
        assertEquals(2, done.versionCount)

        // 再来一版，然后切回第一版：正文、思考都换回去
        val third = done.startNewVersion().copy(text = "第三版").finishVersion(previous = 1)
        assertEquals(3, third.versionCount)
        val back = third.switchTo(0)
        assertEquals("第一版", back.text)
        assertEquals("想1", back.reasoning)
        assertEquals(0, back.active)
        assertEquals("第三版", back.switchTo(2).text)
        // 越界不动
        assertEquals(back, back.switchTo(-1))
        assertEquals(back, back.switchTo(3))
    }

    @Test
    fun emptyNewVersionFallsBack() {
        val two = first.startNewVersion().copy(text = "第二版").finishVersion(0)
        // 新一版刚开始就停了、什么都没有：退回之前看的那一版
        val stopped = two.startNewVersion().finishVersion(previous = 1)
        assertEquals("第二版", stopped.text)
        assertEquals(1, stopped.active)
        assertEquals(2, stopped.versionCount)
        // 只有一版时停掉：还是原样，不留版本列表
        val single = first.startNewVersion().finishVersion(0)
        assertEquals("第一版", single.text)
        assertEquals(1, single.versionCount)
        assertTrue(single.versions.size <= 1)
    }

    @Test
    fun onlyActiveVersionIsSent() {
        val reply = first.startNewVersion().copy(text = "第二版").finishVersion(0).switchTo(0)
        val history = listOf(Message("u", "user", "问"), reply, Message("u2", "user", "再问"))
        val sent = Json.parseToJsonElement(DeepSeek.requestBody(history, false, false, false)).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("问", "第一版", "再问"), sent)
    }

    @Test
    fun imagesBecomeContentParts() {
        val msg = Message("u", "user", "这是什么", images = listOf("/a.jpg", "/b.jpg", "/gone.jpg"))
        val body = DeepSeek.requestBody(listOf(msg), false, false, false, image = { path ->
            when (path) {
                "/a.jpg" -> DeepSeek.fileImage("file-api-1")
                "/b.jpg" -> kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("image_url"))
                }
                else -> null
            }
        })
        val content = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals(listOf("file", "image_url", "text"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("file-api-1", content[0].jsonObject["file_id"]!!.jsonPrimitive.content)
        val text = content[2].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("1 张图片已经找不到"))
        assertTrue(text.endsWith("这是什么"))
    }

    @Test
    fun imageOnlyMessageHasNoTextPart() {
        val msg = Message("u", "user", "", images = listOf("/a.jpg"))
        val body = DeepSeek.requestBody(listOf(msg), false, false, false, image = { DeepSeek.fileImage("f") })
        val content = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals(1, content.size)
    }
}
