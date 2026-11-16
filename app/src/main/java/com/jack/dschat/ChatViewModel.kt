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
import com.jack.dschat.data.Demo
import com.jack.dschat.data.Message
import com.jack.dschat.data.titleFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
                store.load() ?: Demo.seed(System.currentTimeMillis()).also { store.save(it) }
            }
        }
    }

    fun updateTheme(mode: Int) { themeMode = mode; prefs.edit().putInt("theme", mode).apply() }
    fun updateFontScale(scale: Float) { fontScale = scale; prefs.edit().putFloat("font", scale).apply() }
    fun updateEnglish(on: Boolean) { english = on; prefs.edit().putBoolean("english", on).apply() }
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
        answer(id, content)
    }

    /** 把最后一条回复删掉，重新回答一遍 */
    fun regenerate() {
        val conversation = current ?: return
        if (generating) return
        val last = conversation.messages.lastOrNull() ?: return
        val question = conversation.messages.lastOrNull { it.isUser } ?: return
        if (!last.isUser) {
            conversations = conversations.map {
                if (it.id == conversation.id) it.copy(messages = it.messages.dropLast(1)) else it
            }
        }
        answer(conversation.id, question.text)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun answer(conversationId: String, question: String) {
        val messageId = UUID.randomUUID().toString()
        val withThinking = think
        val reasoning = Demo.reasoning(question, english)
        val reply = Demo.reply(question, english, search)
        append(conversationId, Message(messageId, "assistant", "", time = System.currentTimeMillis()))
        generating = true
        job = viewModelScope.launch {
            try {
                delay(400)
                if (withThinking) {
                    val start = System.currentTimeMillis()
                    for (piece in reasoning.chunked(2)) {
                        delay(24)
                        update(conversationId, messageId) { it.copy(reasoning = it.reasoning + piece) }
                    }
                    val seconds = ((System.currentTimeMillis() - start) / 1000).toInt().coerceAtLeast(1)
                    update(conversationId, messageId) { it.copy(thinkSeconds = seconds) }
                }
                for (piece in reply.chunked(2)) {
                    delay(24)
                    update(conversationId, messageId) { it.copy(text = it.text + piece) }
                }
            } finally {
                // 被停止时也走这里：已经出来的字留着，存盘
                generating = false
                update(conversationId, messageId) { if (it.reasoning.isNotEmpty() && it.thinkSeconds == 0) it.copy(thinkSeconds = 1) else it }
                save()
            }
        }
    }

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
