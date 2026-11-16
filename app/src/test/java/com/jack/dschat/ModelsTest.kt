package com.jack.dschat

import com.jack.dschat.data.Conversation
import com.jack.dschat.data.Group
import com.jack.dschat.data.Message
import com.jack.dschat.data.groupOf
import com.jack.dschat.data.matches
import com.jack.dschat.data.titleFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ModelsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 11, 16, 16)

    @Test
    fun 按天数分组() {
        assertEquals(Group.Today, groupOf(now, at(2026, 11, 16, 1), zone))
        // 昨天深夜也算昨天，不看是不是满 24 小时
        assertEquals(Group.Yesterday, groupOf(now, at(2026, 11, 15, 23), zone))
        assertEquals(Group.Week, groupOf(now, at(2026, 11, 10), zone))
        assertEquals(Group.Month, groupOf(now, at(2026, 11, 9), zone))
        assertEquals(Group.Month, groupOf(now, at(2026, 10, 18), zone))
        assertEquals(Group.YearMonth(2026, 10), groupOf(now, at(2026, 10, 17), zone))
        assertEquals(Group.YearMonth(2025, 12), groupOf(now, at(2025, 12, 31), zone))
    }

    @Test
    fun 搜索看标题和消息内容() {
        val c = Conversation("1", "红烧肉的做法", 0, listOf(Message("m", "assistant", "先把五花肉 Blanch 一下")))
        assertTrue(c.matches(""))
        assertTrue(c.matches("红烧"))
        assertTrue(c.matches(" blanch "))
        assertFalse(c.matches("徒步"))
    }

    @Test
    fun 标题取第一行前二十个字() {
        assertEquals("你好", titleFrom("\n  你好  \n第二行"))
        assertEquals("一二三四五六七八九十一二三四五六七八九十…", titleFrom("一二三四五六七八九十一二三四五六七八九十多出来的"))
    }
}
