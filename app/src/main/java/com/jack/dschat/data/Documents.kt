package com.jack.dschat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 上传文档给 DeepSeek 解析（Files API）。PDF、Word、PPT 走 text_recognition（只取文字，按页收解析费）；
 * 纯文本、代码、表格、电子书走 user_data（转成文字，不收解析费）。上传会一直等到解析完才返回 file_id。
 * 文件不设过期，删对话时顺手删掉。
 */
object Documents {
    /** 要解析的文档 */
    private val PARSED = setOf("pdf", "doc", "docx", "ppt", "pptx")
    /** 当文字读的：纯文本、代码、表格、电子书 */
    private val TEXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "log", "html", "htm",
        "xlsx", "xls", "epub", "mobi",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "swift", "rb", "php",
        "sh", "sql", "css", "scss", "gradle", "lua", "dart", "r", "m", "vue", "srt", "tex",
    )
    const val MAX_PARSED = 100L * 1024 * 1024
    const val MAX_TEXT = 32L * 1024 * 1024
    /** 一条消息最多带几个文档（一次请求总共最多 20 个） */
    const val MAX_PER_MESSAGE = 10

    private val json = Json { ignoreUnknownKeys = true }

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    /** 按扩展名选解析方式；不认识的返回 null */
    fun purpose(name: String): String? = when (extension(name)) {
        in PARSED -> "text_recognition"
        in TEXT -> "user_data"
        else -> null
    }

    fun maxSize(purpose: String) = if (purpose == "user_data") MAX_TEXT else MAX_PARSED

    fun mime(name: String) = when (extension(name)) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "epub" -> "application/epub+zip"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "md", "markdown" -> "text/markdown"
        else -> "text/plain"
    }

    /** 文件名放进表单头里：去掉引号和换行 */
    fun safeName(name: String) = name.replace(Regex("[\"\\r\\n]"), "_").take(200).ifBlank { "file" }

    /** 上传并等解析完，返回 file_id；出错抛 DeepSeek.ApiException。在后台线程调 */
    fun upload(apiKey: String, name: String, purpose: String, input: InputStream): String {
        val boundary = "----dschat" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL("${DeepSeek.BASE}/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            // 上传最多 120 秒，解析再最多 120 秒
            readTimeout = 300_000
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                // purpose 必须在 file 前面
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n$purpose\r\n".toByteArray())
                // 文件名用 UTF-8 原样写，服务端按 RFC 7578 读
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${safeName(name)}\"\r\nContent-Type: ${mime(name)}\r\n\r\n".toByteArray())
                input.copyTo(out, 64 * 1024)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            DeepSeek.checkStatus(conn)
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            return (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw DeepSeek.ApiException(500, "no file id")
        } finally {
            conn.disconnect()
        }
    }

    /** 删掉服务端的文件；失败就算了 */
    fun delete(apiKey: String, fileId: String) {
        runCatching {
            val conn = DeepSeek.open("${DeepSeek.BASE}/files/$fileId", apiKey).apply { requestMethod = "DELETE" }
            try { conn.responseCode } finally { conn.disconnect() }
        }
    }

    /** 文件大小给人看 */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
