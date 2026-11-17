package com.jack.dschat

import com.jack.dschat.data.Conversation
import com.jack.dschat.data.DeepSeek
import com.jack.dschat.data.HistoryImport
import com.jack.dschat.data.Message
import com.jack.dschat.data.Source
import com.jack.dschat.ui.Chinese
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HistoryImportTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = HistoryImport.read(ByteArrayInputStream(bytes), Chinese)

    private val manifest = "manifest.json" to """{"export_format_version":3}"""

    private fun index(vararg ids: String) = "chat_session_index.json" to ids.joinToString(",", "[", "]") {
        """{"id":"$it","title":null,"pinned":false,"message_count":2,"inserted_at":"2026-11-10T01:00:00Z","updated_at":"2026-11-11T02:33:23.270Z"}"""
    }

    private fun msg(id: Int, parent: Int?, role: String, fragments: String, status: String = "FINISHED") =
        """{"id":$id,"parent_id":${parent ?: "null"},"role":"$role","status":"$status","thinking_enabled":true,"search_enabled":true,
            "accumulated_token_usage":0,"inserted_at":"2026-11-10T01:0$id:00Z","fragments":[$fragments]}"""

    /** 一个有分支的会话：1 提问 → 2 回答（旧版本）/ 3 回答（重新生成的，当前停在这里）→ 4 追问 → 5 出错 */
    private val branched = """{"id":"abc","title":"北京天气","pinned":false,"current_message_id":5,
        "inserted_at":"2026-11-10T01:00:00Z","updated_at":"2026-11-11T02:33:23.270Z","messages":[
        ${msg(1, null, "USER", """{"type":"REQUEST","content":"明天北京天气？"},{"type":"FILE","files":[{"id":"f1","file_name":"图.png","file_size":10,"token_usage":null,"inserted_at":"2026-11-10T01:00:00Z","export_path":null,"is_image":true,"width":1,"height":1}]}""")},
        ${msg(2, 1, "ASSISTANT", """{"type":"RESPONSE","content":"旧回答"}""")},
        ${msg(3, 1, "ASSISTANT", """{"type":"THINK","content":"先搜一下","elapsed_secs":3.6},
            {"type":"TOOL_SEARCH","queries":["北京 天气"],"results":[{"url":"https://w.com/a","title":"天气网","snippet":"","published_at":null,"site_name":"W"},{"url":"javascript:x","title":"坏","snippet":"","published_at":null,"site_name":null}]},
            {"type":"READ_LINK","results":[{"url":"https://r.com/p","title":"页面","signature":"SIG"}]},
            {"type":"TIP","content":"hi","style":"INFO"},{"type":"UNKNOWN_THING"},
            {"type":"RESPONSE","content":"晴，"},{"type":"RESPONSE","content":"25 度"}""")},
        ${msg(4, 3, "USER", """{"type":"REQUEST","content":"后天呢"}""")},
        ${msg(5, 4, "ASSISTANT", """{"type":"TEMPLATE_RESPONSE","content":"服务器繁忙"}""", status = "FAILED")}
        ]}"""

    @Test
    fun importsCurrentBranchWithEverything() {
        val r = read(zip(manifest, "user.json" to "{}", "chat_sessions/abc.json" to branched, index("abc"), "chat_sessions/abc_files/f1_图.png" to "PNG"))
        assertEquals(0, r.skipped)
        val c = r.conversations.single()
        assertEquals("ds-abc", c.id)
        assertEquals("北京天气", c.title)
        assertEquals(java.time.Instant.parse("2026-11-11T02:33:23.270Z").toEpochMilli(), c.updatedAt)
        // 只留当前分支：1 → 3 → 4 → 5，旧回答 2 不要
        assertEquals(listOf("ds-abc-1", "ds-abc-3", "ds-abc-4", "ds-abc-5"), c.messages.map { it.id })
        assertEquals("明天北京天气？\n[图片：图.png]", c.messages[0].text)

        val answer = c.messages[1]
        assertEquals("晴，25 度", answer.text)
        assertEquals("先搜一下", answer.reasoning)
        assertEquals(3, answer.thinkSeconds)
        assertEquals(listOf("北京 天气", "https://r.com/p"), answer.searches)
        assertEquals(listOf(Source("https://w.com/a", "天气网", "W")), answer.sources)
        assertFalse(answer.noContext)
        // 读链接的签名按 server_tool_calls 存下来
        val call = Json.parseToJsonElement(answer.toolCalls).jsonArray[0].jsonObject["calls"]!!.jsonArray[0].jsonObject
        assertEquals("SIG", call["signature"]!!.jsonPrimitive.content)
        assertEquals("https://r.com/p", call["action"]!!.jsonObject["url"]!!.jsonPrimitive.content)

        val failed = c.messages[3]
        assertEquals("服务器繁忙", failed.error)
        assertTrue(failed.noContext)
    }

    @Test
    fun failedRoundsStayOutOfContext() {
        val c = read(zip(manifest, "chat_sessions/abc.json" to branched, index("abc"))).conversations.single()
        val next = c.messages + Message("new", "user", "再问一次")
        val sent = Json.parseToJsonElement(DeepSeek.requestBody(next, think = false, search = false, wantTitle = false))
            .jsonObject["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        // 「后天呢」那一轮出错了，提问和回复都不发
        assertEquals(listOf("明天北京天气？\n[图片：图.png]", "晴，25 度", "再问一次"), sent)
    }

    @Test
    fun refusedReplyDropsTextAndThinking() {
        val s = """{"id":"x","title":"","pinned":false,"current_message_id":2,"inserted_at":"2026-11-10T01:00:00Z","updated_at":"2026-11-10T01:00:00Z","messages":[
            ${msg(1, null, "USER", """{"type":"REQUEST","content":"问题"}""")},
            ${msg(2, 1, "ASSISTANT", """{"type":"THINK","content":"想","elapsed_secs":1},{"type":"RESPONSE","content":"答"}""", status = "CONTENT_FILTER")}]}"""
        val m = read(zip(manifest, "chat_sessions/x.json" to s, index("x"))).conversations.single()
        assertEquals("问题", m.title) // 没标题用第一句话
        val reply = m.messages[1]
        assertEquals("", reply.text)
        assertEquals("", reply.reasoning)
        assertEquals(Chinese.replyRefused, reply.error)
        assertTrue(reply.noContext)
    }

    @Test
    fun brokenTreeIsSkippedNotFatal() {
        // 父消息 ID 比自己大：不合规，跳过这个对话
        val bad = """{"id":"bad","title":"t","pinned":false,"current_message_id":1,"inserted_at":"2026-11-10T01:00:00Z","updated_at":"2026-11-10T01:00:00Z","messages":[
            ${msg(1, 2, "ASSISTANT", """{"type":"RESPONSE","content":"a"}""")},${msg(2, null, "USER", """{"type":"REQUEST","content":"q"}""")}]}"""
        val r = read(zip(manifest, "chat_sessions/abc.json" to branched, "chat_sessions/bad.json" to bad, index("abc", "bad")))
        assertEquals(1, r.conversations.size)
        assertEquals(1, r.skipped)
    }

    private fun expectError(bytes: ByteArray, message: String) {
        try {
            read(bytes)
            fail("应该导入失败")
        } catch (e: HistoryImport.ImportException) {
            assertEquals(message, e.message)
        }
    }

    @Test
    fun rejectsWrongFiles() {
        expectError("not a zip".toByteArray(), Chinese.notZip)
        expectError(zip("chat_sessions/abc.json" to branched, manifest, index("abc")), Chinese.badManifest)
        expectError(zip("manifest.json" to """{"export_format_version":2}""", index()), Chinese.badVersion("2"))
        expectError(zip(manifest, "chat_sessions/abc.json" to branched), Chinese.indexMismatch)
        expectError(zip(manifest, "chat_sessions/abc.json" to branched, index("abc", "zzz")), Chinese.indexMismatch)
    }

    @Test
    fun mergeAddsAndUpdates() {
        val mine = Conversation("local", "我的", 1)
        val old = Conversation("ds-abc", "旧标题", 1)
        val (merged, added, updated) = HistoryImport.merge(
            listOf(mine, old),
            listOf(Conversation("ds-abc", "新标题", 2), Conversation("ds-new", "新的", 3)),
        )
        assertEquals(listOf("我的", "新标题", "新的"), merged.map { it.title })
        assertEquals(1, added)
        assertEquals(1, updated)
    }
}
