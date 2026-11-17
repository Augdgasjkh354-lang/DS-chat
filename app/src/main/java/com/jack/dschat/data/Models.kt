package com.jack.dschat.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Serializable
data class Message(
    val id: String,
    /** "user" 或 "assistant" */
    val role: String,
    val text: String,
    /** 深度思考的内容，没开就是空的 */
    val reasoning: String = "",
    val thinkSeconds: Int = 0,
    val time: Long = 0,
    /** 回答引用的网页 */
    val sources: List<Source> = emptyList(),
    /** 这次上网做了什么：搜索词或读过的网址，按先后 */
    val searches: List<String> = emptyList(),
    /** 接口返回的 server_tool_calls 原文，下一轮要原样传回去 */
    val toolCalls: String = "",
    /** 出错时给人看的说明；空的是没出错 */
    val error: String = "",
    /** 这一轮（提问 + 这条回复）不发给模型当上下文：导入的出错、被拒、服务端模板回复 */
    val noContext: Boolean = false,
    /** 用户发的图片：应用私有目录里的 JPEG 路径 */
    val images: List<String> = emptyList(),
    /**
     * 回复的所有版本（重新生成过才有，至少两个）；上面那些字段是正在显示的那一版，active 是它的序号。
     * 发给模型的只有正在显示的那一版
     */
    val versions: List<Reply> = emptyList(),
    val active: Int = 0,
) {
    val isUser get() = role == "user"

    /** 正在显示的这一版存成 Reply */
    fun snapshot() = Reply(text, reasoning, thinkSeconds, sources, searches, toolCalls, error, noContext)

    /** 换成某一版 */
    fun show(r: Reply, index: Int) = copy(
        text = r.text, reasoning = r.reasoning, thinkSeconds = r.thinkSeconds, sources = r.sources, searches = r.searches,
        toolCalls = r.toolCalls, error = r.error, noContext = r.noContext, active = index,
    )

    /** 一共几版（没重新生成过就是 1） */
    val versionCount get() = versions.size.coerceAtLeast(1)
}

/** 回复的一个版本 */
@Serializable
data class Reply(
    val text: String = "",
    val reasoning: String = "",
    val thinkSeconds: Int = 0,
    val sources: List<Source> = emptyList(),
    val searches: List<String> = emptyList(),
    val toolCalls: String = "",
    val error: String = "",
    val noContext: Boolean = false,
)

/** 换到第 index 版（越界就不动）；当前显示的这版先存回 versions，免得丢 */
fun Message.switchTo(index: Int): Message {
    if (versions.size < 2 || index !in versions.indices || index == active) return this
    val saved = versions.toMutableList().also { it[active.coerceIn(0, it.lastIndex)] = snapshot() }
    return copy(versions = saved).show(saved[index], index)
}

/** 重新生成前：把现有的版本都存好，清空正文，active 指向即将生成的新一版 */
fun Message.startNewVersion(): Message {
    val saved = if (versions.isEmpty()) listOf(snapshot()) else versions.toMutableList().also { it[active.coerceIn(0, it.lastIndex)] = snapshot() }
    return copy(versions = saved).show(Reply(), saved.size)
}

/**
 * 新一版生成完（或被停）：有内容就加进 versions 并显示它；什么都没出来就退回之前显示的那一版
 */
fun Message.finishVersion(previous: Int): Message {
    if (versions.isEmpty()) return this
    val fresh = snapshot()
    if (fresh.text.isEmpty() && fresh.reasoning.isEmpty() && fresh.error.isEmpty()) {
        return show(versions[previous.coerceIn(0, versions.lastIndex)], previous.coerceIn(0, versions.lastIndex))
    }
    val all = versions + fresh
    return copy(versions = if (all.size >= 2) all else emptyList(), active = all.lastIndex)
}

@Serializable
data class Source(val url: String, val title: String = "", val site: String = "")

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<Message> = emptyList(),
    /** 角色扮演对话：扮演的角色；null 是普通对话 */
    val characterId: String? = null,
    /** 角色对话的「前情回忆」：更早的对话整理成的摘要，用户能看能改 */
    val memory: String = "",
    /** 前面这么多条消息已经整理进回忆，不再原样发给模型 */
    val memoryUpTo: Int = 0,
    /** 角色对话自己的深度思考、联网开关（默认关：带工具时创意度不生效） */
    val think: Boolean = false,
    val search: Boolean = false,
)

/** 侧边栏里历史记录的分组 */
sealed interface Group {
    data object Today : Group
    data object Yesterday : Group
    data object Week : Group
    data object Month : Group
    data class YearMonth(val year: Int, val month: Int) : Group
}

/** 按「距今天几天」分组：今天、昨天、7 天内、30 天内，再早的按年月 */
fun groupOf(now: Long, time: Long, zone: ZoneId = ZoneId.systemDefault()): Group {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> Group.Today
        days == 1L -> Group.Yesterday
        days < 7 -> Group.Week
        days < 30 -> Group.Month
        else -> Group.YearMonth(day.year, day.monthValue)
    }
}

/** 搜索：标题或任意一条消息里包含关键词（不分大小写） */
fun Conversation.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) || messages.any { it.text.contains(q, ignoreCase = true) }
}

/** 新对话的标题：取第一句话的前 20 个字 */
fun titleFrom(text: String): String {
    val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length > 20) line.take(20) + "…" else line
}
