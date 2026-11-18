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
