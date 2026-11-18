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
import com.jack.dschat.data.DeepSeekFiles
import com.jack.dschat.data.ImageStore
import kotlinx.serialization.json.JsonObject
import com.jack.dschat.data.Character
import com.jack.dschat.data.CharacterCard
import com.jack.dschat.data.CardException
import com.jack.dschat.data.CharacterStore
import com.jack.dschat.data.Persona
import com.jack.dschat.data.Lore
import com.jack.dschat.data.Lorebook
import com.jack.dschat.data.RoleMemory
import com.jack.dschat.data.RolePrompt
import com.jack.dschat.data.decodeImage
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.jack.dschat.data.HistoryImport
import com.jack.dschat.data.Speaker
import com.jack.dschat.data.Tts
import android.widget.Toast
import kotlinx.coroutines.isActive
import com.jack.dschat.data.Message
import com.jack.dschat.data.titleFrom
import com.jack.dschat.data.switchTo
import com.jack.dschat.data.startNewVersion
import com.jack.dschat.data.finishVersion
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
    private val speaker = Speaker(app)
    private val images = ImageStore(app)
    private val files = DeepSeekFiles(app.filesDir)

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

    // 朗读
    private var speakJob: Job? = null
    /** 正在念的消息 ID（试听音色时是 "sample"） */
    var speakingId by mutableStateOf<String?>(null)
        private set
    /** 新回复写完自动念 */
    var autoRead by mutableStateOf(prefs.getBoolean("autoRead", false))
        private set
    var voice by mutableStateOf(prefs.getString("voice", Tts.DEFAULT_VOICE).takeIf { it in Tts.VOICES } ?: Tts.DEFAULT_VOICE)
        private set

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
                    runCatching { decodeImage(getApplication(), avatar, CharacterStore.FULL * 2)?.let { roles.saveAvatar(c.id, it) } }.getOrNull()
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

    /** 读一个世界书文件（酒馆世界书 JSON，或带世界书的角色卡），交给编辑页加到角色里 */
    fun readLorebook(uri: Uri, onResult: (Lorebook?, String) -> Unit) {
        val strings = if (english) English else Chinese
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw CardException(strings.notALore)
                    if (bytes.size > 20 * 1024 * 1024) throw CardException(strings.notALore)
                    Lore.parseFile(bytes, strings.notALore)
                }
            }
            result.fold(
                onSuccess = { onResult(it, strings.loreImported(it.entries.size)) },
                onFailure = { e -> onResult(null, (e as? CardException)?.message ?: strings.notALore) },
            )
        }
    }

    /**
     * 把角色导出成酒馆 PNG 角色卡，写到用户选的位置。图是头像（avatar 是编辑页里刚选、还没保存的图），
     * 没头像就画一张底色加名字首字的图。世界书跟着卡一起
     */
    fun exportCard(c: Character, avatar: Uri?, target: Uri, onResult: (Boolean, String) -> Unit) {
        val strings = if (english) English else Chinese
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val pic = avatar?.let { decodeImage(app, it, CharacterStore.FULL * 2) }?.let { square(it) }?.let { if (it.width > CharacterStore.FULL) Bitmap.createScaledBitmap(it, CharacterStore.FULL, CharacterStore.FULL, true) else it }
                        ?: (if (c.hasAvatar) roles.loadFullAvatar(c.id) else null)
                        ?: letterAvatar(c)
                    val png = java.io.ByteArrayOutputStream().also { pic.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    val card = CharacterCard.toPng(png, c)
                    app.contentResolver.openOutputStream(target, "wt")?.use { it.write(card) } ?: error("no output")
                }.isSuccess
            }
            onResult(ok, if (ok) strings.cardExported(c.name) else strings.exportFailed)
        }
    }

    /** 把世界书导出成酒馆的世界书 JSON */
    fun exportLorebook(book: Lorebook, name: String, target: Uri, onResult: (Boolean, String) -> Unit) {
        val strings = if (english) English else Chinese
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(target, "wt")
                        ?.use { it.write(Lore.worldInfo(book, name).toByteArray()) } ?: error("no output")
                }.isSuccess
            }
            onResult(ok, if (ok) strings.loreExported(book.entries.size) else strings.exportFailed)
        }
    }

    private fun square(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    /** 没头像的角色：512 像素的底色方图，正中写名字的第一个字 */
    private fun letterAvatar(c: Character): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(c.color.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.45f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(c.name.take(1).ifEmpty { "?" }, size / 2f, y, paint)
        return bmp
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

    fun toggleAutoRead() {
        autoRead = !autoRead
        prefs.edit().putBoolean("autoRead", autoRead).apply()
        if (!autoRead) stopSpeaking()
        val strings = if (english) English else Chinese
        Toast.makeText(getApplication(), if (autoRead) strings.autoReadOn else strings.autoReadOff, Toast.LENGTH_SHORT).show()
    }

    /** 换音色，顺便念一句试听 */
    fun updateVoice(v: String) {
        voice = v
        prefs.edit().putString("voice", v).apply()
        speakText("sample", (if (english) English else Chinese).voiceSample)
    }

    /** 点回复下面的喇叭：没在念就念，正在念就停 */
    fun toggleSpeak(m: Message) {
        if (speakingId == m.id) stopSpeaking() else speakText(m.id, m.text)
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        speaker.stop()
        speakingId = null
    }

    private fun speakText(id: String, text: String) {
        stopSpeaking()
        val strings = if (english) English else Chinese
        val key = apiKey
        if (key.isBlank()) {
            Toast.makeText(getApplication(), strings.noKey, Toast.LENGTH_LONG).show()
            return
        }
        speakingId = id
        var me: Job? = null
        me = viewModelScope.launch {
            try {
                speaker.speak(key, text, voice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeepSeek.ApiException) {
                Toast.makeText(getApplication(), strings.speakError(strings.apiError(e.code, e.message.orEmpty())), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), strings.speakError(strings.networkError), Toast.LENGTH_LONG).show()
            } finally {
                // 被新的朗读顶掉时，speakingId 已经是新的那条了，别清掉
                if (speakJob === me) { speakJob = null; speakingId = null }
            }
        }
        speakJob = me
    }

    override fun onCleared() {
        stopSpeaking()
        super.onCleared()
    }

    fun newChat() {
        stopSpeaking()
        stop()
        currentId = null
    }

    fun open(id: String) {
        if (id == currentId) return
        stopSpeaking()
        stop()
        currentId = id
    }

    fun send(text: String) {
        val content = text.trim()
        val images = pendingImages
        if ((content.isEmpty() && images.isEmpty()) || generating) return
        pendingImages = emptyList()
        stopSpeaking()
        val now = System.currentTimeMillis()
        val userMessage = Message(UUID.randomUUID().toString(), "user", content, time = now, images = images)
        val id = currentId ?: UUID.randomUUID().toString()
        val existing = conversations.firstOrNull { it.id == id }
        conversations = if (existing == null) {
            val title = titleFrom(content).ifEmpty { (if (english) English else Chinese).imageTitle }
            listOf(Conversation(id, title, now, listOf(userMessage))) + conversations
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
        stopSpeaking()
        // 最后一条是回复：旧的留着当一个版本，新生成的是下一版，可以左右切换
        answer(conversation.id, regenerating = if (last.isUser) null else last)
    }

    /** 最后一条回复换到上一版 / 下一版（delta = -1 / +1） */
    fun switchVersion(messageId: String, delta: Int) {
        if (generating) return
        val c = current ?: return
        if (c.messages.lastOrNull()?.id != messageId) return
        conversations = conversations.map { conv ->
            if (conv.id != c.id) conv else conv.copy(messages = conv.messages.map { if (it.id == messageId) it.switchTo(it.active + delta) else it })
        }
        save()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** regenerating 不为 null 时是给这条回复生成新的一版（写进同一条消息），否则在末尾加一条新回复 */
    private fun answer(conversationId: String, regenerating: Message? = null) {
        val conversation = conversations.firstOrNull { it.id == conversationId } ?: return
        val history = if (regenerating != null) conversation.messages.dropLast(1) else conversation.messages
        val messageId = regenerating?.id ?: UUID.randomUUID().toString()
        val previous = regenerating?.active ?: 0
        val text = if (english) English else Chinese
        if (regenerating != null) update(conversationId, messageId) { it.startNewVersion() }
        else append(conversationId, Message(messageId, "assistant", "", time = System.currentTimeMillis()))
        val key = apiKey
        if (key.isBlank()) {
            update(conversationId, messageId) { it.copy(error = text.noKey).let { m -> if (regenerating != null) m.finishVersion(previous) else m } }
            save()
            return
        }
        val role = character(conversation.characterId)
        // 第一轮让模型顺手起个标题（角色对话不起：标题就是角色名，而且带 title 时创意度会失效）
        val wantTitle = role == null && history.count { it.isUser } == 1
        val useThink = think
        val useSearch = search
        // 拼请求体；ids 是已经传到 Files API 的图片（路径 → file_id），其余图片内联发送。要读图片文件，在后台线程调
        fun build(ids: Map<String, String>): String {
            val image: (String) -> JsonObject? = { p -> ids[p]?.let(DeepSeek::fileImage) ?: DeepSeek.inlineImage(p) }
            if (role == null) return DeepSeek.requestBody(history, useThink, useSearch, wantTitle, image = image)
            // 角色对话：系统提示里放角色设定和前情回忆，整理进回忆的旧消息不再发
            // 世界书：最近几条消息里提到了哪些关键词，就把对应的设定带上
            val lore = Lore.activate(role.book, history.map { it.text })
            val system = RolePrompt.system(role, persona, conversation.memory, text.defaultUser, lore)
            return DeepSeek.requestBody(
                history.drop(conversation.memoryUpTo.coerceIn(0, history.size)), conversation.think, conversation.search,
                wantTitle = false, system = system, temperature = role.creativity, readUrl = false, image = image,
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
                // 图片先传到 Files API（传过的直接用 file_id），之后每轮不用重传整张图
                var ids = withContext(Dispatchers.IO) { files.ensure(key, history.flatMap { it.images }) }
                var body = withContext(Dispatchers.IO) { build(ids) }
                var retried = false
                while (true) try {
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
                break
                } catch (e: DeepSeek.ApiException) {
                    // 传过的图片在服务端过期或被删了：清掉记录，改成内联再试一次
                    val fileProblem = e.code in 400..404 && e.message.orEmpty().contains("file", ignoreCase = true)
                    if (retried || ids.isEmpty() || content.isNotEmpty() || reasoning.isNotEmpty() || !fileProblem) throw e
                    retried = true
                    files.forget()
                    ids = emptyMap()
                    body = withContext(Dispatchers.IO) { build(ids) }
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
                    val m = if (it.reasoning.isNotEmpty() && it.thinkSeconds == 0) it.copy(thinkSeconds = elapsed(thinkStart)) else it
                    if (regenerating != null) m.finishVersion(previous) else m
                }
                save()
                // 开了自动朗读：好好写完的回复（不是被停掉、没出错）念出来；还停在这个对话才念
                if (autoRead && isActive && currentId == conversationId) {
                    conversations.firstOrNull { it.id == conversationId }?.messages?.firstOrNull { it.id == messageId }
                        ?.takeIf { it.error.isEmpty() && it.text.isNotBlank() }?.let { speakText(it.id, it.text) }
                }
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
        val removed = conversations.filter { it.id in ids }
        conversations = conversations.filterNot { it.id in ids }
        save()
        deleteImagesOf(removed)
    }

    fun clearAll() {
        newChat()
        val removed = conversations
        conversations = emptyList()
        save()
        deleteImagesOf(removed)
    }

    /** 对话删了，里面发过的图片文件也删掉 */
    private fun deleteImagesOf(list: List<Conversation>) {
        val paths = list.flatMap { c -> c.messages.flatMap { it.images } }
        if (paths.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { images.delete(paths) }
    }

    /** 选好、还没发出去的图片 */
    var pendingImages by mutableStateOf(listOf<String>())
        private set
    var importingImages by mutableStateOf(false)
        private set

    /** 从相册选了图：缩小存好，放进待发送 */
    fun addImages(uris: List<Uri>, onFail: () -> Unit) {
        if (uris.isEmpty()) return
        importingImages = true
        viewModelScope.launch {
            val paths = withContext(Dispatchers.IO) { uris.take(MAX_IMAGES).map { images.import(it) } }
            importingImages = false
            if (paths.any { it == null }) onFail()
            pendingImages = (pendingImages + paths.filterNotNull()).take(MAX_IMAGES)
        }
    }

    fun removePending(path: String) {
        pendingImages = pendingImages - path
        viewModelScope.launch(Dispatchers.IO) { images.delete(listOf(path)) }
    }

    private fun save() {
        val snapshot = conversations
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }

    private companion object {
        /** 没头像的角色的底色 */
        /** 一条消息最多带几张图 */
        const val MAX_IMAGES = 9
        val PALETTE = listOf(0xFF5B7CFA, 0xFFE58A6E, 0xFF4FA88B, 0xFF9B6FD6, 0xFFF2A65A, 0xFF4A6FA5, 0xFFD46A8C, 0xFF8D7B68)
    }
}
