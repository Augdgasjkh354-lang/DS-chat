package com.jack.dschat

import com.jack.dschat.data.Attachment
import com.jack.dschat.data.DeepSeek
import com.jack.dschat.data.Documents
import com.jack.dschat.data.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsTest {
    @Test
    fun picksPurposeByExtension() {
        assertEquals("text_recognition", Documents.purpose("报告.PDF"))
        assertEquals("text_recognition", Documents.purpose("a.docx"))
        assertEquals("text_recognition", Documents.purpose("slides.pptx"))
        assertEquals("user_data", Documents.purpose("账单.xlsx"))
        assertEquals("user_data", Documents.purpose("notes.md"))
        assertEquals("user_data", Documents.purpose("Main.kt"))
        assertNull(Documents.purpose("video.mp4"))
        assertNull(Documents.purpose("noext"))
    }

    @Test
    fun formatsSizes() {
        assertEquals("", Documents.sizeText(0))
        assertEquals("512 B", Documents.sizeText(512))
        assertEquals("2 KB", Documents.sizeText(2048))
        assertEquals("1.5 MB", Documents.sizeText(1024 * 1536))
        assertEquals("a_b_", Documents.safeName("a\"b\n"))
    }

    @Test
    fun sendsFileBlocksBeforeText() {
        val m = Message("1", "user", "总结一下", files = listOf(Attachment("file-1", "报告.pdf", 10), Attachment("file-2", "表.xlsx", 5)))
        val body = Json.parseToJsonElement(DeepSeek.requestBody(listOf(m), false, false, false)).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("file", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("file-1", content[0].jsonObject["file_id"]!!.jsonPrimitive.content)
        assertEquals("file-2", content[1].jsonObject["file_id"]!!.jsonPrimitive.content)
        val text = content[2].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("[附件：报告.pdf、表.xlsx]"))
        assertTrue(text.endsWith("总结一下"))
    }
}
