package com.jack.dschat.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** 聊天页现在是不是垫着背景图：是的话 AI 的回复也要包进气泡，不然字压在照片上看不清 */
val LocalOnImage = staticCompositionLocalOf { false }

/** 模糊要 Android 12 以上才有，老系统上不显示这个滑块 */
val blurSupported get() = Build.VERSION.SDK_INT >= 31

/**
 * 聊天背景：图片铺满（居中裁切），按需模糊，再盖一层和主题同色的遮罩保证字看得清。
 * 聊天页和设置里的预览用的是同一个，保证看到的就是实际效果。
 */
@Composable
fun ChatBackground(image: ImageBitmap, dim: Float, blur: Float, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    Box(modifier.clipToBounds()) {
        Image(
            image, null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (blur > 0f) Modifier.blur((blur * 24).dp) else Modifier),
        )
        Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = dim)))
    }
}
