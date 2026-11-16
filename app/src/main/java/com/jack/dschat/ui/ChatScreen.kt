package com.jack.dschat.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.dschat.ChatViewModel
import com.jack.dschat.data.Message

@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onNotReady: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val messages = vm.current?.messages.orEmpty()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // 顶栏：左边开侧边栏，右边是朗读开关和新对话
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(AppIcons.Menu, onClick = onMenu)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainButton(AppIcons.SpeakerOff, onClick = onNotReady)
                PlainButton(AppIcons.NewChat, onClick = { vm.newChat() })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(bottom = 48.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(AppIcons.Logo, null, Modifier.size(52.dp), tint = colors.accent)
                    Spacer(Modifier.height(22.dp))
                    Text(strings.greeting, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                MessageList(vm, messages)
            }
        }

        InputPanel(vm, onNotReady)
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, messages: List<Message>) {
    val listState = rememberLazyListState()
    val last = messages.last()
    // 换对话、来新消息、流式输出时都滚到最底下（最后一项是个垫底的空白）
    LaunchedEffect(vm.currentId, messages.size, last.text.length, last.reasoning.length) {
        listState.scrollToItem(messages.size)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp),
    ) {
        itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
            val isLast = index == messages.lastIndex
            if (message.isUser) UserMessage(message) else AssistantMessage(vm, message, isLast)
        }
        item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun UserMessage(message: Message) {
    val colors = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(
                message.text,
                color = colors.text,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(colors.bubble).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(vm: ChatViewModel, message: Message, isLast: Boolean) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val streaming = isLast && vm.generating

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (message.reasoning.isNotEmpty()) {
            var expanded by rememberSaveable(message.id) { mutableStateOf(true) }
            val thinkingNow = streaming && message.text.isEmpty()
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(colors.card).clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Atom, null, Modifier.size(16.dp), tint = colors.sub)
                Spacer(Modifier.width(6.dp))
                Text(if (thinkingNow) strings.thinking else strings.thought(message.thinkSeconds), color = colors.sub, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Icon(AppIcons.ChevronDown, null, Modifier.size(14.dp).rotate(if (expanded) 180f else 0f), tint = colors.sub)
            }
            if (expanded) {
                val bar = colors.border
                Text(
                    message.reasoning,
                    color = colors.sub,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 10.dp)
                        .drawBehind { drawLine(bar, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx()) }
                        .padding(start = 14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (message.text.isEmpty() && message.reasoning.isEmpty() && streaming) {
            Text("●", color = colors.sub, fontSize = 14.sp)
        } else if (message.text.isNotEmpty()) {
            SelectionContainer {
                Text(message.text, color = colors.text, fontSize = 16.sp, lineHeight = 26.sp)
            }
        }

        if (!streaming && message.text.isNotEmpty()) {
            Row(Modifier.padding(top = 6.dp)) {
                SmallAction(AppIcons.Copy, strings.copy) {
                    clipboard.setText(AnnotatedString(message.text))
                    Toast.makeText(context, strings.copied, Toast.LENGTH_SHORT).show()
                }
                if (isLast) SmallAction(AppIcons.Refresh, strings.regenerate) { vm.regenerate() }
            }
        }
    }
}

@Composable
private fun InputPanel(vm: ChatViewModel, onNotReady: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    var text by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(28.dp)

    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
            .clip(shape).background(colors.card).border(1.dp, colors.border, shape).padding(14.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = colors.text, fontSize = 17.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(colors.accent),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(strings.inputHint, color = colors.sub, fontSize = 17.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(AppIcons.Atom, strings.deepThink, vm.think, Modifier.weight(1f, fill = false)) { vm.toggleThink() }
                Chip(AppIcons.Globe, strings.smartSearch, vm.search, Modifier.weight(1f, fill = false)) { vm.toggleSearch() }
            }
            Spacer(Modifier.width(8.dp))
            Icon(AppIcons.PlusCircle, null, Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNotReady).padding(5.dp), tint = colors.text)
            Spacer(Modifier.width(4.dp))
            when {
                vm.generating -> Box(
                    Modifier.size(34.dp).clip(CircleShape).background(colors.text).clickable { vm.stop() },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colors.card)) }

                text.isNotBlank() -> Box(
                    Modifier.size(34.dp).clip(CircleShape).background(colors.accent).clickable { vm.send(text); text = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.ArrowUp, null, Modifier.size(20.dp), tint = Color.White) }

                else -> Icon(AppIcons.VoiceCircle, null, Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNotReady).padding(5.dp), tint = colors.text)
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalColors.current
    val content = if (active) colors.accent else colors.text
    Row(
        modifier.clip(CircleShape)
            .background(if (active) colors.accentBg else Color.Transparent)
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.35f) else colors.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = content)
        Spacer(Modifier.width(5.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalColors.current
    Icon(icon, label, Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick).padding(9.dp), tint = colors.sub)
}

/** 带底色和描边的圆按钮（顶栏、设置页的关闭） */
@Composable
fun CircleButton(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalColors.current
    Icon(
        icon, null,
        modifier.size(46.dp).clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape)
            .clickable(onClick = onClick).padding(12.dp),
        tint = colors.text,
    )
}

@Composable
fun PlainButton(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = LocalColors.current.text, onClick: () -> Unit) {
    Icon(icon, null, modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick).padding(12.dp), tint = tint)
}
