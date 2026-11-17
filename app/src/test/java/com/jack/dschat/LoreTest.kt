package com.jack.dschat

import com.jack.dschat.data.CardException
import com.jack.dschat.data.Character
import com.jack.dschat.data.CharacterCard
import com.jack.dschat.data.Lore
import com.jack.dschat.data.LoreEntry
import com.jack.dschat.data.Lorebook
import com.jack.dschat.data.Persona
import com.jack.dschat.data.RolePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoreTest {
    @Test
    fun readsCharacterBookFromCard() {
        val card = """{"spec":"chara_card_v2","data":{"name":"小雨","description":"同桌","character_book":{"scan_depth":3,"recursive_scanning":true,
            "entries":[
              {"keys":["学校","教室"],"content":"青山高中，在海边。","enabled":true,"insertion_order":10,"comment":"学校"},
              {"keys":["社团"],"secondary_keys":["周五"],"selective":true,"content":"文学社每周五活动。","enabled":false,"insertion_order":5,"position":"after_char"},
              {"keys":[],"content":"","enabled":true}
            ]}}}"""
        val book = CharacterCard.parse(card.toByteArray(), "bad").book
        assertEquals(3, book.scanDepth)
        assertTrue(book.recursive)
        assertEquals(2, book.entries.size) // 没内容的丢掉
        val school = book.entries[0]
        assertEquals(listOf("学校", "教室"), school.keys)
        assertEquals("学校", school.comment)
        assertEquals(10, school.order)
        assertFalse(school.after)
        val club = book.entries[1]
        assertFalse(club.enabled)
        assertTrue(club.selective)
        assertEquals(listOf("周五"), club.secondaryKeys)
        assertTrue(club.after)
    }

    @Test
    fun readsSillyTavernWorldFile() {
        val world = """{"entries":{"0":{"uid":0,"key":["龙"],"keysecondary":[],"content":"这个世界的龙会说话。","comment":"龙","constant":false,
            "selective":false,"order":100,"position":1,"disable":false},"1":{"uid":1,"key":["魔法"],"content":"魔法要付出代价。","disable":true,"position":0}}}"""
        val book = Lore.parseFile(world.toByteArray(), "bad")
        assertEquals(2, book.entries.size)
        assertEquals(listOf("龙"), book.entries[0].keys)
        assertTrue(book.entries[0].after)
        assertTrue(book.entries[0].enabled)
        assertFalse(book.entries[1].enabled)
        assertFalse(book.entries[1].after)
    }

    @Test(expected = CardException::class)
    fun rejectsNonBooks() {
        Lore.parseFile("""{"name":"x"}""".toByteArray(), "bad")
    }

    private fun e(id: String, vararg keys: String, content: String = id, order: Int = 100, enabled: Boolean = true, constant: Boolean = false,
                  selective: Boolean = false, secondary: List<String> = emptyList(), case: Boolean = false) =
        LoreEntry(id, keys.toList(), secondary, content, enabled = enabled, constant = constant, selective = selective, caseSensitive = case, order = order)

    private fun ids(book: Lorebook, vararg recent: String) = Lore.activate(book, recent.toList()).map { it.id }

    @Test
    fun activatesByKeywords() {
        val book = Lorebook(scanDepth = 2, entries = listOf(
            e("school", "学校"),
            e("dragon", "Dragon"),
            e("cased", "Magic", case = true),
            e("regex", "/猫{2,}/"),
            e("always", constant = true),
            e("off", "学校", enabled = false),
            e("both", "社团", selective = true, secondary = listOf("周五")),
        ))
        assertEquals(listOf("always"), ids(book, "你好"))
        assertEquals(setOf("always", "school", "dragon", "regex"), ids(book, "学校里有一条 dragon", "猫猫").toSet())
        assertFalse("magic" in ids(book, "magic").toString()) // 区分大小写的不匹配小写
        assertTrue("cased" in ids(book, "Magic"))
        // 次要关键词也要出现
        assertFalse("both" in ids(book, "社团活动"))
        assertTrue("both" in ids(book, "周五社团活动"))
        // 只看最近 2 条
        assertFalse("school" in ids(book, "学校", "第二条", "第三条"))
    }

    @Test
    fun recursiveBudgetAndOrder() {
        val chain = Lorebook(recursive = true, entries = listOf(e("a", "城堡", content = "城堡里住着公主"), e("b", "公主", content = "公主会魔法")))
        assertEquals(listOf("a", "b"), ids(chain, "去城堡"))
        assertEquals(listOf("a"), ids(chain.copy(recursive = false), "去城堡"))

        // 篇幅不够时先丢 order 小的；结果按 order 从小到大
        val big = Lorebook(tokenBudget = 10, entries = listOf(
            e("low", "x", content = "一".repeat(12), order = 1),
            e("high", "x", content = "二".repeat(12), order = 9),
        ))
        assertEquals(listOf("high"), ids(big, "x"))
        val both = big.copy(tokenBudget = 0)
        assertEquals(listOf("low", "high"), ids(both, "x"))
    }

    @Test
    fun loreGoesAroundTheCharacter() {
        val c = Character("r", "小雨", description = "同桌")
        val s = RolePrompt.system(c, Persona(), "", "Jack", listOf(
            LoreEntry("1", content = "世界在海边", after = false),
            LoreEntry("2", content = "{{user}}是转学生", after = true),
        ))
        assertTrue(s.indexOf("世界在海边") < s.indexOf("【小雨的设定】"))
        assertTrue(s.indexOf("Jack是转学生") > s.indexOf("【小雨的设定】"))
    }
}
