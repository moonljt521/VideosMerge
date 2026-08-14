package com.moon.videomerger.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.moon.videomerger.editor.data.EditorUiState
import com.moon.videomerger.editor.data.Clip

/**
 * 时间轴面板 —— 多轨片段可视化 + 播放头。
 *
 * 核心设计：整个项目总时长铺满屏幕宽度（左右各留 12dp 边距），
 * 不需要水平滚动。拖动/点击通过百分比计算播放头位置。
 *
 * 交互：
 * - 点击/拖动标尺 → 移动播放头
 * - 点击/拖动轨道空白处 → 移动播放头
 * - 点击片段块 → 选中片段
 * - 顶部快捷操作行：时间码 + 入点/出点 + 分割 + 删除（区间/片段）
 * - 片段边界上的圆形图标 → 已设置转场，点击可修改
 */
@Composable
fun TimelinePanel(
    state: EditorUiState,
    onSelectClip: (String?) -> Unit,
    onSeek: (Double) -> Unit,
    onSetInPoint: () -> Unit,
    onSetOutPoint: () -> Unit,
    onClearRange: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDeleteClip: () -> Unit,
    onOpenTransition: (String) -> Unit
) {
    val totalDuration = state.project.totalDuration.coerceAtLeast(1.0)
    val horizontalPadding = 12.dp

    // 播放头是否落在选中片段内部（决定分割按钮可用性）
    val selectedClip = state.selectedClip
    val canSplit = selectedClip != null &&
        state.currentPosition > selectedClip.timelineStart + 0.05 &&
        state.currentPosition < selectedClip.timelineEnd - 0.05

    // ★ 用 BoxWithConstraints 获取实际可用宽度
    // pixelsPerSecond = (屏幕宽度 - 左右padding) / 总时长
    // 这样总时长始终铺满屏幕，不需要滚动
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val paddingPx = with(density) { horizontalPadding.toPx() }
        val usableWidthPx = (screenWidthPx - paddingPx * 2).coerceAtLeast(1f)
        val pixelsPerSecond = (usableWidthPx / totalDuration.toFloat()).coerceAtLeast(1f)

        // ★ 不能用 fillMaxSize：外层已移除固定高度约束，
        // fillMaxSize 会吃掉 EditorScreen Column 的全部剩余高度，
        // 把预览区（weight）和底部工具栏挤压为 0。这里高度由内容撑开。
        Column(
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
        ) {
            // ── 快捷操作行：时间码 + 入出点 + 分割/删除 ──
            TimelineActionBar(
                currentPosition = state.currentPosition,
                totalDuration = state.project.totalDuration,
                hasSelectedClip = selectedClip != null,
                canSplit = canSplit,
                inPoint = state.inPoint,
                outPoint = state.outPoint,
                onSetInPoint = onSetInPoint,
                onSetOutPoint = onSetOutPoint,
                onClearRange = onClearRange,
                onSplitAtPlayhead = onSplitAtPlayhead,
                onDeleteClip = onDeleteClip
            )

            // ── 时间标尺（显示入出点区间高亮）──
            TimelineRuler(
                totalDuration = totalDuration,
                pixelsPerSecond = pixelsPerSecond,
                usableWidthPx = usableWidthPx,
                currentPosition = state.currentPosition,
                inPoint = state.inPoint,
                outPoint = state.outPoint,
                onSeek = onSeek
            )

            // ── 轨道区域（多轨时按轨道数撑高）──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((state.project.tracks.size * 48 + 8).dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp)
                ) {
                    state.project.tracks.forEach { track ->
                        TrackRow(
                            track = track,
                            selectedClipId = state.selectedClipId,
                            pixelsPerSecond = pixelsPerSecond,
                            totalDuration = totalDuration,
                            onSeek = onSeek,
                            onSelectClip = { clipId ->
                                onSelectClip(clipId)
                            },
                            onOpenTransition = onOpenTransition
                        )
                    }
                }

                // ── 播放头竖线 ──
                Playhead(
                    position = state.currentPosition,
                    pixelsPerSecond = pixelsPerSecond,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

/**
 * 时间轴快捷操作行 —— 左侧时间码，右侧入出点/分割/删除（剪映式）。
 * 入点出点都设好后，删除按钮变为“删区间”，点击区间文字可清除入出点。
 */
@Composable
private fun TimelineActionBar(
    currentPosition: Double,
    totalDuration: Double,
    hasSelectedClip: Boolean,
    canSplit: Boolean,
    inPoint: Double?,
    outPoint: Double?,
    onSetInPoint: () -> Unit,
    onSetOutPoint: () -> Unit,
    onClearRange: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDeleteClip: () -> Unit
) {
    val hasRange = inPoint != null && outPoint != null && outPoint > inPoint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${formatTimecode(currentPosition)} / ${formatTimecode(totalDuration)}",
            color = Color(0xFF2196F3),
            fontSize = 11.sp
        )
        // 区间已设：显示区间范围，点击清除
        if (hasRange) {
            Text(
                text = " ✂ ${formatTimecode(inPoint!!)}-${formatTimecode(outPoint!!)}",
                color = Color(0xFFFF7043),
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(onClick = onClearRange)
                    .padding(horizontal = 4.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        ActionBarButton(
            icon = Icons.Default.Flag,
            label = "入",
            enabled = true,
            active = inPoint != null,
            onClick = onSetInPoint
        )
        Spacer(Modifier.width(8.dp))
        ActionBarButton(
            icon = Icons.Default.Flag,
            label = "出",
            enabled = true,
            active = outPoint != null,
            onClick = onSetOutPoint
        )
        Spacer(Modifier.width(8.dp))
        ActionBarButton(
            icon = Icons.Default.ContentCut,
            label = "分割",
            enabled = canSplit,
            onClick = onSplitAtPlayhead
        )
        Spacer(Modifier.width(8.dp))
        ActionBarButton(
            icon = Icons.Default.Delete,
            label = if (hasRange) "删区间" else "删除",
            enabled = hasRange || hasSelectedClip,
            onClick = onDeleteClip
        )
    }
}

@Composable
private fun ActionBarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val tint = when {
        !enabled -> Color(0xFF555555)
        active -> Color(0xFFFF7043)
        else -> Color.White
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            label,
            color = tint,
            fontSize = 11.sp
        )
    }
}

/**
 * 时间标尺 —— 总时长铺满宽度，tap/drag 用百分比计算。
 */
@Composable
private fun TimelineRuler(
    totalDuration: Double,
    pixelsPerSecond: Float,
    usableWidthPx: Float,
    currentPosition: Double,
    inPoint: Double?,
    outPoint: Double?,
    onSeek: (Double) -> Unit
) {
    val density = LocalDensity.current
    val rulerWidthDp = with(density) { usableWidthPx.toDp() }

    Box(
        modifier = Modifier
            .width(rulerWidthDp)
            .height(32.dp)
            .background(Color(0xFF222222))
            // drag：拖动播放头
            .pointerInput(totalDuration, pixelsPerSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val time = (offset.x / pixelsPerSecond).toDouble()
                            .coerceIn(0.0, totalDuration)
                        onSeek(time)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val time = (change.position.x / pixelsPerSecond).toDouble()
                            .coerceIn(0.0, totalDuration)
                        onSeek(time)
                    }
                )
            }
            // tap：点击定位
            .pointerInput(totalDuration, pixelsPerSecond) {
                detectTapGestures(
                    onTap = { offset ->
                        val time = (offset.x / pixelsPerSecond).toDouble()
                            .coerceIn(0.0, totalDuration)
                        onSeek(time)
                    }
                )
            }
    ) {
        // 刻度
        val interval = when {
            totalDuration <= 10 -> 1.0    // 10s 以内：每秒一刻
            totalDuration <= 30 -> 2.0    // 30s 以内：每2s一刻
            totalDuration <= 60 -> 5.0    // 60s 以内：每5s一刻
            totalDuration <= 300 -> 10.0  // 5min 以内：每10s一刻
            else -> 30.0                   // 更长：每30s一刻
        }
        val numMarks = (totalDuration / interval).toInt() + 1
        repeat(numMarks) { i ->
            val time = i * interval
            if (time > totalDuration) return@repeat
            val x = with(density) { (time * pixelsPerSecond).toFloat().toDp() }
            Text(
                text = formatTimecode(time),
                color = Color(0xFF888888),
                fontSize = 9.sp,
                modifier = Modifier.offset(x = x)
            )
        }

        // 入出点区间高亮（待删除区间）
        if (inPoint != null && outPoint != null && outPoint > inPoint) {
            val startX = with(density) { (inPoint * pixelsPerSecond).toFloat().toDp() }
            val rangeW = with(density) { ((outPoint - inPoint) * pixelsPerSecond).toFloat().toDp() }
            Box(
                modifier = Modifier
                    .offset(x = startX)
                    .width(rangeW)
                    .fillMaxHeight()
                    .background(Color(0x55FF7043))
            )
        }

        // 当前位置标记（蓝色竖线）
        val playheadX = with(density) { (currentPosition * pixelsPerSecond).toFloat().toDp() }
        Box(
            modifier = Modifier
                .offset(x = playheadX)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color(0xFF2196F3))
        )
    }
}

/**
 * 轨道行 —— 宽度 = 总时长 * pixelsPerSecond（铺满屏幕）。
 * 空白处可拖动播放头，片段 clickable 优先选中。
 */
@Composable
private fun TrackRow(
    track: com.moon.videomerger.editor.data.Track,
    selectedClipId: String?,
    pixelsPerSecond: Float,
    totalDuration: Double,
    onSeek: (Double) -> Unit,
    onSelectClip: (String?) -> Unit,
    onOpenTransition: (String) -> Unit
) {
    val density = LocalDensity.current
    val trackWidthDp = with(density) { (track.duration * pixelsPerSecond).toFloat().toDp() }

    Box(
        modifier = Modifier
            .width(trackWidthDp)
            .height(48.dp)
            .padding(vertical = 2.dp)
            // ★ 轨道空白处拖动播放头
            .pointerInput(totalDuration, pixelsPerSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val time = (offset.x / pixelsPerSecond).toDouble()
                            .coerceIn(0.0, totalDuration)
                        onSeek(time)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val time = (change.position.x / pixelsPerSecond).toDouble()
                            .coerceIn(0.0, totalDuration)
                        onSeek(time)
                    }
                )
            }
            .pointerInput(totalDuration, pixelsPerSecond) {
                detectTapGestures(
                    onTap = { offset ->
                        val time = (offset.x / pixelsPerSecond).toDouble()
                            .coerceIn(0.0, totalDuration)
                        onSeek(time)
                    }
                )
            }
    ) {
        // 轨道类型图标
        Box(
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight()
                .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when (track.type) {
                    com.moon.videomerger.editor.data.TrackType.MAIN -> Icons.Default.VideoFile
                    com.moon.videomerger.editor.data.TrackType.PICTURE -> Icons.Default.Image
                    com.moon.videomerger.editor.data.TrackType.TEXT -> Icons.Default.TextFields
                    com.moon.videomerger.editor.data.TrackType.AUDIO -> Icons.Default.AudioFile
                },
                contentDescription = null,
                tint = Color(0xFFAAAAAA),
                modifier = Modifier.size(14.dp)
            )
        }

        // 片段区域（从 28dp 开始，避开图标；★ 不再额外加 padding，
        // ClipBlock 内部的 +28dp offset 已含偏移，重复会导致片段与播放头错位）
        Box(
            modifier = Modifier.fillMaxHeight()
        ) {
            track.clips.sortedBy { it.timelineStart }.forEach { clip ->
                ClipBlock(
                    clip = clip,
                    pixelsPerSecond = pixelsPerSecond,
                    isSelected = clip.id == selectedClipId,
                    onClick = { onSelectClip(if (clip.id == selectedClipId) null else clip.id) }
                )
            }

            // 转场指示器：片段边界上的圆形图标（剪映风格），点击打开转场面板
            track.clips.sortedBy { it.timelineStart }.forEach { clip ->
                if (clip.transition != com.moon.videomerger.editor.data.TransitionEffect.NONE) {
                    TransitionBadge(
                        clip = clip,
                        pixelsPerSecond = pixelsPerSecond,
                        onClick = { onOpenTransition(clip.id) }
                    )
                }
            }
        }
    }
}

/**
 * 转场指示器 —— 位于片段结尾边界上的小圆形，点击可修改转场。
 */
@Composable
private fun TransitionBadge(
    clip: Clip,
    pixelsPerSecond: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val size = 16.dp
    val centerX = with(density) { (clip.timelineEnd * pixelsPerSecond).toFloat().toDp() } + 28.dp

    // ★ 不能用 align（非 BoxScope 内），用 offset 垂直居中：
    // 轨道行 48dp - 上下 padding 4dp = 44dp，(44 - 16) / 2 = 14dp
    Box(
        modifier = Modifier
            .offset(x = centerX - size / 2, y = 14.dp)
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.SwapHoriz,
            contentDescription = "转场",
            tint = Color(0xFF1A1A1A),
            modifier = Modifier.size(11.dp)
        )
    }
}

/**
 * 片段块 —— 点击选中。
 */
@Composable
private fun ClipBlock(
    clip: Clip,
    pixelsPerSecond: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val width = with(density) { (clip.timelineDuration * pixelsPerSecond).toFloat().toDp() }
        .coerceAtLeast(20.dp)
    val offsetX = with(density) { (clip.timelineStart * pixelsPerSecond).toFloat().toDp() }

    Box(
        modifier = Modifier
            .offset(x = offsetX + 28.dp)
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color(0xFF2196F3) else Color(0xFF3D3D3D))
            .border(
                if (isSelected) 2.dp else 0.dp,
                Color.White,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
    ) {
        clip.thumbnailPath?.let { path ->
            AsyncImage(
                model = path,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Text(
            text = clip.mediaName.take(8),
            color = Color.White,
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(2.dp)
                .background(Color(0x66000000), RoundedCornerShape(2.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )

        if (clip.speed != 1.0) {
            Text(
                text = "${clip.speed}x",
                color = Color.Yellow,
                fontSize = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            )
        }

        if (isSelected) {
            Text(
                text = "✓",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
            )
        }
    }
}

/**
 * 播放头竖线
 */
@Composable
private fun Playhead(
    position: Double,
    pixelsPerSecond: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val posDp = with(density) { (position * pixelsPerSecond).toFloat().toDp() }

    Box(
        modifier = modifier
            .offset(x = posDp)
            .width(2.dp)
            .fillMaxHeight()
            .background(Color.White)
    )
}

/** 格式化时间码 */
private fun formatTimecode(seconds: Double): String {
    val totalSec = seconds.toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}'${s}\"" else "${s}\""
}
