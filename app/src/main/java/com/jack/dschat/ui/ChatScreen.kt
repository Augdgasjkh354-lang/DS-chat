package com.jack.dschat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.jack.dschat.data.Documents
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jack.dschat.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.jack.dschat.R
import androidx.compose.ui.unit.sp
import com.jack.dschat.ChatViewModel
import com.jack.dschat.data.Message

@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onNotReady: () -> Unit, onRole: () -> Unit = {}) {
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
            val role = vm.currentCharacter
            if (role != null) {
                // 角色对话：顶栏是角色头像和名字，点开看前情回忆、编辑角色
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.clip(CircleShape).clickable(onClick = onRole).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoleAvatar(vm, role, 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(role.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (vm.remembering == vm.currentId) {
                            Spacer(Modifier.width(6.dp))
                            Text(strings.rememberingNow, color = colors.sub, fontSize = 12.sp)
                        }
                    }
                }
            } else Text(
                vm.current?.title.orEmpty(),
                color = colors.sub, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Row(
                Modifier.clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainButton(
                    if (vm.autoRead) AppIcons.Speaker else AppIcons.SpeakerOff,
                    tint = if (vm.autoRead) colors.accent else colors.text,
                ) { vm.toggleAutoRead() }
                PlainButton(AppIcons.NewChat, onClick = { vm.newChat() })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(onPick = { vm.send(it) })
            } else {
                MessageList(vm, messages)
            }
        }

        InputPanel(vm)
    }
}

/** 还没发消息时：标志带一圈柔光，下面是问候语和几个可以直接点的话题 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    Column(
        Modifier.fillMaxSize().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(220.dp).background(Brush.radialGradient(listOf(colors.accent2.copy(alpha = if (colors.isDark) 0.22f else 0.16f), Color.Transparent))))
            Image(painterResource(R.drawable.logo), null, Modifier.size(60.dp))
        }
        Text(strings.greeting, color = colors.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 0.dp))
        Spacer(Modifier.height(26.dp))
        FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            strings.suggestions.forEach { suggestion ->
                Text(
                    suggestion,
                    color = colors.text.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape)
                        .clickable { onPick(suggestion) }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** 回复还没出来时的三个点，轮流变亮 */
@Composable
private fun TypingDots() {
    val colors = LocalColors.current
    val transition = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(520, delayMillis = index * 160), RepeatMode.Reverse), label = "dot",
            )
            Box(Modifier.size(7.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(colors.accent))
        }
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, messages: List<Message>) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 输出中（或字还没放完）平滑地跟着最底下走；往上翻看时不拽人，右下角给个回到底部的按钮
    val follow = rememberFollowBottom(
        listState, vm.currentId, messages.lastOrNull { it.isUser }?.id,
        moving = vm.generating || StreamReveal.pending,
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(follow.connection),
            contentPadding = PaddingValues(top = 8.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                val isLast = index == messages.lastIndex
                Box(Modifier.animateItem()) {
                    if (message.isUser) UserMessage(message) else AssistantMessage(vm, message, isLast)
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
        }
        AnimatedVisibility(
            visible = !follow.on && listState.canScrollForward,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            Icon(
                AppIcons.ArrowDown, strings.toBottom,
                Modifier.size(40.dp).clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape)
                    .clickable {
                        follow.on = true
                        scope.launch {
                            val n = listState.layoutInfo.totalItemsCount
                            if (n > 0) listState.animateScrollToItem(n - 1, Int.MAX_VALUE)
                        }
                    }.padding(10.dp),
                tint = colors.text,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessage(message: Message) {
    val colors = LocalColors.current
    var viewing by remember { mutableStateOf<String?>(null) }
    viewing?.let { path -> ImageViewer(path) { viewing = null } }
    // 列表项外面套的是 Box，图片和文字要竖着排，包一层 Column
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), horizontalAlignment = Alignment.End) {
        if (message.images.isNotEmpty()) {
            // 发的图片：靠右排，一张大一点，多张排成小方块；点开看大图
            val size = if (message.images.size == 1) 180.dp else 96.dp
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message.images.forEach { path ->
                    LocalImage(path, 600, Modifier.size(size).clip(RoundedCornerShape(14.dp)).clickable { viewing = path })
                }
            }
            if (message.text.isNotEmpty()) Spacer(Modifier.height(8.dp))
        }
        if (message.files.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.files.forEach { f -> FileChip(f.name, Documents.sizeText(f.size)) }
            }
            if (message.text.isNotEmpty()) Spacer(Modifier.height(8.dp))
        }
        if (message.text.isNotEmpty()) SelectionContainer {
            Text(
                message.text,
                color = colors.text,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(colors.bubble).padding(horizontal = 16.dp, vertical = 10.dp),
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
    // 回答按打字的节奏放出来（思考过程照原速）；接口结束了但字还没放完，也算还在输出
    // 每一版单独记放到哪：切到别的版本直接整段显示，新生成的版本从头放
    val reveal = rememberReveal("${message.id}#${message.active}", message.text, live = isLast && vm.generating)
    val streaming = isLast && (vm.generating || reveal.shown < message.text.length)
    val text = if (reveal.shown >= message.text.length) message.text else message.text.substring(0, reveal.shown)
    val reasoning = message.reasoning
    // 垫着背景图时，回复和思考过程都包进半透明的气泡
    val plate = if (LocalOnImage.current) {
        Modifier.clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)).background(colors.card).padding(horizontal = 14.dp, vertical = 10.dp)
    } else Modifier

    // 有好几版时，在最后一条回复上左右滑动换版本（往左是下一版）
    val canSwipe = isLast && !streaming && message.versionCount > 1
    val swipeDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val swipe = if (!canSwipe) Modifier else Modifier.pointerInput(message.id, message.active, message.versionCount) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total <= -swipeDistance) vm.switchVersion(message.id, +1)
                else if (total >= swipeDistance) vm.switchVersion(message.id, -1)
            },
        ) { change, amount -> total += amount; change.consume() }
    }
    Column(Modifier.fillMaxWidth().then(swipe).padding(horizontal = 16.dp, vertical = 8.dp)) {
        // 角色对话：回复上面带角色的头像和名字
        vm.currentCharacter?.let { role ->
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoleAvatar(vm, role, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(role.name, color = colors.sub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (message.reasoning.isNotEmpty()) {
            var expanded by rememberSaveable(message.id) { mutableStateOf(true) }
            val thinkingNow = streaming && message.text.isEmpty()
            Row(
                Modifier.clip(CircleShape).background(colors.card).clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Atom, null, Modifier.size(16.dp), tint = colors.sub)
                Spacer(Modifier.width(6.dp))
                Text(if (thinkingNow) strings.thinking else strings.thought(message.thinkSeconds), color = colors.sub, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(AppIcons.ChevronDown, null, Modifier.size(14.dp).rotate(turn), tint = colors.sub)
            }
            AnimatedVisibility(expanded) {
                val bar = colors.border
                Text(
                    reasoning,
                    color = colors.sub,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 10.dp).then(plate)
                        .drawBehind { drawLine(bar, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx()) }
                        .padding(start = 14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // 联网：生成中显示正在做什么，结束后留一行「联网搜索 N 次」
        val activity = if (streaming) vm.activity else null
        if (activity != null || message.searches.isNotEmpty()) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Globe, null, Modifier.size(15.dp), tint = colors.sub)
                Spacer(Modifier.width(6.dp))
                Text(
                    activity ?: strings.searched(message.searches.size), color = colors.sub, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (message.text.isEmpty() && message.reasoning.isEmpty() && message.error.isEmpty() && streaming && activity == null) {
            TypingDots()
        } else if (text.isNotEmpty()) {
            SelectionContainer {
                MarkdownText(text, fade = reveal.fade, modifier = plate)
            }
        }

        if (message.error.isNotEmpty()) {
            Text(
                message.error, color = colors.danger, fontSize = 14.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(top = if (message.text.isEmpty()) 0.dp else 10.dp)
                    .clip(RoundedCornerShape(12.dp)).background(colors.danger.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }

        if (message.sources.isNotEmpty() && !streaming) Sources(message)

        if (!streaming && (message.text.isNotEmpty() || message.error.isNotEmpty())) {
            Row(Modifier.padding(top = 6.dp)) {
                if (message.text.isNotEmpty()) SmallAction(AppIcons.Copy, strings.copy) {
                    clipboard.setText(AnnotatedString(message.text))
                    Toast.makeText(context, strings.copied, Toast.LENGTH_SHORT).show()
                }
                if (message.text.isNotEmpty()) {
                    val speaking = vm.speakingId == message.id
                    SmallAction(if (speaking) AppIcons.StopCircle else AppIcons.Speaker, if (speaking) strings.stopReading else strings.readAloud) {
                        vm.toggleSpeak(message)
                    }
                }
                if (isLast) SmallAction(AppIcons.Refresh, strings.regenerate) { vm.regenerate() }
                if (isLast && message.versionCount > 1) VersionSwitch(message.active, message.versionCount) { vm.switchVersion(message.id, it) }
            }
        }
    }
}

/** 应用私有目录里的一张图；在后台解码，解出来之前是灰底 */
@Composable
fun LocalImage(path: String, px: Int, modifier: Modifier) {
    val colors = LocalColors.current
    val bitmap by produceState<ImageBitmap?>(null, path, px) {
        value = withContext(Dispatchers.IO) { ImageStore.thumbnail(path, px)?.asImageBitmap() }
    }
    val b = bitmap
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(colors.group))
}

/** 点开看大图：黑底铺满，点哪都关 */
@Composable
private fun ImageViewer(path: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap by produceState<ImageBitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { ImageStore.thumbnail(path, 2000)?.asImageBitmap() }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
    }
}

/** 「‹ 2 / 3 ›」：换回复的版本 */
@Composable
private fun VersionSwitch(active: Int, count: Int, onSwitch: (Int) -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            AppIcons.Back, strings.prevVersion,
            Modifier.size(32.dp).clip(CircleShape).clickable(enabled = active > 0) { onSwitch(-1) }.padding(8.dp),
            tint = if (active > 0) colors.sub else colors.sub.copy(alpha = 0.3f),
        )
        Text("${active + 1} / $count", color = colors.sub, fontSize = 13.sp)
        Icon(
            AppIcons.ChevronRight, strings.nextVersion,
            Modifier.size(32.dp).clip(CircleShape).clickable(enabled = active < count - 1) { onSwitch(+1) }.padding(8.dp),
            tint = if (active < count - 1) colors.sub else colors.sub.copy(alpha = 0.3f),
        )
    }
}

/** 回答引用的网页：收起时是一颗「引用 N 个网页」，点开是列表，点一条用浏览器打开 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Sources(message: Message) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable(message.id + "src") { mutableStateOf(false) }
    // 展开后把整个列表露出来，不让输入框挡住下半截
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(260)
            requester.bringIntoView()
        }
    }
    Column(Modifier.padding(top = 12.dp).bringIntoViewRequester(requester)) {
        Row(
            Modifier.clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape)
                .clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Globe, null, Modifier.size(15.dp), tint = colors.sub)
            Spacer(Modifier.width(6.dp))
            Text(strings.cited(message.sources.size), color = colors.sub, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "srcChevron")
            Icon(AppIcons.ChevronDown, null, Modifier.size(14.dp).rotate(turn), tint = colors.sub)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.card)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp)).padding(vertical = 4.dp),
            ) {
                message.sources.forEachIndexed { index, source ->
                    Row(
                        Modifier.fillMaxWidth().clickable { runCatching { uriHandler.openUri(source.url) } }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text("${index + 1}", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.title.ifEmpty { source.url }, color = colors.text, fontSize = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                source.site.ifEmpty { hostOf(source.url) }, color = colors.sub, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun hostOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

@Composable
private fun InputPanel(vm: ChatViewModel) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    var text by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(28.dp)
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    // 系统的照片选择器，不用申请权限；一次最多 9 张
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        vm.addImages(uris) { Toast.makeText(context, strings.imageFailed, Toast.LENGTH_SHORT).show() }
    }
    // 语音输入：用手机自带的语音识别（系统弹出说话框），说完的文字接在输入框后面
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { said ->
            text = if (text.isBlank()) said else text.trimEnd() + (if (vm.english) " " else "") + said
        }
    }
    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (vm.english) "en-US" else "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, strings.speakNow)
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, strings.noSpeechService, Toast.LENGTH_LONG).show()
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addFiles(uris) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    var addMenu by remember { mutableStateOf(false) }
    val canSend = (text.isNotBlank() || vm.pendingImages.isNotEmpty() || vm.pendingFiles.isNotEmpty()) && !vm.uploadingFiles

    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
            .clip(shape).background(colors.card).border(1.dp, colors.border, shape).padding(14.dp),
    ) {
        // 选好还没发的图片：一排缩略图，右上角的叉去掉
        if (vm.pendingImages.isNotEmpty() || vm.importingImages) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.pendingImages.forEach { path ->
                    Box {
                        LocalImage(path, 200, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                                .clickable { vm.removePending(path) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AppIcons.Close, strings.removeImage, Modifier.size(12.dp), tint = Color.White) }
                    }
                }
                if (vm.importingImages) Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(colors.group), contentAlignment = Alignment.Center) { TypingDots() }
            }
        }
        // 选好的文档：一排小卡片，还在解析的显示「正在解析…」
        if (vm.pendingFiles.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.pendingFiles.forEach { f ->
                    FileChip(f.name, if (f.fileId == null) (if (Documents.isZip(f.name)) strings.unzipping else strings.parsing) else Documents.sizeText(f.size), busy = f.fileId == null) { vm.removeFile(f.key) }
                }
            }
        }
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
                Chip(AppIcons.Atom, strings.deepThink, vm.thinkOn, Modifier.weight(1f, fill = false)) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.toggleThink() }
                Chip(AppIcons.Globe, strings.smartSearch, vm.searchOn, Modifier.weight(1f, fill = false)) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.toggleSearch() }
            }
            Spacer(Modifier.width(8.dp))
            // 加号弹出小菜单：图片 / 文件
            Box {
                Icon(
                    AppIcons.PlusCircle, strings.addImage,
                    Modifier.size(40.dp).clip(CircleShape).clickable { addMenu = true }.padding(5.dp),
                    tint = colors.text,
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(strings.pickPhotos) },
                        leadingIcon = { Icon(AppIcons.Image, null, Modifier.size(20.dp)) },
                        onClick = {
                            addMenu = false
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.pickFile) },
                        leadingIcon = { Icon(AppIcons.Doc, null, Modifier.size(20.dp)) },
                        onClick = { addMenu = false; pickFiles.launch(arrayOf("*/*")) },
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            // 右下角的按钮：生成中是停止，有字是发送，否则是语音；切换时淡入淡出
            Crossfade(if (vm.generating) 0 else if (canSend) 1 else 2, label = "action") { state ->
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        0 -> Box(
                            Modifier.size(36.dp).clip(CircleShape).background(colors.text).clickable { vm.stop() },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colors.card)) }

                        1 -> Box(
                            Modifier.size(36.dp).clip(CircleShape).background(colors.accentBrush).clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.send(text)
                                text = ""
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AppIcons.ArrowUp, null, Modifier.size(20.dp), tint = Color.White) }

                        else -> Icon(
                            AppIcons.VoiceCircle, strings.voiceInput,
                            Modifier.size(40.dp).clip(CircleShape).clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                startVoice()
                            }.padding(5.dp),
                            tint = colors.text,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalColors.current
    val content by animateColorAsState(if (active) colors.accent else colors.text, label = "chip")
    val fill by animateColorAsState(if (active) colors.accentBg else Color.Transparent, label = "chipFill")
    val line by animateColorAsState(if (active) colors.accent.copy(alpha = 0.4f) else colors.border, label = "chipLine")
    Row(
        modifier.clip(CircleShape)
            .background(fill)
            .border(1.dp, line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = content)
        Spacer(Modifier.width(5.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 一个文档：图标、文件名、大小（或「正在解析…」）；onRemove 不为 null 时右边有叉 */
@Composable
private fun FileChip(name: String, detail: String, busy: Boolean = false, onRemove: (() -> Unit)? = null) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    Row(
        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).background(colors.group)
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(colors.accentBg), contentAlignment = Alignment.Center) {
            Text(Documents.extension(name).uppercase().take(4).ifEmpty { "DOC" }, color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 8.dp).weight(1f, fill = false)) {
            Text(name, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = if (busy) colors.accent else colors.sub, fontSize = 12.sp, maxLines = 1)
        }
        if (onRemove != null) Icon(
            AppIcons.Close, strings.removeImage,
            Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable(onClick = onRemove).padding(7.dp), tint = colors.sub,
        )
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
