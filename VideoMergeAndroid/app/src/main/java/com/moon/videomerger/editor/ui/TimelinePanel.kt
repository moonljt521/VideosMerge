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
 */
@Composable
fun TimelinePanel(
    state: EditorUiState,
    onSelectClip: (String?) -> Unit,
    onSeek: (Double) -> Unit,
    onSplit: (String, Double) -> Unit
) {
    val totalDuration = state.project.totalDuration.coerceAtLeast(1.0)
    val horizontalPadding = 12.dp

    // ★ 用 BoxWithConstraints 获取实际可用宽度
    // pixelsPerSecond = (屏幕宽度 - 左右padding) / 总时长
    // 这样总时长始终铺满屏幕，不需要滚动
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(Color(0xFF1A1A1A))
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val paddingPx = with(density) { horizontalPadding.toPx() }
        val usableWidthPx = (screenWidthPx - paddingPx * 2).coerceAtLeast(1f)
        val pixelsPerSecond = (usableWidthPx / totalDuration.toFloat()).coerceAtLeast(1f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding)
        ) {
            // ── 时间标尺 ──
            TimelineRuler(
                totalDuration = totalDuration,
                pixelsPerSecond = pixelsPerSecond,
                usableWidthPx = usableWidthPx,
                currentPosition = state.currentPosition,
                onSeek = onSeek
            )

            // ── 轨道区域 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                            }
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
 * 时间标尺 —— 总时长铺满宽度，tap/drag 用百分比计算。
 */
@Composable
private fun TimelineRuler(
    totalDuration: Double,
    pixelsPerSecond: Float,
    usableWidthPx: Float,
    currentPosition: Double,
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
    onSelectClip: (String?) -> Unit
) {
    val trackWidthDp = with(LocalDensity.current) { (track.duration * pixelsPerSecond).toFloat().toDp() }

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

        // 片段区域（从 28dp 开始，避开图标）
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 28.dp)
        ) {
            track.clips.sortedBy { it.timelineStart }.forEach { clip ->
                ClipBlock(
                    clip = clip,
                    pixelsPerSecond = pixelsPerSecond,
                    isSelected = clip.id == selectedClipId,
                    onClick = { onSelectClip(if (clip.id == selectedClipId) null else clip.id) }
                )
            }
        }
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
