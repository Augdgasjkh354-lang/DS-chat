package com.jack.dschat.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** 聊天列表要不要跟着最底下走。用户手指往上翻看旧内容时 on 变成 false，回到底部又变回 true */
class FollowBottom {
    var on by mutableStateOf(true)

    /** 挂在列表上：手指往下拖（往上翻）就不再拽着用户 */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0) on = false
            return Offset.Zero
        }
    }
}

/** 离最底下还差多少像素；最后一项还没露出来（离得太远）返回 null */
private fun LazyListState.distToEnd(): Float? {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return null
    if (last.index < info.totalItemsCount - 1) return null
    return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat()
}

private suspend fun LazyListState.toEnd() {
    val n = layoutInfo.totalItemsCount
    if (n > 0) scrollToItem(n - 1, Int.MAX_VALUE)
}

/** 每帧走剩下距离的一部分：新起一行时画面在零点二秒左右里滑上去，不一下一下地跳 */
private suspend fun LazyListState.glide(share: Float) {
    val dist = distToEnd() ?: run { toEnd(); return }
    // 最后一点点直接走完，不留几个像素的缝
    if (dist > 0.5f) scrollBy(if (dist <= 2f) dist else maxOf(1f, dist * share))
}

/**
 * key 换了（换对话）或 newTurn 换了（发了新消息）就回到最底下并开始跟；
 * moving（正在输出或字还没放完）时每一帧往底部滑一点，输出停了把剩下那点距离走完。
 */
@Composable
fun rememberFollowBottom(listState: LazyListState, key: Any?, newTurn: Any?, moving: Boolean): FollowBottom {
    val f = remember(key) { FollowBottom() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { more -> if (!more) f.on = true }
    }
    LaunchedEffect(key, newTurn) {
        f.on = true
        withFrameNanos { }
        listState.toEnd()
    }
    LaunchedEffect(key, moving) {
        if (!moving) {
            // 输出刚停：按同样的滑法停到正好贴底（用户在翻看、在拖动时不动）
            repeat(60) {
                withFrameNanos { }
                if (!f.on || listState.isScrollInProgress) return@LaunchedEffect
                val d = listState.distToEnd() ?: return@LaunchedEffect
                if (d <= 0.5f) return@LaunchedEffect
                listState.glide(0.3f)
            }
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            if (f.on && listState.canScrollForward && !listState.isScrollInProgress) listState.glide(0.2f)
        }
    }
    return f
}
