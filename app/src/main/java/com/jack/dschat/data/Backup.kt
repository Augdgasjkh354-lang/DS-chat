package com.jack.dschat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份和恢复：所有对话（含图片）、角色（含头像、世界书）、「我的设定」、聊天背景、设置，打成一个 zip。
 * API Key 不进备份（备份文件可能会转给别人或存到网盘）。
 *
 * zip 里：manifest.json、settings.json、chats.json、characters.json、background.jpg、images/…、avatars/…
 */
object Backup {
    const val FORMAT = 1
    private const val APP = "dschat"
    private val json = Json { ignoreUnknownKeys = true }

    /** 备份失败（不是本 App 的备份、版本太新、文件坏了） */
    class BackupException(message: String) : Exception(message)

    /** 设置里要备份的项：键 → 类型 */
    val SETTINGS = mapOf(
        "theme" to Int::class, "font" to Float::class, "english" to Boolean::class, "think" to Boolean::class,
        "search" to Boolean::class, "bgDim" to Float::class, "bgBlur" to Float::class, "autoRead" to Boolean::class,
        "voice" to String::class,
    )

    /**
     * 写备份。chats、characters 是内存里最新的 JSON（不读磁盘上可能还没存完的文件）；
     * filesDir 里取图片、头像、背景
     */
    fun write(out: OutputStream, filesDir: File, chats: String, characters: String, settings: Map<String, Any?>, now: Long) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun text(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            fun file(name: String, f: File) {
                if (!f.isFile) return
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            text("manifest.json", buildJsonObject { put("app", APP); put("format", FORMAT); put("created_at", now) }.toString())
            text("settings.json", buildJsonObject {
                for ((k, v) in settings) when (v) {
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    is String -> put(k, v)
                }
            }.toString())
            text("chats.json", chats)
            text("characters.json", characters)
            file("background.jpg", File(filesDir, "background.jpg"))
            for (dir in listOf("images", "avatars")) {
                File(filesDir, dir).listFiles()?.sortedBy { it.name }?.forEach { if (!it.name.endsWith(".tmp")) file("$dir/${it.name}", it) }
            }
        }
    }

    /** 读出来的备份：文件已经解到 staging 目录里，还没动现有数据 */
    class Restored(val staging: File, val chats: String, val characters: String?, val settings: JsonObject)

    /** 解压并检查备份，放到 staging（先清空）。不认识的文件、带路径穿越的名字一律不要 */
    fun read(input: InputStream, staging: File, notABackup: String, tooNew: String): Restored {
        staging.deleteRecursively()
        staging.mkdirs()
        var manifest: JsonObject? = null
        var chats: String? = null
        var characters: String? = null
        var settings = JsonObject(emptyMap())
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    when {
                        name == "manifest.json" -> manifest = runCatching { json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject }.getOrNull()
                        name == "chats.json" -> chats = zip.readBytes().decodeToString()
                        name == "characters.json" -> characters = zip.readBytes().decodeToString()
                        name == "settings.json" -> settings = runCatching { json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject }.getOrNull() ?: settings
                        name == "background.jpg" -> File(staging, name).outputStream().use { zip.copyTo(it) }
                        else -> {
                            val dir = name.substringBefore('/')
                            val base = name.substringAfter('/')
                            if (dir in setOf("images", "avatars") && base.isNotEmpty() && '/' !in base && '\\' !in base && base != "..") {
                                File(staging, dir).mkdirs()
                                File(File(staging, dir), base).outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw BackupException(notABackup)
        }
        val m = manifest ?: throw BackupException(notABackup)
        if ((m["app"] as? JsonPrimitive)?.contentOrNull != APP) throw BackupException(notABackup)
        if (((m["format"] as? JsonPrimitive)?.intOrNull ?: 0) > FORMAT) throw BackupException(tooNew)
        val c = chats ?: throw BackupException(notABackup)
        return Restored(staging, c, characters, settings)
    }

    /** 恢复出来的消息里图片路径换成这台手机的 images 目录（换手机、换用户时路径可能不一样） */
    fun relocate(list: List<Conversation>, imagesDir: File): List<Conversation> = list.map { c ->
        c.copy(messages = c.messages.map { m ->
            if (m.images.isEmpty()) m else m.copy(images = m.images.map { File(imagesDir, File(it).name).path })
        })
    }

    /** settings.json 里的一项，按 SETTINGS 的类型取；类型不对返回 null */
    fun setting(settings: JsonObject, key: String): Any? {
        val p = settings[key] as? JsonPrimitive ?: return null
        return when (SETTINGS[key]) {
            Int::class -> p.intOrNull
            Float::class -> p.floatOrNull
            Boolean::class -> p.booleanOrNull
            String::class -> if (p.isString) p.content else null
            else -> null
        }
    }

    /** 把 staging 里的图片、头像、背景换进 filesDir（原来的整个换掉） */
    fun install(staging: File, filesDir: File) {
        for (dir in listOf("images", "avatars")) {
            val target = File(filesDir, dir)
            target.deleteRecursively()
            val from = File(staging, dir)
            if (from.isDirectory) from.renameTo(target) || from.copyRecursively(target, overwrite = true)
            target.mkdirs()
        }
        val bg = File(filesDir, "background.jpg")
        val newBg = File(staging, "background.jpg")
        if (newBg.isFile) newBg.copyTo(bg, overwrite = true) else bg.delete()
        staging.deleteRecursively()
    }
}
