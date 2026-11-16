package com.jack.dschat.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.dschat.ChatViewModel
import kotlin.math.roundToInt

private enum class Page { Root, Language, Font, Data, Personalize }

/** 设置：从底下升上来的一整页，圆角顶，右上角关闭 */
@Composable
fun SettingsSheet(vm: ChatViewModel, onClose: () -> Unit, onNotReady: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    var page by rememberSaveable { mutableStateOf(Page.Root) }
    BackHandler { if (page == Page.Root) onClose() else page = Page.Root }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            // 挡住点击，不让它落到下面的聊天页
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .statusBarsPadding().padding(top = 10.dp),
    ) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)).background(colors.sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                if (page != Page.Root) CircleButton(AppIcons.Back, Modifier.align(Alignment.CenterStart)) { page = Page.Root }
                Text(
                    when (page) {
                        Page.Root -> strings.settings
                        Page.Language -> strings.language
                        Page.Font -> strings.fontSize
                        Page.Data -> strings.data
                        Page.Personalize -> strings.personalize
                    },
                    color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
                CircleButton(AppIcons.Close, Modifier.align(Alignment.CenterEnd), onClick = onClose)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                when (page) {
                    Page.Root -> RootPage(vm, onNotReady) { page = it }
                    Page.Language -> LanguagePage(vm)
                    Page.Font -> FontPage(vm)
                    Page.Data -> DataPage(vm)
                    Page.Personalize -> PersonalizePage(vm)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RootPage(vm: ChatViewModel, onNotReady: () -> Unit, go: (Page) -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Section(strings.sectionAccount)
    Card {
        SettingRow(AppIcons.Person, strings.account, onClick = onNotReady)
        Divider()
        SettingRow(AppIcons.Database, strings.data) { go(Page.Data) }
    }

    Section(strings.sectionApp)
    Card {
        SettingRow(AppIcons.Globe, strings.language, value = strings.languageName) { go(Page.Language) }
        Divider()
        // 外观直接在这一行弹出菜单选
        Box {
            var menu by remember { mutableStateOf(false) }
            val names = listOf(strings.themeSystem, strings.themeLight, strings.themeDark)
            SettingRow(AppIcons.Moon, strings.appearance, value = names[vm.themeMode.coerceIn(0, 2)], trailing = AppIcons.UpDown) { menu = true }
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    names.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            trailingIcon = { if (vm.themeMode == index) Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = colors.accent) },
                            onClick = { vm.updateTheme(index); menu = false },
                        )
                    }
                }
            }
        }
        Divider()
        SettingRow(AppIcons.Font, strings.fontSize) { go(Page.Font) }
        Divider()
        SettingRow(AppIcons.Sparkle, strings.personalize) { go(Page.Personalize) }
    }

    Section(strings.sectionVoice)
    Card { SettingRow(AppIcons.Speaker, strings.voice, onClick = onNotReady) }

    Section(strings.sectionAbout)
    Card {
        SettingRow(AppIcons.Info, strings.checkUpdate, value = version, onClick = onNotReady)
        Divider()
        SettingRow(AppIcons.Doc, strings.terms, onClick = onNotReady)
    }
}

@Composable
private fun LanguagePage(vm: ChatViewModel) {
    val colors = LocalColors.current
    Spacer(Modifier.height(8.dp))
    Card {
        listOf("简体中文" to false, "English" to true).forEachIndexed { index, (name, english) ->
            if (index > 0) Divider(inset = 20.dp)
            Row(
                Modifier.fillMaxWidth().clickable { vm.updateEnglish(english) }.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = colors.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (vm.english == english) Icon(AppIcons.Check, null, Modifier.size(20.dp), tint = colors.accent)
            }
        }
    }
}

@Composable
private fun FontPage(vm: ChatViewModel) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    // 五档：0.85、1.0、1.15、1.3、1.45
    val steps = listOf(0.85f, 1f, 1.15f, 1.3f, 1.45f)
    val index = steps.indexOfFirst { it >= vm.fontScale - 0.01f }.coerceAtLeast(0)
    Spacer(Modifier.height(8.dp))
    Card {
        Text(strings.fontPreview, color = colors.text, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.padding(20.dp))
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("A", color = colors.sub, fontSize = 13.sp)
            Slider(
                value = index.toFloat(),
                onValueChange = { vm.updateFontScale(steps[it.roundToInt().coerceIn(0, steps.lastIndex)]) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = steps.size - 2,
                colors = sliderColors(),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("A", color = colors.sub, fontSize = 22.sp)
        }
    }
}

@Composable
private fun DataPage(vm: ChatViewModel) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    var confirm by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text(strings.chatCount(vm.conversations.size), color = colors.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = vm.conversations.isNotEmpty()) { confirm = true }.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Trash, null, Modifier.size(22.dp), tint = colors.danger)
            Spacer(Modifier.width(12.dp))
            Text(strings.deleteAll, color = colors.danger, fontSize = 17.sp)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(strings.deleteAll) },
            text = { Text(strings.deleteAllConfirm) },
            confirmButton = { TextButton(onClick = { vm.clearAll(); confirm = false }) { Text(strings.delete, color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(strings.cancel) } },
        )
    }
}

/** 个性化：聊天背景图。上面是实时预览，下面选图和调遮罩、模糊 */
@Composable
private fun PersonalizePage(vm: ChatViewModel) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val image = vm.background
    // 系统自带的照片选择器，不需要任何权限
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pickBackground(uri) { Toast.makeText(context, strings.imageFailed, Toast.LENGTH_SHORT).show() }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Section(strings.chatBackground)
    Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(22.dp)).background(colors.bg).border(1.dp, colors.border, RoundedCornerShape(22.dp))) {
        if (image == null) {
            Text(strings.noBackground, color = colors.sub, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            ChatBackground(image, vm.backgroundDim, vm.backgroundBlur, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    strings.previewQuestion, color = colors.text, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        .background(colors.bubble.copy(alpha = 0.82f)).padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    strings.previewAnswer, color = colors.text, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                        .background(colors.card.copy(alpha = 0.72f)).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = pick).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (image == null) strings.pickImage else strings.changeImage, color = colors.accent, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.sub)
        }
        if (image != null) {
            Divider(inset = 20.dp)
            LevelSlider(strings.dimLevel, vm.backgroundDim, 0f..0.9f) { vm.updateBackgroundDim(it) }
            if (blurSupported) {
                Divider(inset = 20.dp)
                LevelSlider(strings.blurLevel, vm.backgroundBlur, 0f..1f) { vm.updateBackgroundBlur(it) }
            }
        }
    }
    if (image != null) {
        Spacer(Modifier.height(16.dp))
        Card {
            Text(
                strings.removeBackground, color = colors.danger, fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.clearBackground() }.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun LevelSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val colors = LocalColors.current
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, fontSize = 16.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range, colors = sliderColors(), modifier = Modifier.weight(1f).padding(start = 16.dp))
    }
}

/** 滑块没划过的那段用中性的灰，默认的颜色在浅色模式下偏粉 */
@Composable
private fun sliderColors() = SliderDefaults.colors(
    inactiveTrackColor = LocalColors.current.border,
    inactiveTickColor = LocalColors.current.sub,
)

@Composable
private fun Section(title: String) {
    Text(title, color = LocalColors.current.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(LocalColors.current.group), content = content)
}

@Composable
private fun Divider(inset: androidx.compose.ui.unit.Dp = 64.dp) {
    Box(Modifier.padding(start = inset, end = 16.dp).fillMaxWidth().height(1.dp).background(LocalColors.current.border))
}

@Composable
private fun SettingRow(icon: ImageVector, label: String, value: String = "", trailing: ImageVector = AppIcons.ChevronRight, onClick: () -> Unit) {
    val colors = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = colors.accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = colors.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = colors.sub, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
        }
        Icon(trailing, null, Modifier.size(18.dp), tint = colors.sub)
    }
}
