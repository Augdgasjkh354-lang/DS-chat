package com.jack.dschat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** 一个可以扮演的角色。字段和「酒馆」（SillyTavern）角色卡对得上，导入导出不丢东西 */
@Serializable
data class Character(
    val id: String,
    val name: String,
    /** 人设：外貌、背景、和用户的关系……（卡里的 description） */
    val description: String = "",
    /** 性格（personality） */
    val personality: String = "",
    /** 场景：故事发生在哪、从什么情形开始（scenario） */
    val scenario: String = "",
    /** 开场白：新对话里角色先说的第一句（first_mes） */
    val greeting: String = "",
    /** 示例对话：几段「它会怎么说话」（mes_example） */
    val examples: String = "",
    /** 额外的要求（system_prompt + post_history_instructions） */
    val instructions: String = "",
    /** 创意度，就是接口的 temperature：越高说话越跳脱 */
    val creativity: Float = DEFAULT_CREATIVITY,
    /** 头像在 filesDir/avatars/{id}.png；没有就用名字的第一个字 */
    val hasAvatar: Boolean = false,
    /** 头像底色（没有图片时） */
    val color: Long = 0xFF5B7CFA,
    val createdAt: Long = 0,
    /** 世界书：和角色绑在一起的背景资料，提到关键词时才告诉模型（卡里的 character_book） */
    val book: Lorebook = Lorebook(),
) {
    companion object {
        const val DEFAULT_CREATIVITY = 1.3f
    }
}

/** 世界书的一条 */
@Serializable
data class LoreEntry(
    val id: String,
    /** 关键词：对话里出现任意一个就生效；写成 /正则/flags 按正则匹配 */
    val keys: List<String> = emptyList(),
    /** 次要关键词：selective 时还要再出现其中任意一个 */
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    /** 备注（卡里的 comment / name），只给人看 */
    val comment: String = "",
    val enabled: Boolean = true,
    /** 始终生效，不看关键词 */
    val constant: Boolean = false,
    val selective: Boolean = false,
    val caseSensitive: Boolean = false,
    /** 插入顺序：越小越靠前；超出篇幅时先保留大的（越靠后越重要，和酒馆一样） */
    val order: Int = 100,
    /** true 放在角色设定之后，false 放在之前 */
    val after: Boolean = false,
)

@Serializable
data class Lorebook(
    val name: String = "",
    /** 看最近几条消息里有没有关键词 */
    val scanDepth: Int = Lore.DEFAULT_SCAN_DEPTH,
    /** 篇幅上限（卡里的 token_budget）；0 用默认 */
    val tokenBudget: Int = 0,
    /** 生效条目的内容里出现别的关键词，也让那些条目生效 */
    val recursive: Boolean = false,
    val entries: List<LoreEntry> = emptyList(),
)

/** 「我」的设定：所有角色都知道 */
@Serializable
data class Persona(val name: String = "", val description: String = "")

/** 角色卡导入失败 */
class CardException(message: String) : Exception(message)

/**
 * 读「酒馆」角色卡：JSON（V1 字段直接在最外层，V2 / V3 在 data 里），
 * 或 PNG（卡片 JSON 用 base64 藏在 tEXt / iTXt / zTXt 块里，关键字 chara 或 ccv3，V3 优先）。
 * 返回角色（还没有 id、头像）和是不是 PNG（PNG 本身就是头像）。
 */
object CharacterCard {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun isPng(bytes: ByteArray) = bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

    fun parse(bytes: ByteArray, notACard: String): Character {
        val text = if (isPng(bytes)) pngCardText(bytes) ?: throw CardException(notACard) else bytes.toString(Charsets.UTF_8)
        val root = runCatching { json.parseToJsonElement(text.trim().removePrefix("﻿")).jsonObject }.getOrNull() ?: throw CardException(notACard)
        val data = (root["data"] as? JsonObject) ?: root
        fun f(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val name = f("name").ifEmpty { f("char_name") }
        if (name.isEmpty()) throw CardException(notACard)
        val instructions = listOf(f("system_prompt"), f("post_history_instructions"))
            .filter { it.isNotEmpty() && it != "{{original}}" }.joinToString("\n\n")
        return Character(
            id = "",
            book = (data["character_book"] as? JsonObject)?.let { Lore.parseBook(it) } ?: Lorebook(),
            name = name,
            description = f("description").ifEmpty { f("char_persona") },
            personality = f("personality"),
            scenario = f("scenario").ifEmpty { f("world_scenario") },
            greeting = f("first_mes").ifEmpty { f("char_greeting") }.ifEmpty {
                (data["alternate_greetings"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            },
            examples = f("mes_example").ifEmpty { f("example_dialogue") },
            instructions = instructions,
            creativity = ((data["extensions"] as? JsonObject)?.get(EXT) as? JsonObject)?.let { (it["creativity"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() }
                ?.coerceIn(0f, 2f) ?: Character.DEFAULT_CREATIVITY,
        )
    }

    /** 本 App 自己的扩展字段（创意度），放在卡的 extensions 里，别的软件会原样保留 */
    private const val EXT = "dschat"

    /**
     * 角色写成酒馆卡 JSON。v3 = true 是 chara_card_v3，否则 chara_card_v2；
     * 最外层同时放一份 V1 字段，老软件也认得。世界书放在 data.character_book
     */
    fun toJson(c: Character, v3: Boolean): String {
        val data = buildJsonObject {
            put("name", c.name)
            put("description", c.description)
            put("personality", c.personality)
            put("scenario", c.scenario)
            put("first_mes", c.greeting)
            put("mes_example", c.examples)
            put("creator_notes", "")
            put("system_prompt", c.instructions)
            put("post_history_instructions", "")
            putJsonArray("alternate_greetings") {}
            putJsonArray("tags") {}
            put("creator", "")
            put("character_version", "")
            putJsonObject("extensions") { putJsonObject(EXT) { put("creativity", c.creativity) } }
            if (c.book.entries.isNotEmpty()) put("character_book", Lore.cardBook(c.book, c.name))
            if (v3) {
                putJsonArray("group_only_greetings") {}
                putJsonArray("assets") { add(buildJsonObject { put("type", "icon"); put("uri", "ccdefault:"); put("name", "main"); put("ext", "png") }) }
            }
        }
        return buildJsonObject {
            put("spec", if (v3) "chara_card_v3" else "chara_card_v2")
            put("spec_version", if (v3) "3.0" else "2.0")
            if (!v3) {
                put("name", c.name); put("description", c.description); put("personality", c.personality)
                put("scenario", c.scenario); put("first_mes", c.greeting); put("mes_example", c.examples)
            }
            put("data", data)
        }.toString()
    }

    /**
     * 把角色藏进一张 PNG：去掉图里原来的 chara / ccv3 块，在 IEND 前加两个 tEXt 块（V2 的 chara 和 V3 的 ccv3，
     * 内容是卡片 JSON 的 base64）。图片本身不变
     */
    fun toPng(image: ByteArray, c: Character): ByteArray {
        require(isPng(image))
        val out = ByteArrayOutputStream(image.size + 4096)
        out.write(image, 0, 8)
        var pos = 8
        var ended = false
        while (pos + 12 <= image.size) {
            val len = int32(image, pos)
            if (len < 0 || pos + 12 + len > image.size) break
            val type = String(image, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == "IEND") {
                for ((key, v3) in listOf("chara" to false, "ccv3" to true)) {
                    val body = key + "\u0000" + Base64.getEncoder().encodeToString(toJson(c, v3).toByteArray(Charsets.UTF_8))
                    writeChunk(out, "tEXt", body.toByteArray(Charsets.ISO_8859_1))
                }
                out.write(image, pos, 12 + len)
                ended = true
                break
            }
            val key = (type == "tEXt" || type == "zTXt" || type == "iTXt") &&
                textChunk(type, image.copyOfRange(pos + 8, pos + 8 + len))?.first.let { it == "chara" || it == "ccv3" }
            if (!key) out.write(image, pos, 12 + len)
            pos += 12 + len
        }
        require(ended) { "PNG 没有 IEND" }
        return out.toByteArray()
    }

    private fun int32(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
        ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        fun int(v: Long) = out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        int(data.size.toLong())
        out.write(typeBytes); out.write(data)
        int(CRC32().apply { update(typeBytes); update(data) }.value)
    }

    /** 从 PNG 的文字块里找卡片 JSON；V3（ccv3）优先于 V2（chara） */
    fun pngCardText(bytes: ByteArray): String? {
        val found = HashMap<String, String>()
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = int32(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val data = bytes.copyOfRange(pos + 8, pos + 8 + len)
            textChunk(type, data)?.let { (key, value) -> if (key == "chara" || key == "ccv3") found[key] = value }
            if (type == "IEND") break
            pos += 12 + len
        }
        val raw = found["ccv3"] ?: found["chara"] ?: return null
        return runCatching { String(Base64.getMimeDecoder().decode(raw.trim()), Charsets.UTF_8) }.getOrNull()
            ?: raw.takeIf { it.trimStart().startsWith("{") }
    }

    private fun textChunk(type: String, data: ByteArray): Pair<String, String>? {
        val zero = data.indexOf(0)
        if (zero <= 0) return null
        val key = String(data, 0, zero, Charsets.ISO_8859_1)
        return when (type) {
            "tEXt" -> key to String(data, zero + 1, data.size - zero - 1, Charsets.ISO_8859_1)
            "zTXt" -> key to inflate(data.copyOfRange(zero + 2, data.size)).toString(Charsets.ISO_8859_1)
            "iTXt" -> {
                // 关键字 \0 压缩标志 压缩方法 语言 \0 翻译的关键字 \0 文字
                val compressed = data.getOrNull(zero + 1)?.toInt() == 1
                var p = zero + 3
                repeat(2) { p = data.indexOf(0, p) + 1; if (p <= 0) return null }
                val body = data.copyOfRange(p, data.size)
                key to (if (compressed) inflate(body) else body).toString(Charsets.UTF_8)
            }
            else -> null
        }
    }

    private fun ByteArray.indexOf(b: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    private fun inflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        InflaterInputStream(data.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }
}

/** 世界书：读（酒馆角色卡里的 character_book，或单独的世界书 JSON）和挑出这一轮该生效的条目 */
object Lore {
    const val DEFAULT_SCAN_DEPTH = 4
    /** 默认最多带这么多字的世界书内容 */
    const val DEFAULT_BUDGET_CHARS = 8000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = str(k)?.toBooleanStrictOrNull()
    private fun JsonObject.int(k: String) = str(k)?.toDoubleOrNull()?.toInt()
    private fun JsonObject.list(k: String): List<String> = when (val v = this[k]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }

    /**
     * 角色卡 V2 / V3 的 character_book：entries 是数组，字段 keys、secondary_keys、enabled、insertion_order、position 等。
     * 酒馆单独导出的世界书：entries 是以编号为键的对象，字段 key、keysecondary、disable、order、position(0 前 / 其余后)。
     */
    fun parseBook(root: JsonObject): Lorebook? {
        val raw = root["entries"] ?: return null
        val items = when (raw) {
            is JsonArray -> raw.mapNotNull { it as? JsonObject }
            is JsonObject -> raw.values.mapNotNull { it as? JsonObject }
            else -> return null
        }
        val entries = items.mapIndexedNotNull { i, e ->
            val content = e.str("content")?.trim().orEmpty()
            if (content.isEmpty()) return@mapIndexedNotNull null
            val ext = e["extensions"] as? JsonObject
            val position = e.str("position") ?: ext?.str("position")
            LoreEntry(
                id = "lore-" + java.util.UUID.randomUUID(),
                keys = e.list("keys").ifEmpty { e.list("key") },
                secondaryKeys = e.list("secondary_keys").ifEmpty { e.list("keysecondary") },
                content = content,
                comment = e.str("comment")?.trim().orEmpty().ifEmpty { e.str("name")?.trim().orEmpty() },
                enabled = e.bool("enabled") ?: (e.bool("disable")?.not() ?: true),
                constant = e.bool("constant") ?: false,
                selective = e.bool("selective") ?: false,
                caseSensitive = e.bool("case_sensitive") ?: e.bool("caseSensitive") ?: false,
                order = e.int("insertion_order") ?: e.int("order") ?: (100 + i),
                after = when (position) { null, "before_char", "0" -> false; else -> true },
            )
        }
        return Lorebook(
            name = root.str("name").orEmpty(),
            scanDepth = (root.int("scan_depth") ?: DEFAULT_SCAN_DEPTH).coerceIn(1, 50),
            tokenBudget = root.int("token_budget") ?: 0,
            recursive = root.bool("recursive_scanning") ?: false,
            entries = entries,
        )
    }

    /** 读一个单独的世界书文件，或者整张角色卡里的世界书 */
    fun parseFile(bytes: ByteArray, notABook: String): Lorebook {
        val text = if (CharacterCard.isPng(bytes)) CharacterCard.pngCardText(bytes) else bytes.toString(Charsets.UTF_8)
        val root = text?.let { runCatching { json.parseToJsonElement(it.trim().removePrefix("\uFEFF")).jsonObject }.getOrNull() }
            ?: throw CardException(notABook)
        val book = (root["data"] as? JsonObject)?.get("character_book") as? JsonObject
            ?: root["character_book"] as? JsonObject ?: root
        return parseBook(book)?.takeIf { it.entries.isNotEmpty() } ?: throw CardException(notABook)
    }

    /** 写成角色卡里的 character_book（V2 / V3 格式） */
    fun cardBook(book: Lorebook, fallbackName: String = ""): JsonObject = buildJsonObject {
        put("name", book.name.ifBlank { fallbackName })
        put("scan_depth", book.scanDepth)
        if (book.tokenBudget > 0) put("token_budget", book.tokenBudget)
        put("recursive_scanning", book.recursive)
        putJsonObject("extensions") {}
        putJsonArray("entries") {
            book.entries.forEachIndexed { i, e ->
                add(buildJsonObject {
                    put("id", i)
                    put("keys", strings(e.keys))
                    put("secondary_keys", strings(e.secondaryKeys))
                    put("comment", e.comment)
                    put("name", e.comment)
                    put("content", e.content)
                    put("constant", e.constant)
                    put("selective", e.selective)
                    put("case_sensitive", e.caseSensitive)
                    put("insertion_order", e.order)
                    put("priority", e.order)
                    put("enabled", e.enabled)
                    put("position", if (e.after) "after_char" else "before_char")
                    putJsonObject("extensions") { put("position", if (e.after) 1 else 0) }
                })
            }
        }
    }

    /** 写成酒馆单独的世界书文件：entries 是以编号为键的对象 */
    fun worldInfo(book: Lorebook, fallbackName: String = ""): String = buildJsonObject {
        put("name", book.name.ifBlank { fallbackName })
        put("scan_depth", book.scanDepth)
        if (book.tokenBudget > 0) put("token_budget", book.tokenBudget)
        put("recursive_scanning", book.recursive)
        putJsonObject("entries") {
            book.entries.forEachIndexed { i, e ->
                putJsonObject(i.toString()) {
                    put("uid", i)
                    put("key", strings(e.keys))
                    put("keysecondary", strings(e.secondaryKeys))
                    put("comment", e.comment)
                    put("content", e.content)
                    put("constant", e.constant)
                    put("selective", e.selective)
                    put("caseSensitive", e.caseSensitive)
                    put("order", e.order)
                    put("position", if (e.after) 1 else 0)
                    put("disable", !e.enabled)
                    put("displayIndex", i)
                }
            }
        }
    }.toString()

    private fun strings(list: List<String>): JsonElement = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }

    /** 关键词有没有出现在 text 里；/…/flags 按正则 */
    fun matches(key: String, text: String, caseSensitive: Boolean): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        val re = Regex("""^/(.+)/([a-z]*)$""").find(k)
        if (re != null) {
            // 正则按它自己的标志：带 i 才不分大小写
            val options = if ('i' in re.groupValues[2]) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return runCatching { Regex(re.groupValues[1], options).containsMatchIn(text) }.getOrDefault(false)
        }
        return text.contains(k, ignoreCase = !caseSensitive)
    }

    /**
     * 这一轮生效的条目：始终生效的，加上关键词出现在最近 scanDepth 条消息里的（selective 的还要次要关键词也出现）。
     * 开了 recursive 时，生效条目的内容也算进扫描范围（最多再扫三轮）。超出篇幅时先丢 order 小的，最后按 order 排好
     */
    fun activate(book: Lorebook, recent: List<String>): List<LoreEntry> {
        val pool = book.entries.filter { it.enabled && it.content.isNotBlank() }
        if (pool.isEmpty()) return emptyList()
        var scan = recent.takeLast(book.scanDepth.coerceAtLeast(1)).joinToString("\n")
        val active = LinkedHashSet<LoreEntry>()
        repeat(if (book.recursive) 4 else 1) {
            val found = pool.filter { e ->
                e !in active && (e.constant || (e.keys.any { matches(it, scan, e.caseSensitive) } &&
                    (!e.selective || e.secondaryKeys.isEmpty() || e.secondaryKeys.any { matches(it, scan, e.caseSensitive) })))
            }
            if (found.isEmpty()) return@repeat
            active += found
            scan += "\n" + found.joinToString("\n") { it.content }
        }
        val budget = if (book.tokenBudget > 0) book.tokenBudget * 2 else DEFAULT_BUDGET_CHARS
        var used = 0
        val kept = active.sortedWith(compareByDescending<LoreEntry> { it.constant }.thenByDescending { it.order })
            .filter { e -> (used + e.content.length <= budget).also { if (it) used += e.content.length } }
        return kept.sortedBy { it.order }
    }
}

/** 角色对话发给模型的系统提示和上下文 */
object RolePrompt {
    /** 卡里常见的占位符：{{char}} 换成角色名，{{user}} 换成「我」的名字 */
    fun fill(text: String, char: String, user: String): String = text
        .replace(Regex("""\{\{\s*char\s*}}|<BOT>""", RegexOption.IGNORE_CASE), char)
        .replace(Regex("""\{\{\s*user\s*}}|<USER>""", RegexOption.IGNORE_CASE), user)

    fun userName(persona: Persona, fallback: String) = persona.name.trim().ifEmpty { fallback }

    fun system(c: Character, persona: Persona, memory: String, userFallback: String, lore: List<LoreEntry> = emptyList()): String {
        val user = userName(persona, userFallback)
        fun f(s: String) = fill(s, c.name, user).trim()
        fun loreSection(entries: List<LoreEntry>) = entries.joinToString("\n\n") { f(it.content) }
        val before = lore.filterNot { it.after }
        val afterLore = lore.filter { it.after }
        return buildString {
            append("你现在扮演「${c.name}」，和「$user」进行角色扮演对话。\n")
            append("规则：始终以${c.name}的身份、语气和视角说话，不跳出角色，不提自己是 AI 或模型；")
            append("动作、神态、心理可以用（括号）描写；回复长短跟着情境走，像真人聊天；")
            append("只写${c.name}的言行，不替$user 说话、不替$user 做决定；记住之前发生过的事，前后一致。\n")
            append("用户用什么语言说话，就用什么语言回复。\n")
            if (before.isNotEmpty()) append("\n【世界设定】\n").append(loreSection(before)).append('\n')
            if (c.description.isNotBlank()) append("\n【${c.name}的设定】\n").append(f(c.description)).append('\n')
            if (c.personality.isNotBlank()) append("\n【性格】\n").append(f(c.personality)).append('\n')
            if (c.scenario.isNotBlank()) append("\n【场景】\n").append(f(c.scenario)).append('\n')
            if (afterLore.isNotEmpty()) append("\n【相关设定】\n").append(loreSection(afterLore)).append('\n')
            if (persona.description.isNotBlank()) append("\n【关于$user】\n").append(f(persona.description)).append('\n')
            if (c.examples.isNotBlank()) {
                append("\n【示例对话】（只是说话风格的参考，不是真的发生过）\n")
                append(f(c.examples.replace(Regex("""<START>""", RegexOption.IGNORE_CASE), "---"))).append('\n')
            }
            if (c.instructions.isNotBlank()) append("\n【额外要求】\n").append(f(c.instructions)).append('\n')
            if (memory.isNotBlank()) append("\n【前情回忆】（更早的对话已经整理成下面的回忆，以此为准）\n").append(memory.trim()).append('\n')
        }.trim()
    }
}

/**
 * 长期记忆：对话长了，把最早的那部分让模型整理成一份「回忆」，之后只发回忆 + 最近的对话。
 * 回忆存在对话里（Conversation.memory），用户能看、能改。
 */
object RoleMemory {
    /** 还没整理进回忆的对话超过这么多字，就整理一次 */
    const val TRIGGER_CHARS = 24_000
    /** 整理时最近这么多条消息不动，原样留在上下文里 */
    const val KEEP_RECENT = 16

    /** 需要整理的话，返回这次要整理到的位置（不含）；不需要返回 null */
    fun due(c: Conversation, force: Boolean = false): Int? {
        val start = c.memoryUpTo.coerceIn(0, c.messages.size)
        val end = c.messages.size - (if (force) 6 else KEEP_RECENT)
        if (end <= start) return null
        if (!force && c.messages.drop(start).sumOf { it.text.length } < TRIGGER_CHARS) return null
        return end
    }

    const val SYSTEM = "你负责给一段角色扮演对话整理「前情回忆」。把旧的回忆和新的对话合并成一份新的回忆，" +
        "用简洁的中文分条写：两人的关系和称呼、发生过的重要事件（按先后）、约定和承诺、各自透露过的个人信息和喜好、" +
        "角色当前的情绪和处境、没讲完的线索。只写对话里真的出现过的事，不编造；删掉无关紧要的寒暄。" +
        "控制在 800 字以内。只输出回忆本身，不要标题以外的说明。"

    fun input(memory: String, chunk: List<Message>, charName: String, userName: String): String = buildString {
        append("【旧的回忆】\n").append(memory.ifBlank { "（还没有）" }).append("\n\n【新的对话】\n")
        for (m in chunk) {
            if (m.text.isBlank()) continue
            append(if (m.isUser) userName else charName).append("：").append(m.text.trim()).append('\n')
        }
    }
}
