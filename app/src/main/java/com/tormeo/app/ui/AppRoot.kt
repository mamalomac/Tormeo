package com.tormeo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.tormeo.app.diag.Diagnostics

/** Minimal back-stack navigation (no extra navigation library – keeps the APK small). */
sealed class Screen(val key: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data class Detail(val type: String, val id: String, val name: String, val poster: String?) : Screen("detail/$type/$id")
    data class Streams(
        val type: String,
        val metaId: String,
        val videoId: String,
        val title: String,
        val subtitle: String?,
        val poster: String?,
        val runtimeMinutes: Int?,
        val autoPlay: Boolean,
    ) : Screen("streams/$videoId/$autoPlay")
}

class Navigator(private val stack: MutableList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

@Composable
fun AppRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Navigator(stack) }
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = stack.size > 1) { nav.back() }
    var crashReport by remember { mutableStateOf(Diagnostics.takePendingReport()) }
    crashReport?.let { CrashReportDialog(it) { crashReport = null } }

    // White default text color (otherwise Text falls back to black on our dark background)
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            Modifier.fillMaxSize().background(Bg)
                // keep content clear of the status bar, navigation bar, display cutout and keyboard
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val screen = stack.last()
            holder.SaveableStateProvider(screen.key) {
                when (screen) {
                    Screen.Home -> HomeScreen(nav)
                    Screen.Search -> SearchScreen(nav)
                    Screen.Settings -> SettingsScreen()
                    is Screen.Detail -> DetailScreen(nav, screen)
                    is Screen.Streams -> StreamsScreen(screen)
                }
            }
        }
    }
}
