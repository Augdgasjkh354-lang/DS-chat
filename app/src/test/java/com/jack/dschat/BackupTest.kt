package com.jack.dschat

import com.jack.dschat.data.Backup
import com.jack.dschat.data.Conversation
import com.jack.dschat.data.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private fun tmp() = kotlin.io.path.createTempDirectory("bk").toFile()

    @Test
    fun roundTripsFilesChatsAndSettings() {
        val src = tmp()
        File(src, "images").mkdirs(); File(src, "images/a.jpg").writeText("img")
        File(src, "images/b.jpg.tmp").writeText("half")
        File(src, "avatars").mkdirs(); File(src, "avatars/x.png").writeText("av")
        File(src, "background.jpg").writeText("bg")
        val out = ByteArrayOutputStream()
        Backup.write(out, src, "[\"chats\"]", "{\"c\":1}", mapOf("theme" to 2, "font" to 1.15f, "english" to true, "voice" to "tide", "x" to null), 5)

        val staging = tmp()
        val r = Backup.read(out.toByteArray().inputStream(), staging, "bad", "new")
        assertEquals("[\"chats\"]", r.chats)
        assertEquals("{\"c\":1}", r.characters)
        assertEquals(2, Backup.setting(r.settings, "theme"))
        assertEquals(1.15f, Backup.setting(r.settings, "font"))
        assertEquals(true, Backup.setting(r.settings, "english"))
        assertEquals("tide", Backup.setting(r.settings, "voice"))
        assertNull(Backup.setting(r.settings, "x"))

        val dest = tmp()
        File(dest, "images").mkdirs(); File(dest, "images/old.jpg").writeText("old")
        File(dest, "background.jpg").writeText("old bg")
        Backup.install(r.staging, dest)
        assertEquals("img", File(dest, "images/a.jpg").readText())
        assertFalse(File(dest, "images/old.jpg").exists())
        assertFalse(File(dest, "images/b.jpg.tmp").exists())
        assertEquals("av", File(dest, "avatars/x.png").readText())
        assertEquals("bg", File(dest, "background.jpg").readText())
    }

    @Test
    fun rejectsOtherZipsAndPathTraversal() {
        fun zip(vararg e: Pair<String, String>) = ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { z -> e.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        }.toByteArray()
        val notOurs = zip("manifest.json" to "{\"app\":\"other\"}", "chats.json" to "[]")
        assertEquals("bad", runCatching { Backup.read(notOurs.inputStream(), tmp(), "bad", "new") }.exceptionOrNull()?.message)
        val newer = zip("manifest.json" to "{\"app\":\"dschat\",\"format\":99}", "chats.json" to "[]")
        assertEquals("new", runCatching { Backup.read(newer.inputStream(), tmp(), "bad", "new") }.exceptionOrNull()?.message)
        val staging = tmp()
        val evil = zip("manifest.json" to "{\"app\":\"dschat\",\"format\":1}", "chats.json" to "[]", "images/../../evil.txt" to "x", "other/f" to "y")
        Backup.read(evil.inputStream(), staging, "bad", "new")
        assertTrue(staging.walkTopDown().none { it.name == "evil.txt" || it.name == "f" })
        assertFalse(File(staging.parentFile, "evil.txt").exists())
    }

    @Test
    fun relocatesImagePaths() {
        val c = Conversation("1", "t", 0, listOf(Message("m", "user", "", images = listOf("/data/user/0/old/files/images/a.jpg"))))
        val moved = Backup.relocate(listOf(c), File("/new/images"))
        assertEquals("/new/images/a.jpg", moved[0].messages[0].images[0])
    }
}
