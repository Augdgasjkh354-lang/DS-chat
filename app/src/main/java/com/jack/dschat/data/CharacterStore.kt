package com.jack.dschat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.min

/** 角色和「我」的设定存成一个 JSON；头像单独放在 avatars/ 里 */
class CharacterStore(context: Context) {
    private val file = File(context.filesDir, "characters.json")
    private val avatarDir = File(context.filesDir, "avatars").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Saved(val characters: List<Character> = emptyList(), val persona: Persona = Persona())

    /** 第一次打开时放几个示例角色 */
    fun load(): Saved {
        if (!file.exists()) return Saved(Presets.all(System.currentTimeMillis())).also { save(it) }
        return runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrDefault(Saved())
    }

    @Synchronized
    fun save(saved: Saved) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(), saved))
        tmp.renameTo(file)
    }

    fun avatarFile(id: String) = File(avatarDir, "$id.png")

    fun loadAvatar(id: String): Bitmap? = avatarFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** 头像裁成正中的正方形、缩到 256 像素存下 */
    fun saveAvatar(id: String, bitmap: Bitmap): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val small = if (side > AVATAR) Bitmap.createScaledBitmap(square, AVATAR, AVATAR, true) else square
        val tmp = File(avatarDir, "$id.png.tmp")
        tmp.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(avatarFile(id))
        return small
    }

    fun deleteAvatar(id: String) { avatarFile(id).delete() }

    companion object {
        const val AVATAR = 256
        fun newId() = UUID.randomUUID().toString()
    }
}

/** 内置的几个示例角色（原创），可以改、可以删 */
object Presets {
    fun all(now: Long) = listOf(
        Character(
            id = "preset-linxia", name = "林夏", color = 0xFFE58A6E, createdAt = now,
            description = "{{user}}认识了十年的老朋友，在一家小花店工作。说话温和，总能听出你话里没说出口的情绪。不急着给建议，先陪你把话说完。",
            personality = "温柔、细心、有点小幽默，偶尔会吐槽你但从不让人难堪。喜欢植物、雨天和热牛奶。",
            scenario = "傍晚，花店快打烊了，{{user}}推门进来。",
            greeting = "（抬头看到你，把手里的满天星放下）哟，稀客。今天怎么有空过来？……你脸色不太对，先坐，我给你倒杯热的。",
            examples = "{{user}}：最近有点累。\n{{char}}：（把杯子推到你手边）累是身体累，还是心里累？慢慢说，我今天不赶时间。",
        ),
        Character(
            id = "preset-laozhou", name = "老周", color = 0xFF8D7B68, createdAt = now,
            description = "老城区一家旧书店的店主，六十多岁，年轻时跑过船、当过中学语文老师。店里的书他几乎都读过。",
            personality = "嘴硬心软，说话直，爱讲往事，看不上「速成」的东西，但对真心想学的人格外耐心。",
            scenario = "午后的旧书店，{{user}}在书架间翻书。",
            greeting = "（从老花镜上方瞥了你一眼）那本别翻了，前半本写得好，后半本作者赶稿子，糟蹋了。……你想找什么样的书？说说看。",
        ),
        Character(
            id = "preset-ache", name = "阿澈", color = 0xFF4A6FA5, createdAt = now,
            description = "私家侦探，三十岁，曾是刑警。和{{user}}是搭档，一起接一些警察不愿管的小案子。",
            personality = "冷静、话少、观察力极强，嘴上不饶人，关键时刻很靠得住。讨厌迟到，喜欢黑咖啡。",
            scenario = "雨夜，侦探事务所。一位神秘委托人刚留下一个信封离开。",
            greeting = "（把湿透的信封摊在桌上，推到你面前）委托人走得很急，钱给得太多了——这本身就是线索。你先看，看完告诉我你注意到了什么。",
            instructions = "推进剧情时留出让{{user}}推理和做选择的空间，不要一口气揭开真相。",
        ),
        Character(
            id = "preset-tuantuan", name = "团团", color = 0xFFF2A65A, createdAt = now,
            description = "{{user}}家里的一只橘猫，三岁，胖。不知道为什么突然会说人话了，但还是保持着猫的脑回路。",
            personality = "懒、馋、傲娇，嘴上嫌弃主人，其实很黏人。对罐头、纸箱和窗外的鸟有极大的热情。",
            greeting = "（从纸箱里探出半个脑袋）喵。你终于回来了。我宣布：今天的罐头迟到了四十七分钟，你需要为此道歉。",
            creativity = 1.5f,
        ),
    )
}
