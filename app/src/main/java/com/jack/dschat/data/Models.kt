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
) {
    val isUser get() = role == "user"
}

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<Message> = emptyList(),
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
