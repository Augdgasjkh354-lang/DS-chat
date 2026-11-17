package com.jack.dschat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 导入 DeepSeek 官方 App / 网页「设置 → 数据管理 → 导出所有历史对话」得到的 deepseek_data-*.zip。
 *
 * 格式照 DeepSeek 开源的 chat-demo（src/services/chat/history，export_format_version 3）：
 * manifest.json（必须是第一个条目）→ user.json（可能有，跳过）→ chat_sessions/{id}.json（每个对话一个）
 * → chat_session_index.json → chat_sessions/{id}_files/…（图片原件，这一版不导入）。
 *
 * 和 chat-demo 不同的地方：
 * - 合并进现有对话，不是整份替换；同一个对话（按官方的会话 ID）再导入一次会更新那一条；
 * - 分支只留最后停留的那条线（current_message_id 往上找到根）；
 * - 图片、文件只留文件名占位。
 */
object HistoryImport {
    const val FORMAT_VERSION = 3
    /** 导入后的对话 ID 前缀：和 App 里自己开的对话分开，重复导入时按它找到同一条 */
    const val ID_PREFIX = "ds-"
    /** 单个 JSON 条目的上限，防止坏文件把内存撑爆 */
    private const val MAX_JSON_BYTES = 64L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** 导入失败的原因，message 是给人看的中文说明 */
    class ImportException(message: String) : Exception(message)

    /** 界面文字随语言切换，所以错误说明从外面传进来 */
    interface Texts {
        val notZip: String
        val badManifest: String
        fun badVersion(v: String): String
        val indexMismatch: String
        val empty: String
        val replyError: String
        val replyRefused: String
        fun image(name: String): String
        fun file(name: String): String
    }

    class Result(
        val conversations: List<Conversation>,
        /** 消息树坏了、跳过的对话数 */
        val skipped: Int,
    )

    fun read(input: InputStream, texts: Texts): Result {
        val sessions = linkedMapOf<String, Conversation?>()
        var indexIds: List<String>? = null
        var first = true
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (first) {
                        first = false
                        if (name != "manifest.json") throw ImportException(texts.badManifest)
                        val manifest = parse(zip, texts) as? JsonObject ?: throw ImportException(texts.badManifest)
                        val version = manifest["export_format_version"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                        if (version != FORMAT_VERSION.toString()) throw ImportException(texts.badVersion(version ?: "?"))
                        continue
                    }
                    // 不认识的路径、目录、图片都跳过（ZipInputStream 读的时候会校验 CRC）
                    if (entry.isDirectory || name.contains("..") || name.startsWith("/")) continue
                    when {
                        name == "chat_session_index.json" -> {
                            val list = parse(zip, texts) as? JsonArray ?: throw ImportException(texts.indexMismatch)
                            indexIds = list.map { (it as? JsonObject)?.str("id") ?: throw ImportException(texts.indexMismatch) }
                        }
                        SESSION.matches(name) -> {
                            val id = SESSION.find(name)!!.groupValues[1]
                            val session = parse(zip, texts) as? JsonObject
                            sessions[id] = session?.let { runCatching { toConversation(it, texts) }.getOrNull() }
                        }
                    }
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: ZipException) {
            throw ImportException(texts.notZip)
        } catch (e: java.io.IOException) {
            throw ImportException(texts.notZip)
        }
        if (first) throw ImportException(texts.notZip)
        // 目录和会话文件要一一对应，对不上说明文件不完整
        val index = indexIds ?: throw ImportException(texts.indexMismatch)
        if (index.toSet() != sessions.keys) throw ImportException(texts.indexMismatch)
        if (sessions.isEmpty()) throw ImportException(texts.empty)
        val good = sessions.values.filterNotNull()
        return Result(good, sessions.size - good.size)
    }

    private val SESSION = Regex("""^chat_sessions/([^/]+)\.json$""")

    private fun parse(zip: ZipInputStream, texts: Texts): JsonElement {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_JSON_BYTES) throw ImportException(texts.notZip)
            out.write(buf, 0, n)
        }
        return runCatching { json.parseToJsonElement(out.toString(Charsets.UTF_8.name())) }.getOrElse { throw ImportException(texts.notZip) }
    }

    /** 一个会话文件转成本地对话；消息树不合规就抛异常（外面会跳过这个对话） */
    fun toConversation(session: JsonObject, texts: Texts): Conversation {
        val sessionId = session.str("id") ?: error("no id")
        val all = (session["messages"] as? JsonArray ?: error("no messages")).map { it as JsonObject }
        val byId = all.associateBy { it.long("id") ?: error("message without id") }
        // 从当前停留的消息往上找到根，就是要留下的那条线
        val currentId = session.long("current_message_id")?.takeIf { it in byId } ?: byId.keys.maxOrNull() ?: error("empty")
        val path = mutableListOf<JsonObject>()
        var cursor: Long? = currentId
        while (cursor != null) {
            val m = byId[cursor] ?: error("dangling parent")
            val parent = m.long("parent_id")
            if (parent != null && parent >= cursor) error("parent must be older")
            path += m
            cursor = parent
            if (path.size > byId.size) error("cycle")
        }
        path.reverse()
        if (path.first().str("role") != "USER") error("root must be a question")
        path.zipWithNext().forEach { (a, b) -> if (a.str("role") == b.str("role")) error("roles must alternate") }

        val messages = path.map { toMessage(sessionId, it, texts) }
        val firstQuestion = messages.firstOrNull { it.isUser }?.text.orEmpty()
        return Conversation(
            id = ID_PREFIX + sessionId,
            title = session.str("title")?.takeIf { it.isNotBlank() } ?: titleFrom(firstQuestion),
            updatedAt = session.time("updated_at") ?: messages.maxOfOrNull { it.time } ?: 0L,
            messages = messages,
        )
    }

    private fun toMessage(sessionId: String, m: JsonObject, texts: Texts): Message {
        val id = "$ID_PREFIX$sessionId-${m.long("id")}"
        val time = m.time("inserted_at") ?: 0L
        val fragments = (m["fragments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (m.str("role") == "USER") {
            val text = buildString {
                fragments.filter { it.str("type") == "REQUEST" }.forEach { append(it.str("content").orEmpty()) }
                // 图片、文件这一版不导入，只留文件名
                fragments.filter { it.str("type") == "FILE" }.flatMap { (it["files"] as? JsonArray).orEmpty() }.forEach { f ->
                    val file = f as? JsonObject ?: return@forEach
                    val name = file.str("file_name").orEmpty()
                    if (isNotEmpty()) append('\n')
                    append(if (file.bool("is_image") == true) texts.image(name) else texts.file(name))
                }
            }
            return Message(id, "user", text, time = time)
        }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        var seconds = 0.0
        var template = ""
        val searches = mutableListOf<String>()
        val sources = linkedMapOf<String, Source>()
        val readCalls = mutableListOf<JsonObject>()
        fragments.forEachIndexed { index, f ->
            when (f.str("type")) {
                "RESPONSE" -> text.append(f.str("content").orEmpty())
                "THINK" -> {
                    reasoning.append(f.str("content").orEmpty())
                    seconds += f.double("elapsed_secs") ?: 0.0
                }
                "TEMPLATE_RESPONSE" -> template += f.str("content").orEmpty()
                "TOOL_SEARCH" -> {
                    (f["queries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.forEach { searches += it }
                    (f["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { r ->
                        val url = r.str("url")?.takeIf(::isWebUrl) ?: return@forEach
                        sources.putIfAbsent(url, Source(url, r.str("title").orEmpty(), r.str("site_name").orEmpty()))
                    }
                }
                "TOOL_OPEN" -> {
                    val url = f.str("url")?.takeIf(::isWebUrl) ?: return@forEachIndexed
                    sources.putIfAbsent(url, Source(url, f.str("title").orEmpty(), f.str("site_name").orEmpty()))
                }
                "READ_LINK" -> (f["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { r ->
                    val url = r.str("url")?.takeIf(::isWebUrl)
                    val signature = r.str("signature")
                    if (url != null) searches += url
                    // 有签名的读链接结果按 Chat Completions 的 server_tool_calls 存下来，接着聊时服务端能恢复网页内容
                    if (url != null && !signature.isNullOrEmpty()) readCalls += buildJsonObject {
                        put("index", readCalls.size)
                        put("id", "history-${m.long("id")}-$index-${readCalls.size}")
                        put("type", "deepseek_read_url")
                        put("kind", "read_link")
                        put("action", buildJsonObject { put("type", "read_link"); put("url", url) })
                        put("status", "completed")
                        put("page", buildJsonObject { put("url", url); put("title", r.str("title") ?: url); put("published_at", null as String?) })
                        put("signature", signature)
                    }
                }
                // TIP、CONTENT_RISK、PARTIAL_FILE_READ、TOOL_FIND、TOOL_IMAGE_SEARCH 等没有对应展示，丢掉
            }
        }
        val status = m.str("status")
        val refused = status == "CONTENT_FILTER"
        val failed = status == "FAILED" || status == "CONTEXT_LENGTH_EXCEEDED" || status == "TIMEOUT"
        val error = when {
            refused -> template.ifBlank { texts.replyRefused }
            failed -> template.ifBlank { texts.replyError }
            else -> template
        }
        val toolCalls = if (readCalls.isEmpty() || refused) "" else buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("reasoning_content", "")
                put("calls", JsonArray(readCalls))
            })
        }.toString()
        return Message(
            id = id,
            role = "assistant",
            // 被拒的回复丢掉正文和思考，只留提示
            text = if (refused) "" else text.toString(),
            reasoning = if (refused) "" else reasoning.toString(),
            thinkSeconds = if (reasoning.isEmpty() || refused) 0 else seconds.toInt().coerceAtLeast(1),
            time = time,
            sources = sources.values.toList(),
            searches = searches,
            toolCalls = toolCalls,
            error = error,
            // 出错、被拒、服务端模板回复：所在这一轮不发给模型当上下文
            noContext = refused || failed || template.isNotBlank(),
        )
    }

    private fun isWebUrl(url: String) = url.startsWith("https://") || url.startsWith("http://")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.time(key: String) = str(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /** 合并：同 ID 的对话用导入的替换，其余新增；返回（合并后的列表，新增数，更新数） */
    fun merge(existing: List<Conversation>, imported: List<Conversation>): Triple<List<Conversation>, Int, Int> {
        val incoming = imported.associateBy { it.id }
        val updated = existing.count { it.id in incoming }
        val kept = existing.map { incoming[it.id] ?: it }
        val known = existing.map { it.id }.toSet()
        val added = imported.filter { it.id !in known }
        return Triple(kept + added, added.size, updated)
    }
}
