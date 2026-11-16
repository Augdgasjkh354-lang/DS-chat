package com.jack.dschat.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class AppColors(
    val isDark: Boolean,
    /** 主聊天页的底色 */
    val bg: Color,
    /** 侧边栏的底色 */
    val side: Color,
    /** 输入框、搜索框 */
    val card: Color,
    /** 设置页的底色 */
    val sheet: Color,
    /** 设置页里一组一组的卡片 */
    val group: Color,
    val border: Color,
    val text: Color,
    val sub: Color,
    val accent: Color,
    /** 渐变的另一头（标志、发送按钮） */
    val accent2: Color,
    val accentBg: Color,
    /** 用户消息的气泡 */
    val bubble: Color,
    val danger: Color,
)

val DarkColors = AppColors(
    isDark = true,
    bg = Color(0xFF0F1014),
    side = Color(0xFF0A0B0E),
    card = Color(0xFF1A1B20),
    sheet = Color(0xFF15161A),
    group = Color(0xFF202127),
    border = Color(0x1FFFFFFF),
    text = Color(0xFFF2F3F5),
    sub = Color(0xFF8B8D98),
    accent = Color(0xFF7088FF),
    accent2 = Color(0xFFA57CFF),
    accentBg = Color(0xFF1C2244),
    bubble = Color(0xFF24262E),
    danger = Color(0xFFFF6259),
)

val LightColors = AppColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    side = Color(0xFFF5F6F9),
    card = Color(0xFFF4F5F8),
    sheet = Color(0xFFF2F3F7),
    group = Color(0xFFFFFFFF),
    border = Color(0x17000000),
    text = Color(0xFF14151A),
    sub = Color(0xFF858793),
    accent = Color(0xFF4F68F5),
    accent2 = Color(0xFF8A5CF6),
    accentBg = Color(0xFFE6EAFF),
    bubble = Color(0xFFEEF0F6),
    danger = Color(0xFFE5392F),
)

val LocalColors = staticCompositionLocalOf { DarkColors }

/** 标志和发送按钮用的蓝紫渐变，从左上到右下 */
val AppColors.accentBrush get() = Brush.linearGradient(listOf(accent, accent2), Offset.Zero, Offset.Infinite)
