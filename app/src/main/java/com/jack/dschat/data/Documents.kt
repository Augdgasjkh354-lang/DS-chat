package com.jack.dschat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
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

    /** 按扩展名选解析方式；不认识的返回 null（zip 另外处理，见 unpackZip） */
    fun purpose(name: String): String? = when (extension(name)) {
        in PARSED -> "text_recognition"
        in TEXT -> "user_data"
        else -> null
    }

    fun isZip(name: String) = extension(name) == "zip"

    /** 要服务端转换的二进制格式（表格、电子书）：不能当文字拼起来，单独传 */
    private val CONVERTED = setOf("xlsx", "xls", "epub", "mobi")
    /** 没有扩展名但其实是文本的常见文件 */
    private val TEXT_NAMES = setOf("makefile", "dockerfile", "readme", "license", "changelog", "gemfile", "procfile")

    /** 合并后的文本最多这么大（一次请求里文档文字一共最多 8 MiB，留点给别的） */
    const val BUNDLE_MAX = 6L * 1024 * 1024
    /** 防压缩炸弹：解压出来的总量、文件数上限 */
    private const val UNZIP_MAX = 300L * 1024 * 1024
    private const val UNZIP_ENTRIES = 5000

    /** 压缩包里拿出来、要上传的一个文件（在 dir 里） */
    data class Extracted(val name: String, val file: File, val purpose: String)

    data class ZipResult(
        val files: List<Extracted>,
        /** 合并进文本合集的文件数 */
        val merged: Int,
        /** 格式不支持跳过的 */
        val unsupported: Int,
        /** 名额不够、太大没放进来的 */
        val dropped: Int,
    )

    /**
     * 解压一个 zip：文本、代码文件按路径顺序拼成一份「文本合集」（每个文件前面标出路径）；
     * PDF、Word、PPT、表格、电子书各自单独传。最多 room 个文件，多的算 dropped。
     * 文件名先按 UTF-8 读，读不了再按 GBK（Windows 中文系统打的包）。open 每次返回一个新的流
     */
    fun unpackZip(open: () -> InputStream, dir: File, zipName: String, room: Int): ZipResult {
        return try {
            unpack(open, dir, zipName, room, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            dir.listFiles()?.forEach { it.delete() }
            unpack(open, dir, zipName, room, java.nio.charset.Charset.forName("GBK"))
        }
    }

    private fun unpack(open: () -> InputStream, dir: File, zipName: String, room: Int, charset: java.nio.charset.Charset): ZipResult {
        dir.mkdirs()
        val out = ArrayList<Extracted>()
        val bundle = StringBuilder()
        var bundleBytes = 0L
        var merged = 0
        var unsupported = 0
        var dropped = 0
        var total = 0L
        var count = 0
        var any = false
        // 文本合集占一个名额
        fun slots() = room - out.size - (if (bundle.isNotEmpty()) 1 else 0)
        ZipInputStream(open(), charset).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                any = true
                if (++count > UNZIP_ENTRIES) break
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/')
                val base = path.substringAfterLast('/')
                // 苹果打包带的 __MACOSX、隐藏文件和隐藏目录（.git 之类）跳过
                if (path.startsWith("__MACOSX/") || base.isEmpty() || path.split('/').any { it.startsWith(".") && it != "." }) continue
                val ext = extension(base)
                val textLike = (purpose(base) == "user_data" && ext !in CONVERTED) || (ext.isEmpty() && base.lowercase() in TEXT_NAMES)
                val separate = ext in PARSED || ext in CONVERTED
                if (!textLike && !separate) { unsupported++; continue }
                if (separate) {
                    // 大文件直接写到磁盘，不进内存
                    val p = purpose(base)!!
                    if (slots() <= 0) { dropped++; continue }
                    val f = File(dir, "${out.size}-${safeName(base)}")
                    val size = copyCapped(zip, f, maxSize(p))
                    if (size < 0) { f.delete(); dropped++; continue }
                    total += size
                    if (total > UNZIP_MAX) { f.delete(); dropped++; break }
                    out += Extracted(base, f, p)
                    continue
                }
                val bytes = readCapped(zip, BUNDLE_MAX)
                if (bytes == null) { dropped++; continue }
                total += bytes.size
                if (total > UNZIP_MAX) { dropped++; break }
                val text = decodeText(bytes)
                if (text == null) { unsupported++; continue } // 看着是文本，其实是二进制
                val piece = "===== $path =====\n" + text.trimEnd() + "\n\n"
                val pieceBytes = piece.toByteArray().size
                if (bundle.isEmpty() && slots() <= 0) { dropped++; continue }
                if (bundleBytes + pieceBytes > BUNDLE_MAX) { dropped++; continue }
                bundle.append(piece)
                bundleBytes += pieceBytes
                merged++
            }
        }
        if (!any) throw ZipException("empty or not a zip")
        if (bundle.isNotEmpty()) {
            val name = zipName.substringBeforeLast('.').ifBlank { "zip" } + "（$merged 个文本文件）.md"
            val f = File(dir, "bundle.md")
            f.writeText(bundle.toString())
            out.add(0, Extracted(name, f, "user_data"))
        }
        return ZipResult(out, merged, unsupported, dropped)
    }

    /** 把这一项写进文件，返回字节数；超过 cap 返回 -1 */
    private fun copyCapped(input: InputStream, file: File, cap: Long): Long {
        val chunk = ByteArray(64 * 1024)
        var written = 0L
        file.outputStream().use { out ->
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                written += n
                if (written > cap) return -1
                out.write(chunk, 0, n)
            }
        }
        return written
    }

    /** 读这一项；超过 cap 返回 null */
    private fun readCapped(input: InputStream, cap: Long): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            read += n
            if (read > cap) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** 按 UTF-8 解码，不行再试 GBK；有 NUL 字节的是二进制，返回 null */
    fun decodeText(bytes: ByteArray): String? {
        if (bytes.take(8000).any { it == 0.toByte() }) return null
        for (cs in listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("GBK"))) {
            val decoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: continue
            return text.removePrefix("\uFEFF")
        }
        return null
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
