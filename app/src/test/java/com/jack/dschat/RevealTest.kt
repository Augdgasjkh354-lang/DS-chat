package com.jack.dschat

import com.jack.dschat.ui.Reveal
import com.jack.dschat.ui.fitWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealTest {
    /** 模拟 60 帧每秒跑 n 帧 */
    private fun Reveal.run(target: Int, frames: Int) {
        var carry = 0f
        repeat(frames) { carry = step(target, 1 / 60f, carry) }
    }

    @Test
    fun bigChunkIsSpreadOverFrames() {
        val r = Reveal(0)
        r.run(300, 1)
        assertTrue("一帧不该全出来：${r.shown}", r.shown in 1..60)
        r.run(300, 60)
        assertEquals("一秒内追上", 300, r.shown)
    }

    @Test
    fun slowTrickleStillMoves() {
        val r = Reveal(0)
        r.run(3, 10)
        assertEquals(3, r.shown)
    }

    @Test
    fun fadeEndsAfterCatchingUp() {
        val r = Reveal(0)
        r.run(20, 30)
        assertEquals(20, r.shown)
        r.run(20, Reveal.FADE)
        assertEquals(0, r.fade)
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
