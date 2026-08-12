package com.moon.videomerger

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.moon.videomerger.editor.ui.EditorScreen
import com.moon.videomerger.editor.ui.EditorViewModel
import com.moon.videomerger.home.HomeScreen
import com.moon.videomerger.ui.MergeScreen
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoMergerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // ★ 每次进入 Editor 用唯一 key，强制重建 EditorScreen 和 ViewModel
    var editorSessionId by remember { mutableStateOf(0) }

    when (val s = screen) {
        is Screen.Home -> {
            HomeScreen(
                onNewProject = { uris ->
                    selectedUris = uris
                    editorSessionId++  // 新的编辑会话
                    screen = Screen.Editor
                },
                onOpenMerge = { uris ->
                    selectedUris = uris
                    screen = Screen.Merge
                },
                onOpenHistory = { path ->
                    // TODO: 从历史记录打开
                }
            )
        }
        is Screen.Editor -> {
            // ★ 用 key 强制重建 EditorScreen，避免旧 UI 状态残留
            key(editorSessionId) {
                val viewModel: EditorViewModel = viewModel()
                // selectedUris 非空时创建项目（只执行一次）
                LaunchedEffect(editorSessionId) {
                    if (selectedUris.isNotEmpty()) {
                        android.util.Log.d("AppNav", "LaunchedEffect: createProject uris=${selectedUris.size}")
                        viewModel.createProject(selectedUris)
                        // 不清空 selectedUris，避免 LaunchedEffect 重复触发
                    }
                }
                EditorScreen(
                    viewModel = viewModel,
                    onBack = {
                        viewModel.resetState()
                        selectedUris = emptyList()
                        screen = Screen.Home
                    }
                )
            }
        }
        is Screen.Merge -> {
            MergeScreen(
                onBack = {
                    selectedUris = emptyList()
                    screen = Screen.Home
                }
            )
        }
    }
}

sealed class Screen {
    object Home : Screen()
    object Editor : Screen()
    object Merge : Screen()
}

@Composable
fun VideoMergerTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF2196F3),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF0D47A1),
        onPrimaryContainer = Color.White,
        background = Color(0xFF000000),
        onBackground = Color.White,
        surface = Color(0xFF1A1A1A),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFCCCCCC),
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
