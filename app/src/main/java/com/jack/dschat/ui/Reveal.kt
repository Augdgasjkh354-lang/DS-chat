package com.jack.dschat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * 让回答柔和地流出来：接口一次吐一大段，屏幕上按打字的节奏一点点放。
 * 输出中固定每秒 100 字（连续地流，看得清在写什么）；模型写得更快的先攒着，
 * 写完后剩下的一两秒内加速放完，不让人干等。思考过程不经过这里，照模型原速。
 */
object StreamReveal {
    /** 每条消息已经放到第几个字。消息滑出屏幕再滑回来时接着放，不会从头重放 */
    internal val shown = HashMap<String, Int>()

    /** 还有字没放完（接口已经结束也可能还在放）：聊天列表据此继续跟着往下走 */
    var pending by mutableStateOf(false)
        internal set

    const val LIVE_RATE = 100.0

    /** 末尾淡入的字数；追上以后每帧少一个，大约 0.2 秒全变实 */
    const val FADE = 12

    /** 每秒放多少字。写完后：每秒放剩下的两倍，最少 150 字。剩 600 字约一秒半放完 */
    fun charsPerSecond(backlog: Int, live: Boolean): Double =
        if (live) LIVE_RATE else maxOf(150.0, backlog * 2.0)

    /** 从 len 往后走 step 个字，不把一个表情（两个 char）切成两半 */
    fun advance(text: String, len: Int, step: Int): Int {
        var next = (len + step).coerceAtMost(text.length)
        if (next in 1 until text.length && Character.isHighSurrogate(text[next - 1])) next++
        return next
    }
}

/** 放到哪了：shown 是显示到第几个字，fade 是末尾还在淡入的字数 */
class Reveal(start: Int) {
    var shown by mutableIntStateOf(start)
        internal set
    var fade by mutableIntStateOf(0)
        internal set
}

/**
 * 一条回答该显示到哪。live 是接口还在输出；以前没放过、也不在输出的消息（翻历史）直接整段显示。
 */
@Composable
fun rememberReveal(id: String, full: String, live: Boolean): Reveal {
    val reveal = remember(id) { Reveal(StreamReveal.shown[id] ?: if (live) 0 else full.length) }
    val latest by rememberUpdatedState(full)
    val stillLive by rememberUpdatedState(live)
    LaunchedEffect(id, live) {
        var acc = 0.0
        var last = 0L
        // 被取消（换了对话、消息滑走）时别让「还在放」一直挂着
        try { while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 1.0 / 60 else ((now - last) / 1e9).coerceAtMost(0.1)
            last = now
            val text = latest
            if (reveal.shown > text.length) reveal.shown = text.length // 内容被换掉了
            val backlog = text.length - reveal.shown
            if (backlog <= 0) {
                StreamReveal.shown[id] = reveal.shown
                acc = 0.0
                if (reveal.fade > 0) reveal.fade--
                if (!stillLive && reveal.fade == 0) { StreamReveal.pending = false; break }
                continue
            }
            StreamReveal.pending = true
            acc += StreamReveal.charsPerSecond(backlog, stillLive) * dt
            val step = acc.toInt()
            if (step > 0) {
                acc -= step
                reveal.shown = StreamReveal.advance(text, reveal.shown, step)
                reveal.fade = StreamReveal.FADE
                StreamReveal.shown[id] = reveal.shown
            }
        } } finally {
            StreamReveal.pending = false
        }
    }
    return reveal
}
