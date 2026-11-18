package com.jack.dschat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 自己画的一套线条图标（24×24），不引整个图标库 */
object AppIcons {
    private fun line(path: String, width: Float = 1.8f) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(path),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    private const val CIRCLE = "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z"

    val Menu = line("M5 8.5H19 M5 15.5H13", 2f)
    val NewChat = line("M12 3.5a8.5 8.5 0 1 1-4.2 15.9L4 20.5l1.1-3.8A8.5 8.5 0 0 1 12 3.5z M12 8.5v7 M8.5 12h7")
    val Search = line("M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14z M16 16l4 4")
    val Select = line("M5 7.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 0 0-3 0z M11.5 7.5h7.5 M5 16.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 0 0-3 0z M11.5 16.5h7.5")
    val More = line("M6 12h.01 M12 12h.01 M18 12h.01", 2.8f)
    val Close = line("M6 6l12 12 M18 6L6 18", 2f)
    val Back = line("M15 5l-7 7 7 7", 2f)
    val ChevronRight = line("M9 5l7 7-7 7", 2f)
    val ChevronDown = line("M6 9l6 6 6-6", 2f)
    val UpDown = line("M8 9l4-4 4 4 M8 15l4 4 4-4", 2f)
    val Person = line("M12 4a4 4 0 1 0 0 8a4 4 0 0 0 0-8z M4.5 20a7.5 7.5 0 0 1 15 0")
    val Database = line("M4 6.5c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6.5V12c0 1.7 3.6 3 8 3s8-1.3 8-3V6.5 M4 12v5.5c0 1.7 3.6 3 8 3s8-1.3 8-3V12")
    val Globe = line("$CIRCLE M3 12h18 M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3z")
    val Moon = line("M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z")
    val Font = line("M3 18l4-10 4 10 M4.5 15h5 M13 18l4.5-12L22 18 M14.8 14h5.4")
    val Sparkle = line("M12 4l1.8 4.7 4.7 1.8-4.7 1.8L12 17l-1.8-4.7-4.7-1.8 4.7-1.8z M18.5 16v4 M16.5 18h4")
    val Speaker = line("M4 10v4h3l5 4V6L7 10z M15.5 9a4 4 0 0 1 0 6 M18 6.5a7.5 7.5 0 0 1 0 11")
    val SpeakerOff = line("M4 10v4h3l5 4V6L7 10z M16 9.5l5 5 M21 9.5l-5 5")
    val Info = line("$CIRCLE M12 11v5.5 M12 7.8h.01")
    val Doc = line("M6 3.5h9l4 4V20a.5 .5 0 0 1-.5 .5h-12.5a.5 .5 0 0 1-.5-.5V4a.5 .5 0 0 1 .5-.5z M9 9h4 M9 13h6 M9 17h4")
    val Atom = line("M5.64 5.64a9 3.8 45 1 0 12.72 12.72a9 3.8 45 1 0-12.72-12.72z M18.36 5.64a9 3.8 -45 1 0-12.72 12.72a9 3.8 -45 1 0 12.72-12.72z M12 12h.01")
    val PlusCircle = line("$CIRCLE M12 8v8 M8 12h8")
    val VoiceCircle = line("$CIRCLE M9 12h.01 M12 9.6a3.4 3.4 0 0 1 0 4.8 M14.5 7.5a6.4 6.4 0 0 1 0 9")
    val ArrowUp = line("M12 19V5 M6 11l6-6 6 6", 2.2f)
    val ArrowDown = line("M12 5v14 M6 13l6 6 6-6", 2.2f)
    /** 角色扮演：一张面具 */
    val Mask = line("M5 5.5c4.5-1.5 9.5-1.5 14 0v5.5a7 7 0 0 1-14 0z M9 10.5h1.5 M13.5 10.5H15 M9.5 14.5c1.5 1.2 3.5 1.2 5 0")
    /** 导入：往下的箭头落进托盘 */
    val Import = line("M12 4v11 M7.5 10.5L12 15l4.5-4.5 M5 19h14")
    /** 导出：箭头从托盘往上出去 */
    val Export = line("M12 15V4 M7.5 8.5L12 4l4.5 4.5 M5 19h14")
    val Copy = line("M9 9h10v11H9z M5 15V4h10")
    val Refresh = line("M19 12a7 7 0 1 1-2.1-5 M19 4v4h-4")
    val Trash = line("M5 7h14 M10 7V4.5h4V7 M7 7l1 13h8l1-13 M10.5 11v5.5 M13.5 11v5.5")
    val Edit = line("M5 19h3.5L19 8.5 15.5 5 5 15.5z M13.5 7l3.5 3.5")
    val Check = line("M5 12.5l4.5 4.5L19 7.5", 2.2f)
}
