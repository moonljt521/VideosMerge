package com.moon.videomerger

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.moon.videomerger.home.HistoryScreen
import com.moon.videomerger.ui.DouyinScreen
import com.moon.videomerger.ui.GifScreen
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
    // ★ 进入编辑器的两种模式：从选中视频新建 / 恢复已有草稿
    var resumeDraft by remember { mutableStateOf(false) }

    // ★ 统一返回体系：所有非首页页面，系统返回/手势返回默认回首页。
    //   各页面内部状态（全屏播放、历史内嵌播放等）自己注册的 BackHandler
    //   组合顺序更靠后、优先级更高，会先于这里触发，不受影响；
    //   新增页面无需自己注册 BackHandler，忘记写也不会退 App。
    BackHandler(enabled = screen != Screen.Home) {
        if (screen is Screen.Merge) selectedUris = emptyList()
        screen = Screen.Home
    }

    when (val s = screen) {
        is Screen.Home -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            // ★ 可观察状态：删除草稿后立即刷新卡片；
            //   返回首页时本分支重建组合，remember 重新执行也会自动刷新
            var draftInfo by remember {
                mutableStateOf(com.moon.videomerger.editor.data.DraftStore.peek(context))
            }
            HomeScreen(
                onNewProject = { uris ->
                    selectedUris = uris
                    resumeDraft = false
                    editorSessionId++  // 新的编辑会话
                    screen = Screen.Editor
                },
                onOpenMerge = { uris ->
                    selectedUris = uris
                    screen = Screen.Merge
                },
                onOpenDouyin = {
                    screen = Screen.Douyin
                },
                onOpenGif = {
                    screen = Screen.Gif
                },
                onOpenHistory = {
                    screen = Screen.History
                },
                draftInfo = draftInfo,
                onResumeDraft = {
                    resumeDraft = true
                    editorSessionId++
                    screen = Screen.Editor
                },
                onDeleteDraft = {
                    com.moon.videomerger.editor.data.DraftStore.clear(context)
                    draftInfo = null
                }
            )
        }
        is Screen.History -> {
            HistoryScreen(
                onBack = { screen = Screen.Home }
            )
        }
        is Screen.Editor -> {
            // ★ 用 key 强制重建 EditorScreen，避免旧 UI 状态残留
            key(editorSessionId) {
                val viewModel: EditorViewModel = viewModel()
                // 新建：selectedUris 非空时创建项目；恢复：加载草稿（只执行一次）
                LaunchedEffect(editorSessionId) {
                    if (resumeDraft) {
                        viewModel.loadDraft()
                    } else if (selectedUris.isNotEmpty()) {
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
                initialUris = selectedUris,
                onBack = {
                    selectedUris = emptyList()
                    screen = Screen.Home
                }
            )
        }
        is Screen.Douyin -> {
            DouyinScreen(
                onBack = {
                    screen = Screen.Home
                }
            )
        }
        is Screen.Gif -> {
            GifScreen(
                onBack = {
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
    object Douyin : Screen()
    object Gif : Screen()
    object History : Screen()
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
