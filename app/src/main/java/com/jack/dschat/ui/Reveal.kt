package com.jack.dschat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.max
import kotlin.math.min

/**
 * 流式输出时让字匀速冒出来：接口一次吐一大段也不会一下子跳出来。
 * shown 是现在显示到第几个字，fade 是末尾还在淡入的字数。
 */
class Reveal(start: Int) {
    var shown by mutableIntStateOf(start)
        internal set
    var fade by mutableIntStateOf(0)
        internal set

    /** 每秒出多少字：落后得越多出得越快，最慢也有 MIN_SPEED，保证接口停了以后很快追上 */
    internal fun step(target: Int, seconds: Float, carry: Float): Float {
        val backlog = target - shown
        if (backlog <= 0) {
            fade = max(0, fade - 1)
            return 0f
        }
        val total = carry + max(MIN_SPEED, backlog * CATCH_UP) * seconds
        val add = total.toInt()
        if (add > 0) shown = min(target, shown + add)
        fade = FADE
        return total - add
    }

    companion object {
        const val MIN_SPEED = 45f
        const val CATCH_UP = 7f
        /** 末尾淡入的字数；追上以后每帧少一个，大约 0.2 秒全变实 */
        const val FADE = 12
    }
}

/** 已经存在的消息（翻历史、切对话）直接显示全文，只有之后新长出来的字才慢慢出 */
@Composable
fun rememberReveal(key: String, target: Int): Reveal {
    val reveal = remember(key) { Reveal(target) }
    if (reveal.shown > target) reveal.shown = target // 文字被换掉了（比如重新生成）
    LaunchedEffect(reveal, target) {
        var carry = 0f
        var last = 0L
        while (reveal.shown < target || reveal.fade > 0) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 1 / 60f else (now - last) / 1e9f
                last = now
                carry = reveal.step(target, seconds, carry)
            }
        }
    }
    return reveal
}
