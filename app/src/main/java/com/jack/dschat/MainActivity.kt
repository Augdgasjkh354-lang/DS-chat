package com.jack.dschat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jack.dschat.ui.ChatScreen
import com.jack.dschat.ui.Chinese
import com.jack.dschat.ui.DarkColors
import com.jack.dschat.ui.English
import com.jack.dschat.ui.LightColors
import com.jack.dschat.ui.LocalColors
import com.jack.dschat.ui.LocalStrings
import com.jack.dschat.ui.SettingsSheet
import com.jack.dschat.ui.Sidebar
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun App(vm: ChatViewModel = viewModel()) {
    val dark = when (vm.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors
    val density = LocalDensity.current

    // 状态栏、导航栏上的图标颜色跟着 App 里选的外观走，不是跟着系统
    val view = LocalView.current
    val activity = LocalContext.current as? ComponentActivity
    SideEffect {
        activity?.window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(
        LocalColors provides colors,
        LocalStrings provides if (vm.english) English else Chinese,
        // 字体大小：在系统字号的基础上再乘一个倍数
        LocalDensity provides Density(density.density, density.fontScale * vm.fontScale),
    ) {
        val scheme = if (dark) {
            darkColorScheme(primary = colors.accent, surface = colors.group, surfaceContainer = colors.group, onSurface = colors.text, background = colors.bg)
        } else {
            lightColorScheme(primary = colors.accent, surface = colors.group, surfaceContainer = colors.group, onSurface = colors.text, background = colors.bg)
        }
        MaterialTheme(colorScheme = scheme) { Root(vm) }
    }
}

/** 主聊天页盖在侧边栏上面，往右拖（或点左上角）把它推开，露出下面的侧边栏 */
@Composable
private fun Root(vm: ChatViewModel) {
    val colors = LocalColors.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val notReady = { Toast.makeText(context, strings.notReady, Toast.LENGTH_SHORT).show() }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.side)) {
        val openPx = constraints.maxWidth * 0.83f
        val offset = remember { Animatable(0f) }
        val progress = (offset.value / openPx).coerceIn(0f, 1f)

        fun settle(open: Boolean) {
            if (open) focus.clearFocus()
            scope.launch { offset.animateTo(if (open) openPx else 0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        }

        BackHandler(enabled = progress > 0f && !showSettings) { settle(false) }

        val drag = rememberDraggableState { delta ->
            scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, openPx)) }
        }
        Box(
            Modifier.fillMaxSize().draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    // 甩得快看方向，否则看过没过半
                    settle(if (velocity > 800f) true else if (velocity < -800f) false else offset.value > openPx / 2)
                },
            ),
        ) {
            Sidebar(
                vm = vm,
                onPick = { settle(false) },
                onSettings = { showSettings = true },
                modifier = Modifier
                    .width(with(LocalDensity.current) { openPx.toDp() })
                    .fillMaxHeight()
                    // 跟着拉开的进度淡入，并从左边轻轻滑进来
                    .graphicsLayer { alpha = 0.3f + 0.7f * progress; translationX = -24.dp.toPx() * (1f - progress) },
            )
            val shape = RoundedCornerShape(36.dp * progress)
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .clip(shape)
                    .background(colors.bg)
                    .border(1.dp, colors.border.copy(alpha = colors.border.alpha * progress), shape),
            ) {
                ChatScreen(vm = vm, onMenu = { settle(true) }, onNotReady = notReady)
                if (progress > 0f) {
                    // 侧边栏开着的时候，点主页面任何地方都是收起
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = (if (colors.isDark) 0.35f else 0.12f) * progress)).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { settle(false) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            SettingsSheet(vm = vm, onClose = { showSettings = false }, onNotReady = notReady)
        }
    }
}
