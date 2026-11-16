package com.jack.dschat.data

import java.util.UUID

/** 这一版没接模型：回复和第一次打开时的历史记录都是这里造的演示数据 */
object Demo {
    private const val DAY = 24 * 60 * 60 * 1000L

    fun reply(question: String, english: Boolean, search: Boolean): String {
        val q = question.trim().let { if (it.length > 40) it.take(40) + "…" else it }
        return if (english) buildString {
            if (search) append("(Search is on, but this demo build never goes online.)\n\n")
            append("This is a demo reply. This build is interface only and no model is connected yet.\n\n")
            append("You said: \"$q\"\n\n")
            append("Once a model is connected, the real answer will stream in here just like this text does.")
        } else buildString {
            if (search) append("（智能搜索已开启，演示版不会真的联网。）\n\n")
            append("这是一条演示回复。这一版只有界面，还没有接入模型。\n\n")
            append("你刚才说的是：「$q」\n\n")
            append("接上模型以后，真正的回答会像现在这样一个字一个字地出现在这里。")
        }
    }

    fun reasoning(question: String, english: Boolean): String {
        val q = question.trim().let { if (it.length > 30) it.take(30) + "…" else it }
        return if (english) {
            "The user said \"$q\". No model is connected in this build, so there is nothing to actually reason about. " +
                "I will show what the thinking block looks like, then give the demo reply."
        } else {
            "用户说的是「$q」。这一版没有接模型，所以没有真的在推理。" +
                "这里先展示思考过程的样子，然后给出演示回复。"
        }
    }

    /** 第一次打开时放几条历史记录，方便看分组和搜索的效果 */
    fun seed(now: Long): List<Conversation> {
        val samples = listOf(
            1 to "周末去哪儿徒步",
            3 to "帮我写一封请假邮件",
            5 to "Kotlin 协程怎么取消",
            12 to "红烧肉的做法",
            20 to "解释一下复利",
            26 to "给猫起个名字",
            45 to "旅行清单：大理五天",
            50 to "英语面试自我介绍",
            80 to "如何养成早睡的习惯",
        )
        return samples.map { (daysAgo, title) ->
            val t = now - daysAgo * DAY
            Conversation(
                id = UUID.randomUUID().toString(),
                title = title,
                updatedAt = t,
                messages = listOf(
                    Message(UUID.randomUUID().toString(), "user", title, time = t),
                    Message(UUID.randomUUID().toString(), "assistant", reply(title, english = false, search = false), time = t),
                ),
            )
        }
    }
}
