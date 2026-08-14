package com.moon.videomerger.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.editor.data.ToolPanel
import kotlinx.coroutines.delay

/**
 * 编辑器主界面 —— 剪映风格三段式布局：
 *   顶部栏 + 预览区 + 时间轴 + 工具栏/面板
 *   导出进度与错误提示用浮层展示，不挤压布局。
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel = viewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    android.util.Log.w("EditorScreen", "compose state=${state.project.mainTrack?.clips?.size} panel=${state.currentPanel}")
    BackHandler { onBack() }

    // 添加更多视频的选择器
    val addVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addClips(uris)
        }
    }

    // 画中画选择器（视频或图片）
    val pipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPipOverlay(uris)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶部栏 ──
            EditorTopBar(
                projectName = state.project.name,
                clipCount = state.project.mainTrack?.clips?.size ?: 0,
                isExporting = state.isExporting,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onBack = onBack,
                onAddVideo = { addVideoLauncher.launch("video/*") },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onExport = { viewModel.export() }
            )

            // ── 预览 + 时间轴 + 工具栏：可拖拽联动调整预览高度 ──
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val density = LocalDensity.current
                val handleHeight = 12.dp
                val minLowerHeight = 220.dp
                val minPreview = 120.dp
                val maxPreview = (maxHeight - handleHeight - minLowerHeight).coerceAtLeast(minPreview)
                var previewHeightDp by remember { mutableStateOf(0.dp) }

                LaunchedEffect(maxHeight) {
                    if (previewHeightDp == 0.dp) {
                        previewHeightDp = maxHeight * 0.45f
                    }
                }
                val clampedPreview = previewHeightDp.coerceIn(minPreview, maxPreview)

                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(clampedPreview)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        PreviewPanel(
                            state = state,
                            onTogglePlay = { viewModel.togglePlay() },
                            onSeek = { viewModel.seekTo(it) }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(handleHeight)
                            .background(Color(0xFF111111))
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val delta = with(density) { dragAmount.toDp() }
                                    previewHeightDp = (previewHeightDp + delta)
                                        .coerceIn(minPreview, maxPreview)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF555555))
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        TimelinePanel(
                            state = state,
                            onSelectClip = { viewModel.selectClip(it) },
                            onSeek = { viewModel.seekTo(it) },
                            onSetInPoint = { viewModel.setInPoint() },
                            onSetOutPoint = { viewModel.setOutPoint() },
                            onClearRange = { viewModel.clearRange() },
                            onSplitAtPlayhead = { viewModel.splitAtPlayhead() },
                            onDeleteClip = {
                                if (state.inPoint != null && state.outPoint != null && state.outPoint!! > state.inPoint!!) {
                                    viewModel.deleteRange()
                                } else {
                                    state.selectedClipId?.let { viewModel.deleteClip(it) }
                                }
                            },
                            onOpenTransition = { clipId ->
                                viewModel.selectClip(clipId)
                                viewModel.showPanel(ToolPanel.TRANSITION)
                            }
                        )

                        if (state.currentPanel == ToolPanel.NONE) {
                            ToolbarPanel(
                                hasSelectedClip = state.selectedClip != null,
                                canTransition = state.selectedClipCanTransition,
                                isExporting = state.isExporting,
                                onToolClick = { panel ->
                                    if (state.selectedClip == null && panel != ToolPanel.EXPORT && panel != ToolPanel.SUBTITLE) {
                                        viewModel.showError("请先点击时间轴上的视频片段选中后再使用该工具")
                                    } else {
                                        viewModel.showPanel(panel)
                                    }
                                },
                                onPipClick = {
                                    // 已选中画中画片段 → 打开面板调整；否则 → 选择视频/图片新增画中画
                                    if (state.selectedClip?.pipEnabled == true) {
                                        viewModel.showPanel(ToolPanel.PICTURE)
                                    } else {
                                        pipLauncher.launch(arrayOf("video/*", "image/*"))
                                    }
                                }
                            )
                        } else {
                            ToolPanelHost(
                                state = state,
                                onTrimChange = { s, e -> viewModel.updateTrim(state.selectedClipId!!, s, e) },
                                onSplitAtPlayhead = { viewModel.splitAtPlayhead() },
                                onDeleteClip = { state.selectedClipId?.let { viewModel.deleteClip(it) } },
                                onSpeedChange = { v -> viewModel.updateSpeed(state.selectedClipId!!, v) },
                                onReverseToggle = { viewModel.toggleReverse(state.selectedClipId!!) },
                                onFilterSelect = { p -> viewModel.setFilterPreset(state.selectedClipId!!, p) },
                                onColorChange = { b, c, s -> viewModel.updateColorParams(state.selectedClipId!!, b, c, s) },
                                onVolumeChange = { v -> viewModel.updateVolume(state.selectedClipId!!, v) },
                                onFadeChange = { fi, fo -> viewModel.updateAudioFade(state.selectedClipId!!, fi, fo) },
                                onPitchChange = { p -> viewModel.updatePitchShift(state.selectedClipId!!, p) },
                                onNoiseReductionToggle = { viewModel.toggleNoiseReduction(state.selectedClipId!!) },
                        onTextChange = { t -> viewModel.setTextOverlay(state.selectedClipId!!, t) },
                        onTextStyleChange = { sz, c, p, op, bd -> viewModel.setTextStyle(state.selectedClipId!!, sz, c, p, op, bd) },
                        onImageWatermarkSelect = { path -> viewModel.setImageWatermark(state.selectedClipId!!, path) },
                        onImageWatermarkChange = { s, o, p -> viewModel.updateImageWatermark(state.selectedClipId!!, s, o, p) },
                        onRotation = { r -> viewModel.setRotation(state.selectedClipId!!, r) },
                                onHFlip = { viewModel.toggleHFlip(state.selectedClipId!!) },
                                onVFlip = { viewModel.toggleVFlip(state.selectedClipId!!) },
                                onDetectLogo = { viewModel.detectTailLogo(state.selectedClipId!!) },
                                onDetectLogoAll = { viewModel.detectTailLogoAll() },
                                isDetectingLogo = state.isDetectingLogo,
                                onToggleBlurBg = { viewModel.toggleBlurBg(state.selectedClipId!!) },
                                onBlurStrengthChange = { s -> viewModel.updateBlurStrength(state.selectedClipId!!, s) },
                                onTransitionChange = { e -> viewModel.setTransition(state.selectedClipId!!, e) },
                                onTransitionDurationChange = { d -> viewModel.updateTransitionDuration(state.selectedClipId!!, d) },
                                onPipTransformChange = { x, y, w, o -> viewModel.updatePipTransform(state.selectedClipId!!, x, y, w, o) },
                                onPipStyleChange = { s, r, b, bw -> viewModel.updatePipStyle(state.selectedClipId!!, s, r, b, bw) },
                                onAddPipKeyframe = { viewModel.addPipKeyframe(state.selectedClipId!!) },
                                onRemovePipKeyframe = { i -> viewModel.removePipKeyframe(state.selectedClipId!!, i) },
                                onRemovePip = { state.selectedClipId?.let { viewModel.deleteClip(it) } },
                                onAddSubtitle = { t, s, e -> viewModel.addSubtitle(t, s, e) },
                                onRemoveSubtitle = { id -> viewModel.removeSubtitle(id) },
                                onTranscribe = { viewModel.transcribeSpeech() },
                                onEditStart = { viewModel.beginEdit() },
                                onExport = { viewModel.export() },
                                onClose = { viewModel.closePanel() }
                            )
                        }
                    }
                }
            }
        }

        // ── 导出进度浮层（不挤压布局，可取消）──
        if (state.isExporting) {
            ExportOverlay(
                progress = state.exportProgress,
                message = state.exportMessage,
                onCancel = { viewModel.cancelExport() }
            )
        }

        // ── 错误/提示浮层（自动消失）──
        state.errorMessage?.let { msg ->
            FloatingMessage(message = msg, onDismiss = { viewModel.dismissError() })
        }
    }
}

// ─── 顶部栏 ───────────────────────────

@Composable
private fun EditorTopBar(
    projectName: String,
    clipCount: Int,
    isExporting: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onAddVideo: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
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
        // 撤销 / 重做
        IconButton(onClick = onUndo, enabled = canUndo && !isExporting, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Undo,
                contentDescription = "撤销",
                tint = if (canUndo && !isExporting) Color.White else Color(0xFF444444)
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo && !isExporting, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Redo,
                contentDescription = "重做",
                tint = if (canRedo && !isExporting) Color.White else Color(0xFF444444)
            )
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

// ─── 导出进度浮层 ───────────────────────────

@Composable
private fun ExportOverlay(progress: Float, message: String, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("正在导出视频", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF2196F3),
                    trackColor = Color(0xFF333333)
                )
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFFAAAAAA), fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("取消导出", color = Color(0xFFFF7043))
                }
            }
        }
    }
}

// ─── 错误/提示浮层（自动消失）───────────────────

@Composable
private fun FloatingMessage(message: String, onDismiss: () -> Unit) {
    // 3.5 秒后自动关闭
    LaunchedEffect(message) {
        delay(3500)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = if (message.startsWith("导出失败") || message.startsWith("保存失败")) {
                Color(0xFFD32F2F)
            } else {
                Color(0xFF333333)
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 64.dp)
                .clickable { onDismiss() }
        ) {
            Text(
                message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
