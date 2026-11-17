package com.jack.dschat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
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
) {
    companion object {
        const val DEFAULT_CREATIVITY = 1.3f
    }
}

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
            name = name,
            description = f("description").ifEmpty { f("char_persona") },
            personality = f("personality"),
            scenario = f("scenario").ifEmpty { f("world_scenario") },
            greeting = f("first_mes").ifEmpty { f("char_greeting") }.ifEmpty {
                (data["alternate_greetings"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            },
            examples = f("mes_example").ifEmpty { f("example_dialogue") },
            instructions = instructions,
        )
    }

    /** 从 PNG 的文字块里找卡片 JSON；V3（ccv3）优先于 V2（chara） */
    fun pngCardText(bytes: ByteArray): String? {
        val found = HashMap<String, String>()
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
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

/** 角色对话发给模型的系统提示和上下文 */
object RolePrompt {
    /** 卡里常见的占位符：{{char}} 换成角色名，{{user}} 换成「我」的名字 */
    fun fill(text: String, char: String, user: String): String = text
        .replace(Regex("""\{\{\s*char\s*}}|<BOT>""", RegexOption.IGNORE_CASE), char)
        .replace(Regex("""\{\{\s*user\s*}}|<USER>""", RegexOption.IGNORE_CASE), user)

    fun userName(persona: Persona, fallback: String) = persona.name.trim().ifEmpty { fallback }

    fun system(c: Character, persona: Persona, memory: String, userFallback: String): String {
        val user = userName(persona, userFallback)
        fun f(s: String) = fill(s, c.name, user).trim()
        return buildString {
            append("你现在扮演「${c.name}」，和「$user」进行角色扮演对话。\n")
            append("规则：始终以${c.name}的身份、语气和视角说话，不跳出角色，不提自己是 AI 或模型；")
            append("动作、神态、心理可以用（括号）描写；回复长短跟着情境走，像真人聊天；")
            append("只写${c.name}的言行，不替$user 说话、不替$user 做决定；记住之前发生过的事，前后一致。\n")
            append("用户用什么语言说话，就用什么语言回复。\n")
            if (c.description.isNotBlank()) append("\n【${c.name}的设定】\n").append(f(c.description)).append('\n')
            if (c.personality.isNotBlank()) append("\n【性格】\n").append(f(c.personality)).append('\n')
            if (c.scenario.isNotBlank()) append("\n【场景】\n").append(f(c.scenario)).append('\n')
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
