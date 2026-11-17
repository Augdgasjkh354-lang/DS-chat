package com.jack.dschat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.jack.dschat.R
import androidx.compose.ui.unit.sp
import com.jack.dschat.ChatViewModel
import com.jack.dschat.data.Conversation
import com.jack.dschat.data.groupOf
import com.jack.dschat.data.matches

/** 侧边栏：搜索、按时间分组的历史记录、底下是用户和设置入口 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(vm: ChatViewModel, onPick: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    // 多选删除：null 是没在选
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }

    val now = System.currentTimeMillis()
    val groups = vm.conversations.filter { it.matches(query) }
        .sortedByDescending { it.updatedAt }
        .groupBy { groupOf(now, it.updatedAt) }
        .toList()

    Column(modifier.statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(strings.appName, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PlainButton(AppIcons.NewChat, onClick = { vm.newChat(); onPick() })
        }
        // 搜索框
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 4.dp).fillMaxWidth()
                .clip(CircleShape).background(colors.card).border(1.dp, colors.border, CircleShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Search, null, Modifier.size(20.dp), tint = colors.sub)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(strings.searchHint, color = colors.sub, fontSize = 16.sp, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Icon(AppIcons.Close, null, Modifier.size(20.dp).clip(CircleShape).clickable { query = "" }.padding(2.dp), tint = colors.sub)
            }
        }

        if (groups.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) strings.noChats else strings.noResults, color = colors.sub, fontSize = 15.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                groups.forEachIndexed { index, (group, list) ->
                    item(key = "header-$group") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 2.dp).height(36.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(strings.group(group), color = colors.sub, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            // 多选的入口只放在第一组标题的右边
                            if (index == 0) {
                                Icon(
                                    AppIcons.Select, null,
                                    Modifier.size(36.dp).clip(CircleShape).clickable { selected = if (selected == null) emptySet() else null }.padding(8.dp),
                                    tint = if (selected != null) colors.accent else colors.sub,
                                )
                            }
                        }
                    }
                    items(list, key = { it.id }) { conversation ->
                        val picking = selected
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (picking == null && conversation.id == vm.currentId) colors.card else Color.Transparent)
                                    .combinedClickable(
                                        onClick = {
                                            if (picking != null) {
                                                selected = if (conversation.id in picking) picking - conversation.id else picking + conversation.id
                                            } else {
                                                vm.open(conversation.id)
                                                onPick()
                                            }
                                        },
                                        onLongClick = { if (picking == null) menu = true },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (picking != null) {
                                    val on = conversation.id in picking
                                    Box(
                                        Modifier.size(20.dp).clip(CircleShape)
                                            .background(if (on) colors.accent else Color.Transparent)
                                            .border(1.5.dp, if (on) colors.accent else colors.sub, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Icon(AppIcons.Check, null, Modifier.size(13.dp), tint = Color.White) }
                                    Spacer(Modifier.width(12.dp))
                                }
                                Text(conversation.title, color = colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(strings.rename) },
                                    leadingIcon = { Icon(AppIcons.Edit, null, Modifier.size(20.dp)) },
                                    onClick = { menu = false; renaming = conversation },
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.delete, color = colors.danger) },
                                    leadingIcon = { Icon(AppIcons.Trash, null, Modifier.size(20.dp), tint = colors.danger) },
                                    onClick = { menu = false; deleting = setOf(conversation.id) },
                                )
                            }
                        }
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
            }
        }

        // 底栏：平时是用户和设置入口，多选时换成删除 / 取消
        val picking = selected
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 10.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picking != null) {
                TextButton(onClick = { selected = null }) { Text(strings.cancel, color = colors.sub, fontSize = 16.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = picking.isNotEmpty(), onClick = { deleting = picking }) {
                    Text(strings.deleteCount(picking.size), color = if (picking.isEmpty()) colors.sub else colors.danger, fontSize = 16.sp)
                }
            } else {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onSettings).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentBrush), contentAlignment = Alignment.Center) {
                        Text(strings.userName.take(1), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(strings.userName, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                PlainButton(AppIcons.More, tint = colors.sub, onClick = onSettings)
            }
        }
    }

    renaming?.let { conversation ->
        var name by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(strings.rename) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(conversation.id, name); renaming = null }) { Text(strings.ok) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(strings.cancel) } },
        )
    }

    deleting?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(strings.deleteCount(ids.size)) },
            text = { Text(strings.deleteConfirm) },
            confirmButton = {
                TextButton(onClick = { vm.delete(ids); deleting = null; selected = null }) { Text(strings.delete, color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings.cancel) } },
        )
    }
}
