package com.moon.videomerger.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.videomerger.editor.data.EditorUiState
import com.moon.videomerger.editor.data.FilterPreset
import com.moon.videomerger.editor.data.PipShape
import com.moon.videomerger.editor.data.ToolPanel
import com.moon.videomerger.editor.data.TransitionEffect

/**
 * 底部工具栏 —— 一级工具入口（横向滚动）。
 *
 * 剪映风格：图标 + 文字标签，选中后切换到对应面板。
 */
@Composable
fun ToolbarPanel(
    hasSelectedClip: Boolean,
    canTransition: Boolean,
    isExporting: Boolean,
    onToolClick: (ToolPanel) -> Unit,
    onPipClick: () -> Unit
) {
    val tools = listOf(
        ToolItem("剪辑", Icons.Default.ContentCut, ToolPanel.TRIM, enabled = hasSelectedClip),
        ToolItem("变速", Icons.Default.Speed, ToolPanel.SPEED, enabled = hasSelectedClip),
        ToolItem("滤镜", Icons.Default.FilterVintage, ToolPanel.FILTER, enabled = hasSelectedClip),
        ToolItem("文字", Icons.Default.TextFields, ToolPanel.TEXT, enabled = hasSelectedClip),
        ToolItem("字幕", Icons.Default.Subtitles, ToolPanel.SUBTITLE, enabled = true),
        ToolItem("水印", Icons.Default.Image, ToolPanel.IMAGE_WATERMARK, enabled = hasSelectedClip),
        ToolItem("去水印", Icons.Default.AutoFixHigh, ToolPanel.WATERMARK_REMOVE, enabled = hasSelectedClip),
        ToolItem("画中画", Icons.Default.PictureInPictureAlt, ToolPanel.PICTURE, enabled = true),
        ToolItem("贴纸", Icons.Default.EmojiEmotions, ToolPanel.STICKER, enabled = true),
        ToolItem("音频", Icons.Default.AudioFile, ToolPanel.AUDIO, enabled = hasSelectedClip),
        ToolItem("背景", Icons.Default.BlurOn, ToolPanel.BLUR_BG, enabled = hasSelectedClip),
        ToolItem("画布", Icons.Default.AspectRatio, ToolPanel.CANVAS),
        ToolItem("导出", Icons.Default.Download, ToolPanel.EXPORT),
    ).let { base ->
        if (canTransition) {
            base.filterNot { it.panel == ToolPanel.EXPORT } +
                ToolItem("转场", Icons.Default.SwapHoriz, ToolPanel.TRANSITION, enabled = hasSelectedClip) +
                base.filter { it.panel == ToolPanel.EXPORT }
        } else {
            base
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(vertical = 8.dp)
    ) {
        if (!hasSelectedClip) {
            Text(
                "👆 点击时间轴上的视频片段来选中，然后使用下方工具",
                color = Color(0xFF888888),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            items(tools) { tool ->
                ToolButton(
                    name = tool.name,
                    icon = tool.icon,
                    // 未选中片段时仅外观置灰，仍可点击（点击后提示先选中片段）；
                    // 导出中才真正禁用
                    enabled = tool.enabled,
                    clickable = !isExporting,
                    onClick = {
                        if (tool.panel == ToolPanel.PICTURE) onPipClick() else onToolClick(tool.panel)
                    }
                )
            }
        }
    }
}

private data class ToolItem(
    val name: String,
    val icon: ImageVector,
    val panel: ToolPanel,
    val enabled: Boolean = true
)

@Composable
private fun ToolButton(
    name: String,
    icon: ImageVector,
    enabled: Boolean,
    clickable: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = name,
            tint = if (enabled) Color.White else Color(0xFF555555),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            name,
            color = if (enabled) Color.White else Color(0xFF555555),
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

// ═══════════════════════════════════════
//  工具面板路由
// ═══════════════════════════════════════

@Composable
fun ToolPanelHost(
    state: EditorUiState,
    playheadFlow: kotlinx.coroutines.flow.StateFlow<Double>,
    onTrimChange: (Double, Double) -> Unit,
    onDetectWatermarks: () -> Unit,
    onAddWatermarkRegion: (String?) -> Unit,
    onUpdateWatermarkRegion: (Int, com.moon.videomerger.editor.data.WatermarkRegion) -> Unit,
    onRemoveWatermarkRegion: (Int) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDeleteClip: () -> Unit,
    onSpeedChange: (Double) -> Unit,
    onReverseToggle: () -> Unit,
    onFilterSelect: (FilterPreset) -> Unit,
    onColorChange: (Double, Double, Double) -> Unit,
    onVolumeChange: (Double) -> Unit,
    onFadeChange: (Double, Double) -> Unit,
    onPitchChange: (Double) -> Unit,
    onNoiseReductionToggle: () -> Unit,
    onTextChange: (String?) -> Unit,
    onTextStyleChange: (Int, String, String, Float, Boolean) -> Unit,
    onImageWatermarkSelect: (String?) -> Unit,
    onImageWatermarkChange: (Double, Double, String) -> Unit,
    onRotation: (Int) -> Unit,
    onHFlip: () -> Unit,
    onVFlip: () -> Unit,
    onDetectLogo: () -> Unit,
    onDetectLogoAll: () -> Unit,
    isDetectingLogo: Boolean,
    onToggleBlurBg: () -> Unit,
    onBlurStrengthChange: (Int) -> Unit,
    onTransitionChange: (TransitionEffect) -> Unit,
    onTransitionDurationChange: (Double) -> Unit,
    onPipTransformChange: (Double, Double, Double, Double) -> Unit,
    onPipStyleChange: (PipShape, Double, Boolean, Double) -> Unit,
    onPipTimingChange: (Double, Double) -> Unit,
    onAddPipKeyframe: () -> Unit,
    onRemovePipKeyframe: (Int) -> Unit,
    onRemovePip: () -> Unit,
    onAddSubtitle: (String, Double, Double) -> Unit,
    onRemoveSubtitle: (String) -> Unit,
    onTranscribe: () -> Unit,
    onPickSticker: (String) -> Unit,
    onSetCanvas: (Int, Int) -> Unit,
    onEditStart: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    val clip = state.selectedClip
    // ★ 组合阶段不能直接调用 onClose（副作用），改用 LaunchedEffect
    //   EXPORT/SUBTITLE/STICKER/CANVAS 为项目级面板，无需选中片段
    LaunchedEffect(clip?.id, state.currentPanel) {
        val projectLevel = state.currentPanel == ToolPanel.EXPORT ||
            state.currentPanel == ToolPanel.SUBTITLE || state.currentPanel == ToolPanel.STICKER ||
            state.currentPanel == ToolPanel.CANVAS
        if (clip == null && !projectLevel && state.currentPanel != ToolPanel.NONE) {
            onClose()
        }
    }
    if (clip == null && state.currentPanel != ToolPanel.EXPORT &&
        state.currentPanel != ToolPanel.SUBTITLE && state.currentPanel != ToolPanel.STICKER &&
        state.currentPanel != ToolPanel.CANVAS
    ) {
        return
    }

    // ★ 面板内本地收集播放头（低频场景：分割按钮可用性、关键帧时间标签等）
    val playhead by playheadFlow.collectAsState()

    when (state.currentPanel) {
        ToolPanel.TRIM -> TrimPanel(
            clip = clip!!,
            currentPosition = playhead,
            onTrimChange = onTrimChange,
            onSplitAtPlayhead = onSplitAtPlayhead,
            onDelete = onDeleteClip,
            onRotation = onRotation,
            onHFlip = onHFlip,
            onVFlip = onVFlip,
            onDetectLogo = onDetectLogo,
            onDetectLogoAll = onDetectLogoAll,
            isDetectingLogo = isDetectingLogo,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.SPEED -> SpeedPanel(
            clip = clip!!,
            onSpeedChange = onSpeedChange,
            onReverseToggle = onReverseToggle,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.FILTER -> FilterPanel(
            clip = clip!!,
            onFilterSelect = onFilterSelect,
            onColorChange = onColorChange,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.TEXT -> TextPanel(
            clip = clip!!,
            onTextChange = onTextChange,
            onTextStyleChange = onTextStyleChange,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.IMAGE_WATERMARK -> ImageWatermarkPanel(
            clip = clip!!,
            onSelect = onImageWatermarkSelect,
            onChange = onImageWatermarkChange,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.AUDIO -> AudioPanel(
            clip = clip!!,
            onVolumeChange = onVolumeChange,
            onFadeChange = onFadeChange,
            onPitchChange = onPitchChange,
            onNoiseReductionToggle = onNoiseReductionToggle,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.BLUR_BG -> BlurBgPanel(
            clip = clip!!,
            onToggle = onToggleBlurBg,
            onStrengthChange = onBlurStrengthChange,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.TRANSITION -> TransitionPanel(
            clip = clip!!,
            onTransitionChange = onTransitionChange,
            onDurationChange = onTransitionDurationChange,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.WATERMARK_REMOVE -> WatermarkRemovePanel(
            clip = clip!!,
            isDetecting = state.isDetectingWatermark,
            onDetect = onDetectWatermarks,
            onAddRegion = { onAddWatermarkRegion(null) },
            onAddRegionAt = onAddWatermarkRegion,
            onUpdateRegion = onUpdateWatermarkRegion,
            onRemoveRegion = onRemoveWatermarkRegion,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.PICTURE -> PicturePanel(
            clip = clip!!,
            currentPosition = playhead,
            totalDuration = state.project.totalDuration,
            onTransformChange = onPipTransformChange,
            onStyleChange = onPipStyleChange,
            onTimingChange = onPipTimingChange,
            onSpeedChange = onSpeedChange,
            onReverseToggle = onReverseToggle,
            onRotation = onRotation,
            onHFlip = onHFlip,
            onVFlip = onVFlip,
            onAddKeyframe = onAddPipKeyframe,
            onRemoveKeyframe = onRemovePipKeyframe,
            onRemove = onRemovePip,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.SUBTITLE -> SubtitlePanel(
            state = state,
            currentPosition = playhead,
            onAdd = onAddSubtitle,
            onRemove = onRemoveSubtitle,
            onTranscribe = onTranscribe,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.STICKER -> StickerPanel(
            onPick = onPickSticker,
            onClose = onClose
        )
        ToolPanel.CANVAS -> CanvasPanel(
            project = state.project,
            onSetCanvas = onSetCanvas,
            onEditStart = onEditStart,
            onClose = onClose
        )
        ToolPanel.EXPORT -> ExportPanel(
            state = state,
            onExport = onExport,
            onClose = onClose
        )
        ToolPanel.NONE -> {}
    }
}

// ─── 面板通用头部 ───────────────────────────

@Composable
internal fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ─── 通用滑块 ───────────────────────────

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeStarted: (() -> Unit)? = null
) {
    // Material3 Slider 无 onValueChangeStarted，用标志位检测拖动开始：
    // 第一次收到 onValueChange 时视为手势开始（只推一次撤销栈）
    var dragging by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.width(60.dp))
        Slider(
            value = value,
            onValueChange = {
                if (!dragging) {
                    dragging = true
                    onValueChangeStarted?.invoke()
                }
                onValueChange(it)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF2196F3),
                activeTrackColor = Color(0xFF2196F3),
                inactiveTrackColor = Color(0xFF333333)
            )
        )
        Text(
            String.format("%.2f", value),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp)
        )
    }
}
