package com.jack.dschat.ui

import androidx.compose.runtime.staticCompositionLocalOf
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
    val accentBg: Color,
    /** 用户消息的气泡 */
    val bubble: Color,
    val danger: Color,
)

val DarkColors = AppColors(
    isDark = true,
    bg = Color(0xFF111112),
    side = Color(0xFF0C0C0D),
    card = Color(0xFF1E1E20),
    sheet = Color(0xFF1C1C1E),
    group = Color(0xFF29292B),
    border = Color(0x24FFFFFF),
    text = Color(0xFFF5F5F5),
    sub = Color(0xFF8E8E93),
    accent = Color(0xFF6C8CFF),
    accentBg = Color(0xFF1D2948),
    bubble = Color(0xFF2A2A2D),
    danger = Color(0xFFFF5A52),
)

val LightColors = AppColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    side = Color(0xFFF5F5F7),
    card = Color(0xFFF3F4F6),
    sheet = Color(0xFFF2F2F7),
    group = Color(0xFFFFFFFF),
    border = Color(0x1A000000),
    text = Color(0xFF111111),
    sub = Color(0xFF8A8A8F),
    accent = Color(0xFF4A6CF7),
    accentBg = Color(0xFFE3E9FF),
    bubble = Color(0xFFEEF0F3),
    danger = Color(0xFFE5392F),
)

val LocalColors = staticCompositionLocalOf { DarkColors }
