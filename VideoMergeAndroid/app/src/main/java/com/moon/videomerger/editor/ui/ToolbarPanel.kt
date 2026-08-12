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
    isExporting: Boolean,
    onToolClick: (ToolPanel) -> Unit
) {
    val tools = listOf(
        ToolItem("剪辑", Icons.Default.ContentCut, ToolPanel.TRIM, enabled = hasSelectedClip),
        ToolItem("变速", Icons.Default.Speed, ToolPanel.SPEED, enabled = hasSelectedClip),
        ToolItem("滤镜", Icons.Default.FilterVintage, ToolPanel.FILTER, enabled = hasSelectedClip),
        ToolItem("文字", Icons.Default.TextFields, ToolPanel.TEXT, enabled = hasSelectedClip),
        ToolItem("音频", Icons.Default.AudioFile, ToolPanel.AUDIO, enabled = hasSelectedClip),
        ToolItem("背景", Icons.Default.BlurOn, ToolPanel.BLUR_BG, enabled = hasSelectedClip),
        ToolItem("转场", Icons.Default.SwapHoriz, ToolPanel.TRANSITION, enabled = hasSelectedClip),
        ToolItem("导出", Icons.Default.Download, ToolPanel.EXPORT),
    )

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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(tools) { tool ->
                ToolButton(
                    name = tool.name,
                    icon = tool.icon,
                    enabled = tool.enabled && !isExporting,
                    onClick = { onToolClick(tool.panel) }
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
private fun ToolButton(name: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .size(width = 56.dp, height = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = name,
            tint = if (enabled) Color.White else Color(0xFF555555),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            color = if (enabled) Color.White else Color(0xFF555555),
            fontSize = 10.sp
        )
    }
}

// ═══════════════════════════════════════
//  工具面板路由
// ═══════════════════════════════════════

@Composable
fun ToolPanelHost(
    state: EditorUiState,
    onTrimChange: (Double, Double) -> Unit,
    onSplit: (String, Double) -> Unit,
    onSpeedChange: (Double) -> Unit,
    onFilterSelect: (FilterPreset) -> Unit,
    onColorChange: (Double, Double, Double) -> Unit,
    onVolumeChange: (Double) -> Unit,
    onTextChange: (String?) -> Unit,
    onTextStyleChange: (Int, String, String, Float, Boolean) -> Unit,
    onRotation: (Int) -> Unit,
    onHFlip: () -> Unit,
    onVFlip: () -> Unit,
    onToggleBlurBg: () -> Unit,
    onBlurStrengthChange: (Int) -> Unit,
    onTransitionChange: (TransitionEffect) -> Unit,
    onTransitionDurationChange: (Double) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    val clip = state.selectedClip
    if (clip == null && state.currentPanel != ToolPanel.EXPORT) {
        onClose()
        return
    }

    when (state.currentPanel) {
        ToolPanel.TRIM -> TrimPanel(
            clip = clip!!,
            onTrimChange = onTrimChange,
            onSplit = { pos -> onSplit(clip!!.id, pos) },
            onClose = onClose
        )
        ToolPanel.SPEED -> SpeedPanel(
            clip = clip!!,
            onSpeedChange = onSpeedChange,
            onClose = onClose
        )
        ToolPanel.FILTER -> FilterPanel(
            clip = clip!!,
            onFilterSelect = onFilterSelect,
            onColorChange = onColorChange,
            onClose = onClose
        )
        ToolPanel.TEXT -> TextPanel(
            clip = clip!!,
            onTextChange = onTextChange,
            onTextStyleChange = onTextStyleChange,
            onClose = onClose
        )
        ToolPanel.AUDIO -> AudioPanel(
            clip = clip!!,
            onVolumeChange = onVolumeChange,
            onClose = onClose
        )
        ToolPanel.BLUR_BG -> BlurBgPanel(
            clip = clip!!,
            onToggle = onToggleBlurBg,
            onStrengthChange = onBlurStrengthChange,
            onClose = onClose
        )
        ToolPanel.TRANSITION -> TransitionPanel(
            clip = clip!!,
            onTransitionChange = onTransitionChange,
            onDurationChange = onTransitionDurationChange,
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
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.width(60.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
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
