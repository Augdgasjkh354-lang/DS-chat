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

class ZipTest {
    private fun zip(charset: java.nio.charset.Charset = Charsets.UTF_8, vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes, charset).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun tmp() = kotlin.io.path.createTempDirectory("zip").toFile()

    @Test
    fun mergesTextAndKeepsDocumentsSeparate() {
        val data = zip(
            Charsets.UTF_8,
            "proj/README.md" to "# 说明".toByteArray(),
            "proj/src/Main.kt" to "fun main() {}".toByteArray(),
            "proj/docs/报告.pdf" to "%PDF-1.4".toByteArray(),
            "proj/logo.png" to byteArrayOf(1, 2, 3),
            "__MACOSX/proj/._README.md" to byteArrayOf(0),
            "proj/.git/config" to "x".toByteArray(),
            "proj/gbk.txt" to "中文笔记".toByteArray(java.nio.charset.Charset.forName("GBK")),
            "proj/bin.json" to byteArrayOf(0, 0, 1),
        )
        val dir = tmp()
        val r = Documents.unpackZip({ data.inputStream() }, dir, "proj.zip", 10)
        assertEquals(3, r.merged)
        assertEquals(2, r.unsupported) // png、假的 json
        assertEquals(0, r.dropped)
        assertEquals(listOf("proj（3 个文本文件）.md", "报告.pdf"), r.files.map { it.name })
        assertEquals("text_recognition", r.files[1].purpose)
        val bundle = r.files[0].file.readText()
        assertTrue(bundle.contains("===== proj/src/Main.kt =====\nfun main() {}"))
        assertTrue(bundle.contains("中文笔记"))
        dir.deleteRecursively()
    }

    @Test
    fun readsGbkFileNamesAndRespectsRoom() {
        val gbk = java.nio.charset.Charset.forName("GBK")
        val data = zip(gbk, "资料/一.pdf" to "a".toByteArray(), "资料/二.docx" to "b".toByteArray(), "资料/三.pptx" to "c".toByteArray())
        val dir = tmp()
        val r = Documents.unpackZip({ data.inputStream() }, dir, "资料.zip", 2)
        assertEquals(listOf("一.pdf", "二.docx"), r.files.map { it.name })
        assertEquals(1, r.dropped)
        dir.deleteRecursively()
    }

    @Test(expected = java.util.zip.ZipException::class)
    fun rejectsNonZip() {
        Documents.unpackZip({ "not a zip".byteInputStream() }, tmp(), "x.zip", 10)
    }
}
