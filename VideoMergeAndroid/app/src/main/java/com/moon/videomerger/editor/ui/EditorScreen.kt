package com.moon.videomerger.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.editor.data.ToolPanel

/**
 * 编辑器主界面 —— 剪映风格三段式布局：
 *   顶部栏 + 预览区 + 时间轴 + 工具栏/面板
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel = viewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler { onBack() }

    // 添加更多视频的选择器
    val addVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addClips(uris)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── 顶部栏 ──
        EditorTopBar(
            projectName = state.project.name,
            clipCount = state.project.mainTrack?.clips?.size ?: 0,
            isExporting = state.isExporting,
            onBack = onBack,
            onAddVideo = { addVideoLauncher.launch("video/*") },
            onExport = { viewModel.export() }
        )

        // ── 预览区（约 45% 屏高）──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            PreviewPanel(
                state = state,
                onTogglePlay = { viewModel.togglePlay() },
                onSeek = { viewModel.seekTo(it) }
            )
        }

        // ── 时间轴（约 25% 屏高）──
        TimelinePanel(
            state = state,
            onSelectClip = { viewModel.selectClip(it) },
            onSeek = { viewModel.seekTo(it) },
            onSplit = { clipId, pos -> viewModel.splitClip(clipId, pos) }
        )

        // ── 工具栏 / 面板（约 30% 屏高）──
        if (state.currentPanel == ToolPanel.NONE) {
            ToolbarPanel(
                hasSelectedClip = state.selectedClip != null,
                isExporting = state.isExporting,
                onToolClick = { panel -> viewModel.showPanel(panel) }
            )
        } else {
            ToolPanelHost(
                state = state,
                onTrimChange = { s, e -> viewModel.updateTrim(state.selectedClipId!!, s, e) },
                onSplit = { clipId, pos -> viewModel.splitClip(clipId, pos) },
                onSpeedChange = { v -> viewModel.updateSpeed(state.selectedClipId!!, v) },
                onFilterSelect = { p -> viewModel.setFilterPreset(state.selectedClipId!!, p) },
                onColorChange = { b, c, s -> viewModel.updateColorParams(state.selectedClipId!!, b, c, s) },
                onVolumeChange = { v -> viewModel.updateVolume(state.selectedClipId!!, v) },
                onTextChange = { t -> viewModel.setTextOverlay(state.selectedClipId!!, t) },
                onTextStyleChange = { sz, c, p, op, bd -> viewModel.setTextStyle(state.selectedClipId!!, sz, c, p, op, bd) },
                onRotation = { r -> viewModel.setRotation(state.selectedClipId!!, r) },
                onHFlip = { viewModel.toggleHFlip(state.selectedClipId!!) },
                onVFlip = { viewModel.toggleVFlip(state.selectedClipId!!) },
                onToggleBlurBg = { viewModel.toggleBlurBg(state.selectedClipId!!) },
                onBlurStrengthChange = { s -> viewModel.updateBlurStrength(state.selectedClipId!!, s) },
                onTransitionChange = { e -> viewModel.setTransition(state.selectedClipId!!, e) },
                onTransitionDurationChange = { d -> viewModel.updateTransitionDuration(state.selectedClipId!!, d) },
                onExport = { viewModel.export() },
                onClose = { viewModel.closePanel() }
            )
        }

        // ── 导出进度 ──
        if (state.isExporting) {
            ExportProgressBar(
                progress = state.exportProgress,
                message = state.exportMessage
            )
        }

        // ── 错误提示 ──
        state.errorMessage?.let { msg ->
            ErrorBar(message = msg, onDismiss = { viewModel.dismissError() })
        }
    }
}

// ─── 顶部栏 ───────────────────────────

@Composable
private fun EditorTopBar(
    projectName: String,
    clipCount: Int,
    isExporting: Boolean,
    onBack: () -> Unit,
    onAddVideo: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = projectName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (clipCount > 0) {
                Text(
                    text = "$clipCount 个片段",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
        }
        // 添加视频按钮
        IconButton(onClick = onAddVideo, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.AddCircle, contentDescription = "添加视频", tint = Color.White)
        }
        if (isExporting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            TextButton(onClick = onExport) {
                Text("导出", color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── 导出进度条 ───────────────────────────

@Composable
private fun ExportProgressBar(progress: Float, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(message, color = Color.White, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color(0xFF2196F3),
            trackColor = Color(0xFF333333)
        )
    }
}

// ─── 错误提示 ───────────────────────────

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Surface(
        color = Color(0xFFD32F2F),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("确定", color = Color.White)
            }
        }
    }
}
