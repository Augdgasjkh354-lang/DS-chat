package com.jack.dschat

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jack.dschat.data.BackgroundStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jack.dschat.data.ChatStore
import com.jack.dschat.data.Conversation
import com.jack.dschat.data.DeepSeek
import com.jack.dschat.data.Message
import com.jack.dschat.data.titleFrom
import com.jack.dschat.ui.Chinese
import com.jack.dschat.ui.English
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val backgrounds = BackgroundStore(app)

    var conversations by mutableStateOf(listOf<Conversation>())
        private set

    /** 当前打开的对话；null 是还没发过消息的新对话 */
    var currentId by mutableStateOf<String?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    /** 正在上网做的事（「正在搜索：…」），生成时顶在回复上面；null 是没在搜 */
    var activity by mutableStateOf<String?>(null)
        private set

    /** DeepSeek 开放平台的 API Key，只存在这台手机上 */
    var apiKey by mutableStateOf(prefs.getString("apiKey", "").orEmpty())
        private set

    // 设置
    /** 0 跟随系统，1 浅色，2 深色 */
    var themeMode by mutableIntStateOf(prefs.getInt("theme", 0))
        private set
    var fontScale by mutableFloatStateOf(prefs.getFloat("font", 1f))
        private set
    var english by mutableStateOf(prefs.getBoolean("english", false))
        private set
    var think by mutableStateOf(prefs.getBoolean("think", false))
        private set
    var search by mutableStateOf(prefs.getBoolean("search", false))
        private set

    /** 聊天背景图；null 是没设，用纯色 */
    var background by mutableStateOf<ImageBitmap?>(null)
        private set
    /** 盖在背景图上的遮罩有多浓，0 到 0.9 */
    var backgroundDim by mutableFloatStateOf(prefs.getFloat("bgDim", 0.55f))
        private set
    /** 背景图的模糊程度，0 到 1 */
    var backgroundBlur by mutableFloatStateOf(prefs.getFloat("bgBlur", 0f))
        private set

    private var job: Job? = null

    val current: Conversation? get() = conversations.firstOrNull { it.id == currentId }

    init {
        viewModelScope.launch { background = withContext(Dispatchers.IO) { backgrounds.load()?.asImageBitmap() } }
        viewModelScope.launch {
            conversations = withContext(Dispatchers.IO) {
                store.load().orEmpty()
            }
        }
    }

    fun updateTheme(mode: Int) { themeMode = mode; prefs.edit().putInt("theme", mode).apply() }
    fun updateFontScale(scale: Float) { fontScale = scale; prefs.edit().putFloat("font", scale).apply() }
    fun updateEnglish(on: Boolean) { english = on; prefs.edit().putBoolean("english", on).apply() }
    fun updateApiKey(key: String) { apiKey = key.trim(); prefs.edit().putString("apiKey", apiKey).apply() }

    /** 查余额（顺便验证 Key）。成功给余额文字，失败给错误说明 */
    fun checkBalance(key: String, onResult: (ok: Boolean, text: String) -> Unit) {
        val strings = if (english) English else Chinese
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeepSeek.balance(key.trim()) } }
            result.fold(
                onSuccess = { onResult(true, strings.balance(it)) },
                onFailure = { e ->
                    onResult(false, if (e is DeepSeek.ApiException) strings.apiError(e.code, e.message.orEmpty()) else strings.networkError)
                },
            )
        }
    }

    fun toggleThink() { think = !think; prefs.edit().putBoolean("think", think).apply() }
    fun toggleSearch() { search = !search; prefs.edit().putBoolean("search", search).apply() }

    /** 换背景图。图片读不出来时调 onFail，原来的背景不动 */
    fun pickBackground(uri: Uri, onFail: () -> Unit) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { backgrounds.import(uri)?.asImageBitmap() }
            if (picked == null) onFail() else background = picked
        }
    }

    fun clearBackground() {
        background = null
        viewModelScope.launch(Dispatchers.IO) { backgrounds.clear() }
    }

    fun updateBackgroundDim(value: Float) { backgroundDim = value; prefs.edit().putFloat("bgDim", value).apply() }
    fun updateBackgroundBlur(value: Float) { backgroundBlur = value; prefs.edit().putFloat("bgBlur", value).apply() }

    fun newChat() {
        stop()
        currentId = null
    }

    fun open(id: String) {
        if (id == currentId) return
        stop()
        currentId = id
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || generating) return
        val now = System.currentTimeMillis()
        val userMessage = Message(UUID.randomUUID().toString(), "user", content, time = now)
        val id = currentId ?: UUID.randomUUID().toString()
        val existing = conversations.firstOrNull { it.id == id }
        conversations = if (existing == null) {
            listOf(Conversation(id, titleFrom(content), now, listOf(userMessage))) + conversations
        } else {
            conversations.map { if (it.id == id) it.copy(updatedAt = now, messages = it.messages + userMessage) else it }
        }
        currentId = id
        answer(id)
    }

    /** 把最后一条回复删掉，重新回答一遍 */
    fun regenerate() {
        val conversation = current ?: return
        if (generating) return
        val last = conversation.messages.lastOrNull() ?: return
        if (conversation.messages.none { it.isUser }) return
        if (!last.isUser) {
            conversations = conversations.map {
                if (it.id == conversation.id) it.copy(messages = it.messages.dropLast(1)) else it
            }
        }
        answer(conversation.id)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun answer(conversationId: String) {
        val conversation = conversations.firstOrNull { it.id == conversationId } ?: return
        val history = conversation.messages
        val messageId = UUID.randomUUID().toString()
        val text = if (english) English else Chinese
        append(conversationId, Message(messageId, "assistant", "", time = System.currentTimeMillis()))
        val key = apiKey
        if (key.isBlank()) {
            update(conversationId, messageId) { it.copy(error = text.noKey) }
            save()
            return
        }
        // 第一轮让模型顺手起个标题；用户手动改过名字的不动
        val wantTitle = history.count { it.isUser } == 1
        val body = DeepSeek.requestBody(history, think, search, wantTitle)
        generating = true
        job = viewModelScope.launch {
            var thinkStart = 0L
            try {
                DeepSeek.stream(key, body).collect { event ->
                    when (event) {
                        is DeepSeek.Event.Reasoning -> {
                            if (thinkStart == 0L) thinkStart = System.currentTimeMillis()
                            update(conversationId, messageId) { it.copy(reasoning = it.reasoning + event.text) }
                        }
                        is DeepSeek.Event.Content -> update(conversationId, messageId) {
                            // 正文开始出来，思考就算结束了
                            val seconds = if (it.reasoning.isNotEmpty() && it.thinkSeconds == 0) elapsed(thinkStart) else it.thinkSeconds
                            it.copy(text = it.text + event.text, thinkSeconds = seconds)
                        }
                        is DeepSeek.Event.ToolStarted -> {
                            val what = event.queries.ifEmpty { listOfNotNull(event.url) }
                            activity = when {
                                event.queries.isNotEmpty() -> text.searching(event.queries.joinToString("、"))
                                event.url != null -> text.reading(event.url)
                                else -> text.searchingWeb
                            }
                            update(conversationId, messageId) { it.copy(searches = it.searches + what) }
                        }
                        is DeepSeek.Event.ToolDone -> activity = null
                        is DeepSeek.Event.Title -> if (wantTitle) {
                            conversations = conversations.map { if (it.id == conversationId) it.copy(title = event.title) else it }
                        }
                        is DeepSeek.Event.Finish -> update(conversationId, messageId) {
                            it.copy(
                                sources = event.cited,
                                toolCalls = event.toolCalls,
                                error = when (event.reason) {
                                    "content_filter" -> text.refused
                                    "length" -> text.truncated
                                    else -> ""
                                },
                            )
                        }
                        is DeepSeek.Event.Failure -> update(conversationId, messageId) { it.copy(error = text.failed(event.message)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeepSeek.ApiException) {
                update(conversationId, messageId) { it.copy(error = text.apiError(e.code, e.message.orEmpty())) }
            } catch (e: Exception) {
                update(conversationId, messageId) { it.copy(error = text.networkError) }
            } finally {
                // 被停止时也走这里：已经出来的字留着，存盘
                generating = false
                activity = null
                update(conversationId, messageId) {
                    if (it.reasoning.isNotEmpty() && it.thinkSeconds == 0) it.copy(thinkSeconds = elapsed(thinkStart)) else it
                }
                save()
            }
        }
    }

    private fun elapsed(start: Long) =
        if (start == 0L) 1 else ((System.currentTimeMillis() - start) / 1000).toInt().coerceAtLeast(1)

    private fun append(conversationId: String, message: Message) {
        conversations = conversations.map { if (it.id == conversationId) it.copy(messages = it.messages + message) else it }
    }

    private fun update(conversationId: String, messageId: String, change: (Message) -> Message) {
        conversations = conversations.map { c ->
            if (c.id != conversationId) c
            else c.copy(messages = c.messages.map { if (it.id == messageId) change(it) else it })
        }
    }

    fun rename(id: String, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        conversations = conversations.map { if (it.id == id) it.copy(title = name) else it }
        save()
    }

    fun delete(ids: Set<String>) {
        if (currentId in ids) newChat()
        conversations = conversations.filterNot { it.id in ids }
        save()
    }

    fun clearAll() {
        newChat()
        conversations = emptyList()
        save()
    }

    private fun save() {
        val snapshot = conversations
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }
}
