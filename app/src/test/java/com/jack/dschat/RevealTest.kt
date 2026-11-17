package com.jack.dschat

import com.jack.dschat.ui.StreamReveal
import com.jack.dschat.ui.fitWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealTest {
    @Test
    fun steadyWhileLiveFastAfter() {
        // 输出中不管攒了多少都是每秒 100 字
        assertEquals(100.0, StreamReveal.charsPerSecond(5, live = true), 0.0)
        assertEquals(100.0, StreamReveal.charsPerSecond(5000, live = true), 0.0)
        // 写完后最少每秒 150，剩得多放得快：600 字约一秒半
        assertEquals(150.0, StreamReveal.charsPerSecond(10, live = false), 0.0)
        assertEquals(1200.0, StreamReveal.charsPerSecond(600, live = false), 0.0)
    }

    @Test
    fun neverSplitsAnEmoji() {
        val text = "好😀呀"
        // 「😀」占两个 char（下标 1、2）：走到中间要多走一个
        assertEquals(3, StreamReveal.advance(text, 0, 2))
        assertEquals(1, StreamReveal.advance(text, 0, 1))
        assertEquals(text.length, StreamReveal.advance(text, 3, 10))
    }

    @Test
    fun liveRevealTakesAboutRightTime() {
        // 模拟 60 帧每秒：一秒放出大约 100 字
        var len = 0
        var acc = 0.0
        val text = "字".repeat(1000)
        repeat(60) {
            acc += StreamReveal.charsPerSecond(text.length - len, live = true) / 60
            val step = acc.toInt()
            acc -= step
            len = StreamReveal.advance(text, len, step)
        }
        assertTrue("一秒放了 $len 字", len in 98..101)
    }

    @Test
    fun tableColumnsFillOrShrink() {
        val narrow = intArrayOf(100, 100)
        fitWidths(narrow, 301, 110)
        assertEquals(listOf(151, 150), narrow.toList())

        val wide = intArrayOf(100, 120, 400)
        fitWidths(wide, 500, 110)
        assertEquals(500, wide.sum())
        assertEquals(100, wide[0]) // 本来就比下限窄的不动

        val tooWide = intArrayOf(300, 300, 300)
        fitWidths(tooWide, 200, 110)
        assertEquals(listOf(110, 110, 110), tooWide.toList()) // 压到下限还放不下：左右滑
    }
}
