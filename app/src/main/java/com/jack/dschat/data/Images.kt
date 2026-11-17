package com.jack.dschat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 聊天里发的图片：选中后缩到长边 1600、存成 JPEG 放进应用私有目录（原图删了也不影响），消息里只记路径。
 * 做法照 jack-ai：模型看图按尺寸算 token，1600 已经够清楚，再大也是缩到约 1300×1300 再看
 */
class ImageStore(private val context: Context) {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }

    /** 读不出来返回 null */
    fun import(uri: Uri): String? {
        val bitmap = runCatching { decodeImage(context, uri, LIMIT) }.getOrNull() ?: return null
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.path
    }

    fun delete(paths: Collection<String>) {
        paths.forEach { p -> File(p).takeIf { it.parentFile == dir }?.delete() }
    }

    companion object {
        const val LIMIT = 1600
        private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** 缩略图：长边不超过 px；同一张同一尺寸只解码一次 */
        fun thumbnail(path: String, px: Int): Bitmap? {
            val key = "$px|$path"
            cache.get(key)?.let { return it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            cache.put(key, bmp)
            return bmp
        }

        /** 内联发送用的 data URL；文件不在了返回 null */
        fun dataUrl(path: String): String? {
            val f = File(path)
            if (!f.isFile) return null
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f.readBytes())
        }
    }
}

/**
 * DeepSeek Files API：图片上传一次，之后每轮只发 file_id，不用每次把整张图重传（照 jack-ai 的 DeepSeekFiles）。
 * 上传结果按「Key + 图片路径」记在 deepseek_files.json，30 天有效，剩不到一天就重传；传不上的照旧内联发送
 */
class DeepSeekFiles(dir: File) {
    @Serializable
    private data class Entry(val id: String, val expiresAt: Long)

    private val file = File(dir, "deepseek_files.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val lock = Any()

    /** 这次运行里上传接口不能用，就别每轮都试 */
    @Volatile private var broken = false

    private fun load(): MutableMap<String, Entry> =
        runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun keyOf(apiKey: String, path: String) = "${apiKey.hashCode()}:$path"

    /** 确保这些图片都传上去了，返回 路径 → file_id（没传成的不在里面）。在后台线程调 */
    fun ensure(apiKey: String, paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val map = synchronized(lock) { load() }
        map.entries.removeIf { it.value.expiresAt < now }
        val out = mutableMapOf<String, String>()
        var changed = false
        for (path in paths.distinct()) {
            val k = keyOf(apiKey, path)
            val hit = map[k]
            if (hit != null && hit.expiresAt - now > TimeUnit.DAYS.toMillis(1)) {
                out[path] = hit.id
                continue
            }
            if (broken) continue
            val f = File(path)
            if (!f.isFile) continue
            val id = runCatching { upload(apiKey, f) }.getOrElse { e ->
                if (e is DeepSeek.ApiException && e.code in listOf(404, 405)) broken = true
                null
            } ?: continue
            map[k] = Entry(id, now + TimeUnit.DAYS.toMillis(30))
            out[path] = id
            changed = true
        }
        if (changed) synchronized(lock) {
            val merged = load().apply { entries.removeIf { it.value.expiresAt < now }; putAll(map) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, merged))
            tmp.renameTo(file)
        }
        return out
    }

    /** 请求说文件找不到时调用：清空记录，下次全部重传 */
    fun forget() = synchronized(lock) { file.delete() }

    private fun upload(apiKey: String, f: File): String {
        val boundary = "----dschat" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL("${DeepSeek.BASE}/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("purpose", "user_data")
                field("expires_after[anchor]", "created_at")
                field("expires_after[seconds]", TimeUnit.DAYS.toSeconds(30).toString())
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${f.name}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                f.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            if (code !in 200..299) throw DeepSeek.ApiException(code, conn.errorStream?.bufferedReader()?.readText().orEmpty().take(200))
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            return (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw DeepSeek.ApiException(code, "no file id")
        } finally {
            conn.disconnect()
        }
    }
}
