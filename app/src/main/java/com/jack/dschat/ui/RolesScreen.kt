package com.jack.dschat.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.dschat.ChatViewModel
import com.jack.dschat.data.Character
import com.jack.dschat.data.CharacterStore
import com.jack.dschat.data.Conversation
import com.jack.dschat.data.LoreEntry
import com.jack.dschat.data.Lorebook
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import kotlin.math.roundToInt

/** 角色头像：有图用图，没图是名字第一个字配底色 */
@Composable
fun RoleAvatar(vm: ChatViewModel, c: Character, size: Dp) {
    val pic = vm.avatars[c.id]
    if (pic != null) {
        Image(pic, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Color(c.color)), contentAlignment = Alignment.Center) {
            Text(c.name.take(1), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 从底下升上来的整页，和设置页一个样子；title 为空时不显示返回键 */
@Composable
private fun Sheet(title: String, onBack: (() -> Unit)?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalColors.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .statusBarsPadding().padding(top = 10.dp),
    ) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)).background(colors.sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().imePadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                if (onBack != null) CircleButton(AppIcons.Back, Modifier.align(Alignment.CenterStart), onClick = onBack)
                Text(title, color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                CircleButton(AppIcons.Close, Modifier.align(Alignment.CenterEnd), onClick = onClose)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), content = content)
        }
    }
}

private sealed interface RolePage {
    data object List : RolePage
    data object Me : RolePage
    /** id 为 null 是新建 */
    data class Edit(val id: String?) : RolePage
}

/** 角色扮演：角色列表、新建 / 编辑角色、导入角色卡、「我」的设定 */
@Composable
fun RolesSheet(vm: ChatViewModel, onClose: () -> Unit, onStarted: () -> Unit) {
    val strings = LocalStrings.current
    var page by remember { mutableStateOf<RolePage>(RolePage.List) }
    // 编辑页里进了世界书时，左上角的返回先退回世界书的上一层
    var innerBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    BackHandler { if (page == RolePage.List) onClose() else page = RolePage.List }
    when (val p = page) {
        RolePage.List -> Sheet(strings.roles, null, onClose) {
            RoleList(vm, onEdit = { page = RolePage.Edit(it) }, onMe = { page = RolePage.Me }, onStart = { vm.startRoleChat(it); onStarted() })
        }
        RolePage.Me -> Sheet(strings.me, { page = RolePage.List }, onClose) { MePage(vm) { page = RolePage.List } }
        is RolePage.Edit -> Sheet(if (p.id == null) strings.newRole else strings.editRole, innerBack ?: { page = RolePage.List }, onClose) {
            RoleEditor(vm, vm.character(p.id), setBack = { innerBack = it }) { page = RolePage.List }
        }
    }
}

@Composable
private fun ColumnScope.RoleList(vm: ChatViewModel, onEdit: (String?) -> Unit, onMe: () -> Unit, onStart: (Character) -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCard(uri) { _, text -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    Spacer(Modifier.height(4.dp))
    Card {
        SettingRow(AppIcons.Person, strings.me, value = vm.persona.name.ifBlank { "" }, onClick = onMe)
        Divider()
        SettingRow(AppIcons.Edit, strings.newRole) { onEdit(null) }
        Divider()
        SettingRow(AppIcons.Import, strings.importCard) {
            picker.launch(arrayOf("image/png", "application/json", "application/octet-stream", "text/plain"))
        }
    }
    Text(strings.importCardHint, color = colors.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))

    Section(strings.allRoles)
    if (vm.characters.isEmpty()) {
        Text(strings.noRoles, color = colors.sub, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
    } else Card {
        vm.characters.forEachIndexed { i, c ->
            if (i > 0) Divider(inset = 76.dp)
            Row(
                Modifier.fillMaxWidth().clickable { onStart(c) }.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleAvatar(vm, c, 46.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = (c.description.ifBlank { c.greeting }).replace("{{user}}", vm.persona.name.ifBlank { strings.defaultUser })
                        .replace("{{char}}", c.name).lineSequence().firstOrNull().orEmpty()
                    if (line.isNotBlank()) Text(line, color = colors.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlainButton(AppIcons.Edit, tint = colors.sub) { onEdit(c.id) }
            }
        }
    }
}

/** 带标题的多行输入框 */
@Composable
private fun Field(label: String, value: String, hint: String = "", minLines: Int = 1, onChange: (String) -> Unit) {
    val colors = LocalColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (label.isNotEmpty()) {
            Text(label, color = colors.sub, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = (24 * minLines).dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = colors.sub.copy(alpha = 0.7f), fontSize = 16.sp, lineHeight = 24.sp)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.MePage(vm: ChatViewModel, onDone: () -> Unit) {
    val strings = LocalStrings.current
    val colors = LocalColors.current
    var name by rememberSaveable { mutableStateOf(vm.persona.name) }
    var desc by rememberSaveable { mutableStateOf(vm.persona.description) }
    Spacer(Modifier.height(4.dp))
    Card {
        Field(strings.myName, name, strings.defaultUser) { name = it }
        Divider(inset = 20.dp)
        Field(strings.myDesc, desc, strings.myDescHint, minLines = 4) { desc = it }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Text(
            strings.save, color = colors.accent, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                vm.updatePersona(vm.persona.copy(name = name.trim(), description = desc.trim()))
                onDone()
            }.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun ColumnScope.RoleEditor(vm: ChatViewModel, original: Character?, setBack: ((() -> Unit)?) -> Unit = {}, onDone: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val id = rememberSaveable { original?.id ?: CharacterStore.newId() }
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var desc by rememberSaveable { mutableStateOf(original?.description.orEmpty()) }
    var greeting by rememberSaveable { mutableStateOf(original?.greeting.orEmpty()) }
    var personality by rememberSaveable { mutableStateOf(original?.personality.orEmpty()) }
    var scenario by rememberSaveable { mutableStateOf(original?.scenario.orEmpty()) }
    var examples by rememberSaveable { mutableStateOf(original?.examples.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(original?.instructions.orEmpty()) }
    var creativity by rememberSaveable { mutableStateOf(original?.creativity ?: Character.DEFAULT_CREATIVITY) }
    var avatar by remember { mutableStateOf<Uri?>(null) }
    var more by rememberSaveable { mutableStateOf(listOf(personality, scenario, examples, instructions).any { it.isNotBlank() }) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 世界书跟着角色一起保存；lore：null 是角色编辑页，-1 是世界书列表，>= 0 是在改第几条
    var book by remember { mutableStateOf(original?.book ?: Lorebook()) }
    var lore by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) avatar = it }
    DisposableEffect(lore) {
        val page = lore
        setBack(if (page == null) null else ({ lore = if (page >= 0) -1 else null }))
        onDispose { setBack(null) }
    }
    lore?.let { page ->
        BackHandler { lore = if (page >= 0) -1 else null }
        if (page >= 0 && page < book.entries.size) {
            LoreEntryEditor(
                book.entries[page],
                onChange = { e -> book = book.copy(entries = book.entries.toMutableList().also { it[page] = e }) },
                onDelete = { book = book.copy(entries = book.entries.filterIndexed { i, _ -> i != page }); lore = -1 },
                onDone = {
                    // 什么都没写的新条目不留
                    if (book.entries[page].content.isBlank() && book.entries[page].keys.isEmpty()) {
                        book = book.copy(entries = book.entries.filterIndexed { i, _ -> i != page })
                    }
                    lore = -1
                },
            )
        } else LoreList(vm, book, name.trim(), onChange = { book = it }, onOpen = { lore = it }, onDone = { lore = null })
        return
    }
    val draft = (original ?: Character(id, "", color = vm.palette(id), createdAt = System.currentTimeMillis())).copy(name = name.ifBlank { "?" })
    // 编辑页上现在的样子（没保存的改动也算）
    fun edited() = draft.copy(
        name = name.trim(), description = desc.trim(), greeting = greeting.trim(), personality = personality.trim(),
        scenario = scenario.trim(), examples = examples.trim(), instructions = instructions.trim(), creativity = creativity,
        book = book,
    )
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportCard(edited(), avatar, uri) { _, text -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    // 头像：点一下从相册换
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.clip(CircleShape).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            val picked = avatar
            if (picked != null) {
                val bitmap = remember(picked) {
                    runCatching { com.jack.dschat.data.decodeImage(context, picked, 512) }.getOrNull()
                }
                if (bitmap != null) Image(
                    bitmap.asImageBitmap(), null,
                    Modifier.size(84.dp).clip(CircleShape), contentScale = ContentScale.Crop,
                ) else RoleAvatar(vm, draft, 84.dp)
            } else RoleAvatar(vm, draft, 84.dp)
        }
        Text(strings.changeAvatar, color = colors.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    }

    Card {
        Field(strings.roleName, name) { name = it }
        Divider(inset = 20.dp)
        Field(strings.roleDesc, desc, strings.roleDescHint, minLines = 4) { desc = it }
        Divider(inset = 20.dp)
        Field(strings.roleGreeting, greeting, strings.roleGreetingHint, minLines = 2) { greeting = it }
    }

    Section(strings.creativity)
    Card {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.steady, color = colors.sub, fontSize = 13.sp)
            Slider(
                value = creativity, onValueChange = { creativity = (it * 10).roundToInt() / 10f }, valueRange = 0.5f..1.8f,
                colors = sliderColors(), modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(strings.wild, color = colors.sub, fontSize = 13.sp)
        }
    }
    Text("%.1f · %s".format(creativity, strings.creativityNote), color = colors.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))

    Row(
        Modifier.padding(top = 20.dp, bottom = 10.dp).clip(CircleShape).clickable { more = !more }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(strings.moreSettings, color = colors.sub, fontSize = 14.sp)
        Icon(AppIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(14.dp), tint = colors.sub)
    }
    Card {
        SettingRow(AppIcons.Doc, strings.lorebook, value = strings.loreCount(book.entries.size)) { lore = -1 }
    }
    Spacer(Modifier.height(10.dp))
    AnimatedVisibility(more) {
        Card {
            Field(strings.rolePersonality, personality, minLines = 2) { personality = it }
            Divider(inset = 20.dp)
            Field(strings.roleScenario, scenario, minLines = 2) { scenario = it }
            Divider(inset = 20.dp)
            Field(strings.roleExamples, examples, strings.roleExamplesHint, minLines = 3) { examples = it }
            Divider(inset = 20.dp)
            Field(strings.roleInstructions, instructions, minLines = 2) { instructions = it }
        }
    }

    Spacer(Modifier.height(16.dp))
    Card {
        Text(
            strings.save, color = if (name.isNotBlank()) colors.accent else colors.sub, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().clickable(enabled = name.isNotBlank()) {
                vm.saveCharacter(edited(), avatar) { Toast.makeText(context, strings.imageFailed, Toast.LENGTH_SHORT).show() }
                onDone()
            }.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        if (original != null) {
            Divider(inset = 20.dp)
            Text(
                strings.chatWith, color = colors.accent, fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.startRoleChat(original); onDone() }.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
    if (name.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Card {
            SettingRow(AppIcons.Export, strings.exportCard) { exporter.launch(name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png") }
        }
        Text(strings.exportCardHint, color = colors.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    }
    if (original != null) {
        Spacer(Modifier.height(16.dp))
        Card {
            Text(
                strings.deleteRole, color = colors.danger, fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(strings.deleteRole) },
            text = { Text(strings.deleteRoleConfirm) },
            confirmButton = { TextButton(onClick = { vm.deleteCharacter(id); confirmDelete = false; onDone() }) { Text(strings.delete, color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(strings.cancel) } },
        )
    }
}

/** 世界书列表：每条一行，右边开关启用；底下新建、导入 */
@Composable
private fun ColumnScope.LoreList(vm: ChatViewModel, book: Lorebook, roleName: String, onChange: (Lorebook) -> Unit, onOpen: (Int) -> Unit, onDone: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readLorebook(uri) { read, text ->
            if (read != null) onChange(book.copy(entries = book.entries + read.entries))
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportLorebook(book, roleName, uri) { _, text -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
    Text(strings.lorebook, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    Text(strings.loreHint, color = colors.sub, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp))
    if (book.entries.isEmpty()) {
        Text(strings.noEntries, color = colors.sub, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
    } else Card {
        book.entries.forEachIndexed { i, e ->
            if (i > 0) Divider(inset = 20.dp)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        e.comment.ifBlank { e.keys.firstOrNull() ?: strings.untitledEntry },
                        color = if (e.enabled) colors.text else colors.sub, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (e.constant) strings.alwaysOn else e.keys.joinToString("、"),
                        color = colors.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = e.enabled,
                    onCheckedChange = { on -> onChange(book.copy(entries = book.entries.toMutableList().also { it[i] = e.copy(enabled = on) })) },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, uncheckedTrackColor = colors.border, uncheckedBorderColor = Color.Transparent),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        SettingRow(AppIcons.Edit, strings.newEntry) {
            onChange(book.copy(entries = book.entries + LoreEntry("lore-" + CharacterStore.newId())))
            onOpen(book.entries.size)
        }
        Divider()
        SettingRow(AppIcons.Import, strings.importLore) {
            picker.launch(arrayOf("application/json", "image/png", "application/octet-stream", "text/plain"))
        }
        if (book.entries.isNotEmpty()) {
            Divider()
            SettingRow(AppIcons.Export, strings.exportLore) {
                exporter.launch(book.name.ifBlank { roleName.ifBlank { strings.lorebook } } + ".json")
            }
        }
    }
    Text(strings.importLoreHint, color = colors.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    Spacer(Modifier.height(16.dp))
    Card {
        Text(strings.done, color = colors.accent, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onDone).padding(horizontal = 20.dp, vertical = 16.dp))
    }
}

/** 改世界书的一条：备注、关键词、内容、始终生效、启用 */
@Composable
private fun ColumnScope.LoreEntryEditor(entry: LoreEntry, onChange: (LoreEntry) -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    var keys by remember(entry.id) { mutableStateOf(entry.keys.joinToString("，")) }
    Spacer(Modifier.height(4.dp))
    Card {
        Field(strings.entryName, entry.comment, strings.entryNameHint) { onChange(entry.copy(comment = it)) }
        Divider(inset = 20.dp)
        Field(strings.entryKeys, keys, strings.entryKeysHint) {
            keys = it
            onChange(entry.copy(keys = it.split(Regex("[,，、\n]")).map { k -> k.trim() }.filter { k -> k.isNotEmpty() }))
        }
        Divider(inset = 20.dp)
        Field(strings.entryContent, entry.content, strings.entryContentHint, minLines = 6) { onChange(entry.copy(content = it)) }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        ToggleRow(strings.alwaysOn, entry.constant) { onChange(entry.copy(constant = it)) }
        Divider(inset = 20.dp)
        ToggleRow(strings.entryEnabled, entry.enabled) { onChange(entry.copy(enabled = it)) }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Text(strings.done, color = colors.accent, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onDone).padding(horizontal = 20.dp, vertical = 16.dp))
        Divider(inset = 20.dp)
        Text(strings.deleteEntry, color = colors.danger, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(horizontal = 20.dp, vertical = 16.dp))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, uncheckedTrackColor = colors.border, uncheckedBorderColor = Color.Transparent),
        )
    }
}

/** 角色对话顶栏点开：前情回忆（能改、能马上整理），以及编辑这个角色 */
@Composable
fun MemorySheet(vm: ChatViewModel, conversation: Conversation, onClose: () -> Unit) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val role = vm.character(conversation.characterId) ?: return
    var editing by remember { mutableStateOf(false) }
    var innerBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    BackHandler { if (editing) editing = false else onClose() }
    if (editing) {
        Sheet(strings.editRole, innerBack ?: { editing = false }, onClose) { RoleEditor(vm, role, setBack = { innerBack = it }) { editing = false } }
        return
    }
    var text by remember(conversation.id, conversation.memory) { mutableStateOf(conversation.memory) }
    val busy = vm.remembering == conversation.id
    Sheet(role.name, null, { if (text.trim() != conversation.memory) vm.updateMemory(conversation.id, text); onClose() }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RoleAvatar(vm, role, 72.dp)
            Spacer(Modifier.height(10.dp))
            Text(strings.editRole, color = colors.accent, fontSize = 14.sp, modifier = Modifier.clip(CircleShape).clickable { editing = true }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Section(strings.memory)
        Card { Field("", text, strings.memoryEmpty, minLines = 6) { text = it } }
        if (conversation.memoryUpTo > 0) {
            Text(strings.memoryCovers(conversation.memoryUpTo), color = colors.sub, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Card {
            Text(
                strings.save, color = if (text.trim() != conversation.memory) colors.accent else colors.sub, fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = text.trim() != conversation.memory) {
                    vm.updateMemory(conversation.id, text)
                    Toast.makeText(context, strings.saved, Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Divider(inset = 20.dp)
            Text(
                if (busy) strings.rememberingNow else strings.rememberNow, color = if (busy) colors.sub else colors.accent, fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    if (text.trim() != conversation.memory) vm.updateMemory(conversation.id, text)
                    vm.remember(conversation.id, force = true) { ok ->
                        Toast.makeText(context, if (ok) strings.rememberDone else strings.rememberNothing, Toast.LENGTH_SHORT).show()
                    }
                }.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
