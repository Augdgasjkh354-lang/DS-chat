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
import com.jack.dschat.data.Character
import com.jack.dschat.data.CharacterCard
import com.jack.dschat.data.CardException
import com.jack.dschat.data.CharacterStore
import com.jack.dschat.data.Persona
import com.jack.dschat.data.RoleMemory
import com.jack.dschat.data.RolePrompt
import com.jack.dschat.data.decodeImage
import android.graphics.BitmapFactory
import com.jack.dschat.data.HistoryImport
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
    private val roles = CharacterStore(app)

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

    /** 正在导入历史对话 */
    var importing by mutableStateOf(false)
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

    // 角色扮演
    var characters by mutableStateOf(listOf<Character>())
        private set
    var persona by mutableStateOf(Persona())
        private set
    /** 角色头像，按角色 ID */
    var avatars by mutableStateOf(mapOf<String, ImageBitmap>())
        private set
    /** 正在整理回忆的对话 ID */
    var remembering by mutableStateOf<String?>(null)
        private set

    fun character(id: String?): Character? = id?.let { i -> characters.firstOrNull { it.id == i } }
    /** 当前对话扮演的角色；角色被删了就当普通对话 */
    val currentCharacter: Character? get() = character(current?.characterId)

    /** 输入框上的两个开关：角色对话用对话自己的（默认关），普通对话用全局的 */
    val thinkOn: Boolean get() = if (currentCharacter != null) current!!.think else think
    val searchOn: Boolean get() = if (currentCharacter != null) current!!.search else search

    init {
        viewModelScope.launch { background = withContext(Dispatchers.IO) { backgrounds.load()?.asImageBitmap() } }
        viewModelScope.launch {
            conversations = withContext(Dispatchers.IO) {
                store.load().orEmpty()
            }
        }
        viewModelScope.launch {
            val (saved, pics) = withContext(Dispatchers.IO) {
                val saved = roles.load()
                saved to saved.characters.filter { it.hasAvatar }.mapNotNull { c -> roles.loadAvatar(c.id)?.let { c.id to it.asImageBitmap() } }.toMap()
            }
            characters = saved.characters
            persona = saved.persona
            avatars = pics
        }
    }

    private fun saveRoles() {
        val snapshot = CharacterStore.Saved(characters, persona)
        viewModelScope.launch(Dispatchers.IO) { roles.save(snapshot) }
    }

    fun updatePersona(p: Persona) { persona = p; saveRoles() }

    /** 新建或修改角色；avatar 不为 null 时换头像（相册里选的图） */
    fun saveCharacter(c: Character, avatar: Uri?, onFail: () -> Unit = {}) {
        viewModelScope.launch {
            var saved = c
            if (avatar != null) {
                val pic = withContext(Dispatchers.IO) {
                    runCatching { decodeImage(getApplication(), avatar, 1024)?.let { roles.saveAvatar(c.id, it) } }.getOrNull()
                }
                if (pic == null) onFail() else {
                    saved = saved.copy(hasAvatar = true)
                    avatars = avatars + (c.id to pic.asImageBitmap())
                }
            }
            characters = if (characters.any { it.id == c.id }) characters.map { if (it.id == c.id) saved else it } else characters + saved
            saveRoles()
        }
    }

    /** 删角色：用它聊过的对话留着，变成普通对话 */
    fun deleteCharacter(id: String) {
        characters = characters.filterNot { it.id == id }
        avatars = avatars - id
        saveRoles()
        viewModelScope.launch(Dispatchers.IO) { roles.deleteAvatar(id) }
    }

    /** 导入「酒馆」角色卡（PNG 或 JSON）。onResult(成功与否, 说明) */
    fun importCard(uri: Uri, onResult: (Boolean, String) -> Unit) {
        val strings = if (english) English else Chinese
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw CardException(strings.notACard)
                    if (bytes.size > 20 * 1024 * 1024) throw CardException(strings.notACard)
                    val id = CharacterStore.newId()
                    val card = CharacterCard.parse(bytes, strings.notACard)
                    // PNG 卡片本身就是头像
                    val pic = if (CharacterCard.isPng(bytes)) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { roles.saveAvatar(id, it) }
                    } else null
                    Triple(card.copy(id = id, hasAvatar = pic != null, color = palette(id), createdAt = System.currentTimeMillis()), pic, id)
                }
            }
            result.fold(
                onSuccess = { (card, pic, id) ->
                    if (pic != null) avatars = avatars + (id to pic.asImageBitmap())
                    characters = characters + card
                    saveRoles()
                    onResult(true, strings.cardImported(card.name))
                },
                onFailure = { e -> onResult(false, (e as? CardException)?.message ?: strings.notACard) },
            )
        }
    }

    /** 没头像的角色用的底色，按 ID 挑一个 */
    fun palette(id: String): Long = PALETTE[(id.hashCode() and 0x7fffffff) % PALETTE.size]

    /** 和角色开一段新对话：角色先说开场白 */
    fun startRoleChat(c: Character) {
        stop()
        val now = System.currentTimeMillis()
        val user = RolePrompt.userName(persona, (if (english) English else Chinese).defaultUser)
        val greeting = RolePrompt.fill(c.greeting, c.name, user).trim()
        val messages = if (greeting.isEmpty()) emptyList() else listOf(Message(UUID.randomUUID().toString(), "assistant", greeting, time = now))
        val conversation = Conversation(UUID.randomUUID().toString(), c.name, now, messages, characterId = c.id)
        conversations = listOf(conversation) + conversations
        currentId = conversation.id
        save()
    }

    /** 用户改了回忆 */
    fun updateMemory(conversationId: String, text: String) {
        conversations = conversations.map { if (it.id == conversationId) it.copy(memory = text.trim()) else it }
        save()
    }

    /**
     * 对话长了就把最早的部分整理进回忆（每次角色回答完检查一次）；force 是用户点了「现在整理」。
     * 失败了不要紧，下次再试；回忆没整理好之前照常发完整对话
     */
    fun remember(conversationId: String, force: Boolean = false, onDone: (Boolean) -> Unit = {}) {
        if (remembering != null) return
        val c = conversations.firstOrNull { it.id == conversationId } ?: return
        val role = character(c.characterId) ?: return
        val end = RoleMemory.due(c, force) ?: return onDone(false)
        val key = apiKey.takeIf { it.isNotBlank() } ?: return onDone(false)
        val user = RolePrompt.userName(persona, (if (english) English else Chinese).defaultUser)
        val input = RoleMemory.input(c.memory, c.messages.subList(c.memoryUpTo.coerceIn(0, end), end), role.name, user)
        remembering = conversationId
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { DeepSeek.complete(key, RoleMemory.SYSTEM, input) }.getOrNull() }
            remembering = null
            if (text.isNullOrBlank()) return@launch onDone(false)
            conversations = conversations.map { if (it.id == conversationId) it.copy(memory = text, memoryUpTo = end) else it }
            save()
            onDone(true)
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

    /** 导入 DeepSeek 官方导出的历史对话 zip，合并进现有对话。onResult(成功与否, 给人看的说明) */
    fun importHistory(uri: Uri, onResult: (ok: Boolean, text: String) -> Unit) {
        if (importing) return
        importing = true
        val strings = if (english) English else Chinese
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw HistoryImport.ImportException(strings.notZip)
                    input.use { HistoryImport.read(it, strings) }
                }
            }
            result.fold(
                onSuccess = { r ->
                    // 正在回答的对话要被导入覆盖时先停下
                    if (generating && r.conversations.any { it.id == currentId }) stop()
                    val (merged, added, updated) = HistoryImport.merge(conversations, r.conversations)
                    conversations = merged
                    save()
                    onResult(true, strings.imported(added, updated, r.skipped))
                },
                onFailure = { e -> onResult(false, (e as? HistoryImport.ImportException)?.message ?: strings.notZip) },
            )
            importing = false
        }
    }

    fun toggleThink() {
        val c = current
        if (currentCharacter != null && c != null) {
            conversations = conversations.map { if (it.id == c.id) it.copy(think = !c.think) else it }
            save()
        } else { think = !think; prefs.edit().putBoolean("think", think).apply() }
    }

    fun toggleSearch() {
        val c = current
        if (currentCharacter != null && c != null) {
            conversations = conversations.map { if (it.id == c.id) it.copy(search = !c.search) else it }
            save()
        } else { search = !search; prefs.edit().putBoolean("search", search).apply() }
    }

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
        val role = character(conversation.characterId)
        // 第一轮让模型顺手起个标题（角色对话不起：标题就是角色名，而且带 title 时创意度会失效）
        val wantTitle = role == null && history.count { it.isUser } == 1
        val body = if (role == null) DeepSeek.requestBody(history, think, search, wantTitle) else {
            // 角色对话：系统提示里放角色设定和前情回忆，整理进回忆的旧消息不再发
            val system = RolePrompt.system(role, persona, conversation.memory, text.defaultUser)
            DeepSeek.requestBody(
                history.drop(conversation.memoryUpTo.coerceIn(0, history.size)), conversation.think, conversation.search,
                wantTitle = false, system = system, temperature = role.creativity, readUrl = false,
            )
        }
        generating = true
        job = viewModelScope.launch {
            var thinkStart = 0L
            var thinkSeconds = 0
            // 字先攒在这里，最多每 60 毫秒往界面上刷一次：每来几个字就复制一遍整个对话列表太费
            val reasoning = StringBuilder()
            val content = StringBuilder()
            var lastFlush = 0L
            fun flush() {
                lastFlush = System.currentTimeMillis()
                val r = reasoning.toString()
                val c = content.toString()
                update(conversationId, messageId) { it.copy(reasoning = r, text = c, thinkSeconds = thinkSeconds) }
            }
            fun maybeFlush() { if (System.currentTimeMillis() - lastFlush > 60) flush() }
            try {
                DeepSeek.stream(key, body).collect { event ->
                    when (event) {
                        is DeepSeek.Event.Reasoning -> {
                            if (thinkStart == 0L) thinkStart = System.currentTimeMillis()
                            reasoning.append(event.text)
                            maybeFlush()
                        }
                        is DeepSeek.Event.Content -> {
                            // 正文开始出来，思考就算结束了
                            if (reasoning.isNotEmpty() && thinkSeconds == 0) thinkSeconds = elapsed(thinkStart)
                            content.append(event.text)
                            maybeFlush()
                        }
                        is DeepSeek.Event.ToolStarted -> {
                            flush()
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
                        is DeepSeek.Event.Finish -> {
                            flush()
                            update(conversationId, messageId) {
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
                        }
                        is DeepSeek.Event.Failure -> {
                            flush()
                            update(conversationId, messageId) { it.copy(error = text.failed(event.message)) }
                        }
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
                flush()
                generating = false
                activity = null
                update(conversationId, messageId) {
                    if (it.reasoning.isNotEmpty() && it.thinkSeconds == 0) it.copy(thinkSeconds = elapsed(thinkStart)) else it
                }
                save()
                // 角色对话聊长了，悄悄把旧的部分整理进回忆
                if (role != null) remember(conversationId)
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

    private companion object {
        /** 没头像的角色的底色 */
        val PALETTE = listOf(0xFF5B7CFA, 0xFFE58A6E, 0xFF4FA88B, 0xFF9B6FD6, 0xFFF2A65A, 0xFF4A6FA5, 0xFFD46A8C, 0xFF8D7B68)
    }
}
