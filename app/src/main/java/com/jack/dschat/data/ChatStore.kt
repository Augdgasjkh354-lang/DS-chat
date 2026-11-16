package com.jack.dschat.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 聊天记录存成应用私有目录里的一个 JSON 文件 */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chats.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Conversation.serializer())

    /** 还没存过返回 null（第一次打开，用来决定要不要放演示数据） */
    fun load(): List<Conversation>? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(list: List<Conversation>) {
        // 先写临时文件再改名，写到一半被杀也不会留下坏文件
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(file)
    }
}
