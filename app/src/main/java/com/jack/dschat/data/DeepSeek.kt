package com.jack.dschat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 开放平台的 Chat Completions 接口（流式）。
 * 联网用官方的服务端工具 deepseek_web_search / deepseek_read_url：搜索和读网页都在 DeepSeek 那边跑，App 只管展示。
 */
object DeepSeek {
    const val BASE = "https://api.deepseek.com"
    /** 联网搜索、读链接、自动起标题目前只有 Flash 支持 */
    const val MODEL = "deepseek-flash"

    private val json = Json { ignoreUnknownKeys = true }

    /** 流式输出里的一件事 */
    sealed interface Event {
        data class Reasoning(val text: String) : Event
        data class Content(val text: String) : Event
        /** 开始搜索或读网页：queries 是搜索词，url 是要读的网页 */
        data class ToolStarted(val queries: List<String>, val url: String?) : Event
        data class ToolDone(val sources: List<Source>) : Event
        data class Title(val title: String) : Event
        /** 最后一块：引用、完整的工具调用记录（下一轮要原样传回去）、结束原因 */
        data class Finish(val reason: String, val cited: List<Source>, val toolCalls: String) : Event
        data class Failure(val message: String) : Event
    }

    class ApiException(val code: Int, message: String) : IOException(message)

    /** 拼请求体。history 是到这次提问为止的所有消息（最后一条是用户的） */
    fun requestBody(
        history: List<Message>, think: Boolean, search: Boolean, wantTitle: Boolean,
        /** 角色扮演的系统提示 */
        system: String? = null,
        /** 创意度。带了联网、读链接工具或 title 时接口会忽略它 */
        temperature: Float? = null,
        /** 读链接工具：普通对话一直开着（贴了网址就能读）；角色对话不带，免得创意度失效 */
        readUrl: Boolean = true,
    ): String {
        val body = buildJsonObject {
            put("model", MODEL)
            put("stream", true)
            put("thinking", buildJsonObject { put("type", if (think) "enabled" else "disabled") })
            if (think) put("reasoning_effort", "high")
            val tools = buildJsonArray {
                if (search) add(buildJsonObject { put("type", "deepseek_web_search") })
                if (readUrl) add(buildJsonObject { put("type", "deepseek_read_url") })
            }
            if (tools.isNotEmpty()) put("tools", tools)
            if (temperature != null) put("temperature", temperature)
            if (wantTitle) put("title", buildJsonObject { put("enabled", true) })
            put("messages", buildJsonArray {
                if (system != null) add(buildJsonObject { put("role", "system"); put("content", system) })
                for ((i, m) in history.withIndex()) {
                    if (!m.isUser && (m.text.isEmpty() || m.noContext)) continue // 出错、被停在半路什么都没有的回复不发
                    // 回复不进上下文的那一轮，提问也不发
                    if (m.isUser && history.getOrNull(i + 1)?.let { !it.isUser && it.noContext } == true) continue
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.text)
                        if (!m.isUser) {
                            val calls = m.toolCalls.takeIf { it.isNotEmpty() }
                                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                            // 带 tools 的请求要把之前的思考内容传回去，否则会报 400；只传最后一步的
                            if (m.reasoning.isNotEmpty()) put("reasoning_content", lastStepReasoning(m.reasoning, calls))
                            if (calls != null) put("server_tool_calls", calls)
                        }
                    })
                }
            })
        }
        return body.toString()
    }

    /** 流式的思考是各步连在一起的，前面几步的思考在 server_tool_calls 里，减掉它们就是最后一步 */
    fun lastStepReasoning(all: String, calls: JsonElement?): String {
        val earlier = (calls as? JsonArray)?.sumOf { step ->
            step.jsonObject["reasoning_content"]?.jsonPrimitive?.contentOrNull?.length ?: 0
        } ?: 0
        return if (earlier in 1..all.length) all.substring(earlier) else all
    }

    /** 解析一行 SSE 的 data，返回其中的事件（一行里可能有好几个） */
    fun parse(data: String): List<Event> {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        root["error"]?.let { return listOf(Event.Failure(errorMessage(it))) }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return emptyList()
        val delta = choice["delta"] as? JsonObject
        val events = mutableListOf<Event>()
        delta?.string("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { events += Event.Reasoning(it) }
        delta?.string("content")?.takeIf { it.isNotEmpty() }?.let { events += Event.Content(it) }
        (choice["server_tool_event"] as? JsonObject)?.let { e ->
            val call = e["call"] as? JsonObject
            when (e.string("type")) {
                "call_started" -> {
                    val action = call?.get("action") as? JsonObject
                    val queries = (action?.get("queries") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    events += Event.ToolStarted(queries, action?.string("url"))
                }
                "call_completed" -> events += Event.ToolDone(call?.let { sourcesOf(it) }.orEmpty())
            }
        }
        delta?.string("title")?.takeIf { it.isNotBlank() }?.let { events += Event.Title(it.trim()) }
        choice.string("finish_reason")?.let { reason ->
            val calls = delta?.get("server_tool_calls") as? JsonArray
            events += Event.Finish(reason, citedSources(delta, calls), calls?.toString().orEmpty())
        }
        return events
    }

    /** 一次调用带回来的网页：搜索结果在 sources，读过的网页在 page */
    private fun sourcesOf(call: JsonObject): List<Source> {
        val list = (call["sources"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toSource() }.orEmpty()
        val page = (call["page"] as? JsonObject)?.toSource()
        return if (page != null) list + page else list
    }

    /** 回答里真正引用到的网页，按第一次被引用的顺序；标题、站名从搜索结果里按网址配 */
    private fun citedSources(delta: JsonObject?, calls: JsonArray?): List<Source> {
        val known = calls.orEmpty().flatMap { step ->
            (step.jsonObject["calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { sourcesOf(it) }
        }.associateBy { it.url }
        val annotations = (delta?.get("annotations") as? JsonArray).orEmpty()
        return annotations.mapNotNull { a ->
            val c = (a as? JsonObject)?.get("url_citation") as? JsonObject ?: return@mapNotNull null
            val url = c.string("url") ?: return@mapNotNull null
            known[url] ?: Source(url, c.string("title")?.takeIf { it != url }.orEmpty())
        }.distinctBy { it.url }
    }

    private fun JsonObject.toSource(): Source? {
        val url = string("url") ?: return null
        return Source(url, string("title").orEmpty(), string("site_name").orEmpty())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun errorMessage(e: JsonElement): String =
        ((e as? JsonObject)?.string("message") ?: (e as? JsonPrimitive)?.contentOrNull ?: e.toString())

    /** 发请求，一边收一边吐事件。停止（取消协程）时会立刻断开连接 */
    fun stream(apiKey: String, body: String): Flow<Event> = flow {
        val conn = open("$BASE/chat/completions", apiKey).apply {
            requestMethod = "POST"
            doOutput = true
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        // 读数据时线程是卡住的，取消时从别的线程断开连接，让它马上返回
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            checkStatus(conn)
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    for (event in parse(data)) emit(event)
                }
            }
        } finally {
            handle?.dispose()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** 不流式地问一句（整理回忆用），返回回答正文 */
    fun complete(apiKey: String, system: String, user: String): String {
        val body = buildJsonObject {
            put("model", MODEL)
            put("stream", false)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("temperature", 0.3)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString()
        val conn = open("$BASE/chat/completions", apiKey).apply {
            requestMethod = "POST"
            doOutput = true
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            checkStatus(conn)
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            root["error"]?.let { throw IOException(errorMessage(it)) }
            val message = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            return message?.string("content")?.trim().orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** 查余额，顺便验证 Key 能不能用。返回像「¥12.34」这样的文字 */
    fun balance(apiKey: String): String {
        val conn = open("$BASE/user/balance", apiKey)
        try {
            checkStatus(conn)
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val info = (root["balance_infos"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return ""
            val symbol = when (info.string("currency")) { "CNY" -> "¥"; "USD" -> "$"; else -> "" }
            return symbol + info.string("total_balance").orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, apiKey: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Authorization", "Bearer $apiKey")
    }

    private fun checkStatus(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val text = conn.errorStream?.bufferedReader()?.readText().orEmpty()
        val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.let(::errorMessage) }.getOrNull()
        throw ApiException(code, message ?: text.take(200))
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
