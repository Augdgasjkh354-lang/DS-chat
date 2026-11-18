package com.jack.dschat.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection

/**
 * DeepSeek 的朗读接口（POST /v1/tools/tts）：发文字，边合成边回 24 kHz、16 位、单声道的 PCM。
 * 一次最多 1 万计费字符（汉字算 2 个），长回复切成几段依次念。
 */
object Tts {
    const val SAMPLE_RATE = 24_000
    /** 每段的计费字符上限，留点余量 */
    const val SEGMENT_BILLED = 4_000
    val VOICES = listOf("stella", "tide")
    const val DEFAULT_VOICE = "stella"

    /** 接口的计费字数：汉字（和别的非 ASCII 字符）按 2 算 */
    fun billed(s: String) = s.sumOf { if (it.code < 128) 1L else 2L }.toInt()

    /** 准备念的文字：代码块念出来没意义，去掉；多余空行收一收 */
    fun readable(text: String): String = text
        .replace(Regex("```[\\s\\S]*?(```|$)"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** 切成每段不超过 limit 计费字符：先按段落，太长的段按句子，单句还太长就硬切 */
    fun split(text: String, limit: Int = SEGMENT_BILLED): List<String> {
        val pieces = text.split('\n').flatMap { line ->
            if (billed(line) <= limit) listOf(line)
            else Regex("[^。！？!?；;.…]*[。！？!?；;.…]+|[^。！？!?；;.…]+$").findAll(line).map { it.value }.toList()
                .flatMap { s -> if (billed(s) <= limit) listOf(s) else hardCut(s, limit) }
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var used = 0
        for (p in pieces) {
            val cost = billed(p) + 1
            if (cur.isNotEmpty() && used + cost > limit) { out += cur.toString(); cur.clear(); used = 0 }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(p); used += cost
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun hardCut(s: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var used = 0
        for (i in s.indices) {
            val c = if (s[i].code < 128) 1 else 2
            if (used + c > limit) { out += s.substring(start, i); start = i; used = 0 }
            used += c
        }
        out += s.substring(start)
        return out
    }

    fun requestBody(text: String, voice: String) = buildJsonObject {
        put("text", text)
        put("voice_id", voice)
        put("format", "pcm")
        put("filter_markdown", true)
    }.toString()
}

/** 把文字交给 DeepSeek 合成，边收边放。同一时间只念一段，stop() 立刻停 */
class Speaker(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null
    @Volatile private var conn: HttpURLConnection? = null

    /** 念完（或被停止）才返回；接口出错抛 DeepSeek.ApiException */
    suspend fun speak(apiKey: String, text: String, voice: String) = withContext(Dispatchers.IO) {
        val segments = Tts.split(Tts.readable(text))
        if (segments.isEmpty()) return@withContext
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setSampleRate(Tts.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(Tts.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, Tts.SAMPLE_RATE)).build()
        // 念的时候别的 App 的声音先压低
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(focus)
        track = t
        var frames = 0L
        try {
            t.play()
            val buf = ByteArray(8192)
            for (segment in segments) {
                ensureActive()
                val c = DeepSeek.open("${DeepSeek.BASE}/v1/tools/tts", apiKey).apply {
                    requestMethod = "POST"
                    doOutput = true
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn = c
                try {
                    c.outputStream.use { it.write(Tts.requestBody(segment, voice).toByteArray()) }
                    DeepSeek.checkStatus(c)
                    var carry = -1
                    c.inputStream.use { input ->
                        while (true) {
                            // 16 位采样要两个字节一组写进去：上次多出来的一个字节接在前面
                            val start = if (carry >= 0) { buf[0] = carry.toByte(); carry = -1; 1 } else 0
                            val n = input.read(buf, start, buf.size - start)
                            if (n < 0) break
                            ensureActive()
                            var len = start + n
                            if (len % 2 == 1) { carry = buf[len - 1].toInt() and 0xFF; len-- }
                            if (len > 0) { t.write(buf, 0, len); frames += len / 2 }
                        }
                    }
                } finally {
                    c.disconnect()
                    conn = null
                }
            }
            // 等缓冲里剩下的放完（stop 之后已经写进去的还会接着放完）
            t.stop()
            val left = frames - t.playbackHeadPosition
            val deadline = System.currentTimeMillis() + left * 1000 / Tts.SAMPLE_RATE + 1000
            while (t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
                ensureActive()
                delay(50)
            }
        } finally {
            track = null
            runCatching { t.pause(); t.flush() }
            t.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** 马上停：断开下载、清掉没放完的声音 */
    fun stop() {
        runCatching { conn?.disconnect() }
        runCatching { track?.let { it.pause(); it.flush() } }
    }
}

/**
 * 边生成边念时把正文切成能送去合成的小块：整行或到句末标点就送；代码块（``` 围起来的）整段不送。
 * 纯逻辑，方便测试
 */
class SpeechFilter {
    private val buf = StringBuilder()
    private var inCode = false
    private var atLineStart = true

    /** 收到一段新正文，返回现在可以送去念的文字（可能是空的） */
    fun feed(delta: String): String {
        buf.append(delta)
        val out = StringBuilder()
        while (true) {
            val nl = buf.indexOf("\n")
            if (nl >= 0) {
                val line = buf.substring(0, nl)
                buf.delete(0, nl + 1)
                line(line, out)
                continue
            }
            // 没换行：在代码块里、或者这一行可能是 ``` 开头，先等等
            if (inCode || buf.isEmpty()) break
            if (atLineStart && buf.trimStart().let { it.isEmpty() || it.startsWith("`") }) break
            val cut = buf.indexOfLast { it in PUNCT }
            if (cut < 0) break
            out.append(buf, 0, cut + 1)
            buf.delete(0, cut + 1)
            atLineStart = false
            break
        }
        return out.toString()
    }

    /** 正文写完了：剩下的都送出去（没关上的代码块不念） */
    fun finish(): String {
        val out = StringBuilder()
        if (buf.isNotEmpty()) line(buf.toString(), out)
        buf.clear()
        return out.toString().trimEnd()
    }

    private fun line(line: String, out: StringBuilder) {
        if (atLineStart && line.trimStart().startsWith("```")) inCode = !inCode
        else if (!inCode) out.append(line).append('\n')
        atLineStart = true
    }

    private companion object {
        const val PUNCT = "。！？!?；;…，,：:"
    }
}

/**
 * 边生成边念：用 DeepSeek 朗读的 WebSocket（wss://…/v1/tools/tts），正文一边出来一边送过去，声音一边回来一边放。
 * 一个会话最多 1 万计费字符、60 秒没收到文字会被断开，所以超了或断了就接着开下一个会话。
 */
class StreamSpeech(private val apiKey: String, private val voice: String, context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val filter = SpeechFilter()
    private val texts = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    /** 收到一段正文 */
    fun push(delta: String) {
        filter.feed(delta).takeIf { it.isNotBlank() }?.let { texts.trySend(it) }
    }

    /** 正文写完了（或被停了）：剩下的念完就结束 */
    fun finish() {
        filter.finish().takeIf { it.isNotBlank() }?.let { texts.trySend(it) }
        texts.close()
    }

    /** 服务端结束会话时报的错 */
    class SpeechError(val code: String, message: String) : java.io.IOException("$code: $message")

    /** 念完才返回；协程被取消时立刻停 */
    suspend fun run() = withContext(Dispatchers.IO) {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setSampleRate(Tts.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(Tts.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, Tts.SAMPLE_RATE)).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val written = java.util.concurrent.atomic.AtomicLong(0)
        var focused = false
        var socket: okhttp3.WebSocket? = null
        val client = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        try {
            var carried: String? = null
            var ended = false
            while (!ended) {
                // 等到有字才连（模型深度思考可能很久，先连上会因为 60 秒没字被断开）
                val first = carried ?: texts.receiveCatching().getOrNull() ?: break
                carried = null
                if (!focused) { audio.requestAudioFocus(focus); track.play(); focused = true }
                val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
                var odd = -1
                val url = "wss://api.deepseek.com/v1/tools/tts?voice_id=$voice&format=pcm&filter_markdown=true"
                val ws = client.newWebSocket(
                    okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build(),
                    object : okhttp3.WebSocketListener() {
                        override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                            if (closedFlag.get()) return
                            var data = bytes.toByteArray()
                            // 16 位采样两个字节一组：上一帧多出来的一个字节接在前面
                            if (odd >= 0) { data = byteArrayOf(odd.toByte()) + data; odd = -1 }
                            var len = data.size
                            if (len % 2 == 1) { odd = data[len - 1].toInt() and 0xFF; len-- }
                            if (len > 0 && !closedFlag.get()) { track.write(data, 0, len); written.addAndGet(len / 2L) }
                        }

                        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
                            if ((o["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "finish") return
                            val code = (o["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            val msg = (o["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            // 60 秒没字被断开不算错：后面还有字就再开一个会话
                            if (code == "success" || code == "input_timeout") finished.complete(Unit)
                            else finished.completeExceptionally(SpeechError(code, msg))
                        }

                        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                            val code = response?.code
                            finished.completeExceptionally(if (code != null && code >= 400) DeepSeek.ApiException(code, response.message) else t)
                        }

                        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { finished.complete(Unit) }
                    },
                )
                socket = ws
                var billed = 0
                fun send(text: String): Boolean {
                    val cost = Tts.billed(text)
                    if (billed > 0 && billed + cost > SESSION_BILLED) return false
                    ws.send(buildJsonObject { put("event", "text"); put("text", text) }.toString())
                    billed += cost
                    return true
                }
                // 一块太长先切小
                val queue = ArrayDeque(Tts.split(first, SESSION_BILLED))
                while (true) {
                    val next = queue.removeFirstOrNull() ?: kotlinx.coroutines.selects.select<String?> {
                        texts.onReceiveCatching { r -> r.getOrNull().also { if (it == null) ended = true } }
                        finished.onAwait { null }
                    } ?: break
                    if (finished.isCompleted) { carried = next; break } // 会话已经被关了，这块留给下一个会话
                    val parts = Tts.split(next, SESSION_BILLED)
                    if (!send(parts.first())) { carried = next; break }
                    queue.addAll(0, parts.drop(1))
                }
                if (queue.isNotEmpty()) carried = (listOfNotNull(carried) + queue).joinToString("\n")
                if (!finished.isCompleted) ws.send("""{"event":"finish_text"}""")
                finished.await()
                ws.close(1000, null)
                socket = null
            }
            if (focused) {
                // 等缓冲里剩下的放完
                track.stop()
                val frames = written.get()
                val deadline = System.currentTimeMillis() + (frames - track.playbackHeadPosition) * 1000 / Tts.SAMPLE_RATE + 1000
                while (track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
                    ensureActive()
                    delay(50)
                }
            }
        } finally {
            closedFlag.set(true)
            socket?.cancel()
            texts.cancel()
            runCatching { track.pause(); track.flush() }
            track.release()
            if (focused) audio.abandonAudioFocusRequest(focus)
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        /** 一个会话的计费字符上限（接口是 1 万），留点余量 */
        const val SESSION_BILLED = 9_000
    }
}
