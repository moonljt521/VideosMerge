package com.moon.videomerger.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 时间轴面板（剪映式）—— ★ 播放头固定在屏幕中央，胶片横向滚动。
 *
 * 核心设计：
 * - 内容宽度 = 总时长 × 固定缩放(pps) + 左右各半个屏幕的留白，
 *   使 t=0 与 t=结尾 都能精确居中到播放头下。
 * - 播放中：胶片自动滚动，播放头位置始终对准中央竖线。
 * - 拖动：原生横向滚动手势（代替旧的拖播放头）。
 * - 点击空白/标尺：seek 到该时间点并动画居中。
 * - 点击片段块：选中片段。
 * - 片段边界上的圆形图标 → 已设置转场，点击可修改。
 *
 * 时间↔坐标换算：
 *   contentX(t) = halfViewport + t × pps     （t 在胶片内容中的 x）
 *   scroll(t)   = t × pps                    （把 t 滚到中央所需偏移）
 */
@Composable
fun TimelinePanel(
    state: EditorUiState,
    playheadFlow: StateFlow<Double>,
    onSelectClip: (String?) -> Unit,
    onSeek: (Double) -> Unit,
    onSetInPoint: () -> Unit,
    onSetOutPoint: () -> Unit,
    onClearRange: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDeleteClip: () -> Unit,
    onOpenTransition: (String) -> Unit
) {
    // ★ 本地收集播放头：只有时间轴区域跟随播放重组
    val currentPosition by playheadFlow.collectAsState()
    val totalDuration = state.project.totalDuration.coerceAtLeast(1.0)
    val horizontalPadding = 12.dp

    // 播放头是否落在选中片段内部（决定分割按钮可用性）
    val selectedClip = state.selectedClip
    val canSplit = selectedClip != null &&
        currentPosition > selectedClip.timelineStart + 0.05 &&
        currentPosition < selectedClip.timelineEnd - 0.05

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()

        // ★ 固定缩放：每秒 60dp（≈10 秒可见一屏，后续可加捏合缩放）
        val pixelsPerSecond = with(density) { 60.dp.toPx() }
        val halfViewportPx = with(density) { maxWidth.toPx() } / 2f

        val filmWidthPx = totalDuration.toFloat() * pixelsPerSecond
        val contentWidthPx = filmWidthPx + halfViewportPx * 2f
        val contentWidthDp = with(density) { contentWidthPx.toDp() }
        val edgePadDp = with(density) { halfViewportPx.toDp() }
        val filmWidthDp = with(density) { filmWidthPx.toDp() }

        val scrollState = rememberScrollState()

        // ── 播放中自动跟滚：让当前位置始终对准中央播放头 ──
        LaunchedEffect(currentPosition, state.isPlaying) {
            if (state.isPlaying && !scrollState.isScrollInProgress) {
                val target = (currentPosition.toFloat() * pixelsPerSecond).toInt()
                scrollState.scrollTo(target)
            }
        }

        // seek 后主动把目标点居中（点击定位时调用）
        fun seekAndCenter(time: Double) {
            onSeek(time.coerceIn(0.0, totalDuration))
            scope.launch {
                scrollState.animateScrollTo((time.toFloat() * pixelsPerSecond).toInt())
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding)
        ) {
            // ── 快捷操作行：时间码 + 入出点 + 分割/删除 ──
            TimelineActionBar(
                currentPosition = currentPosition,
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

            // ── 可滚动胶片区（标尺 + 轨道），高度由轨道数撑开 ──
            val trackAreaHeight = (state.project.tracks.size * 48 + 8).dp + 32.dp // 32dp = 标尺高
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackAreaHeight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(scrollState)
                ) {
                    Column(
                        modifier = Modifier
                            .width(contentWidthDp)
                            .padding(horizontal = edgePadDp)
                    ) {
                        // 时间标尺（点击定位）
                        TimelineRuler(
                            totalDuration = totalDuration,
                            pixelsPerSecond = pixelsPerSecond,
                            widthDp = filmWidthDp,
                            currentPosition = currentPosition,
                            inPoint = state.inPoint,
                            outPoint = state.outPoint,
                            onSeek = ::seekAndCenter
                        )

                        // 多轨区域
                        Box(
                            modifier = Modifier.height((state.project.tracks.size * 48 + 8).dp)
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
                                        onSeek = ::seekAndCenter,
                                        onSelectClip = onSelectClip,
                                        onOpenTransition = onOpenTransition
                                    )
                                }
                            }
                        }
                    }
                }

                // ── ★ 固定播放头：钉死屏幕中央的白线 + 顶部手柄 ──
                CenterPlayhead(modifier = Modifier.fillMaxHeight())
            }
        }
    }
}

/**
 * 时间轴快捷操作行 —— 左侧时间码，右侧入出点/分割/删除（剪映式）。
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
 * 时间标尺 —— 宽度 = 总时长×pps（在滚动容器内），tap 定位+居中。
 * 刻度间隔按"实际像素间距不小于 ~64dp"自适应，保证固定缩放下标签不打架。
 */
@Composable
private fun TimelineRuler(
    totalDuration: Double,
    pixelsPerSecond: Float,
    widthDp: androidx.compose.ui.unit.Dp,
    currentPosition: Double,
    inPoint: Double?,
    outPoint: Double?,
    onSeek: (Double) -> Unit
) {
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .width(widthDp)
            .height(32.dp)
            .background(Color(0xFF222222))
            .pointerInput(pixelsPerSecond) {
                detectTapGestures(
                    onTap = { offset ->
                        val time = (offset.x / pixelsPerSecond).toDouble()
                        onSeek(time)
                    }
                )
            }
    ) {
        // 刻度：选最小的、且像素间距 ≥64dp 的档位
        val minSpacingPx = with(density) { 64.dp.toPx() }
        val candidates = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0)
        val interval = candidates.firstOrNull { it * pixelsPerSecond >= minSpacingPx } ?: 300.0
        var time = 0.0
        while (time <= totalDuration) {
            val x = with(density) { (time * pixelsPerSecond).toFloat().toDp() }
            Text(
                text = formatTimecode(time),
                color = Color(0xFF888888),
                fontSize = 9.sp,
                modifier = Modifier.offset(x = x)
            )
            time += interval
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

        // 播放头经过标尺处的小圆点提示（真线在滚动区外层固定绘制）
        if (currentPosition in 0.0..totalDuration) {
            val px = with(density) { (currentPosition * pixelsPerSecond).toFloat().toDp() }
            Box(
                modifier = Modifier
                    .offset(x = px - 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2196F3))
            )
        }
    }
}

/**
 * 轨道行 —— 宽度 = 总时长×pps。空白处点击 seek；横向拖动交由外层滚动容器处理。
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
    val trackWidthDp = with(density) { (totalDuration * pixelsPerSecond).toFloat().toDp() }

    Box(
        modifier = Modifier
            .width(trackWidthDp)
            .height(48.dp)
            .padding(vertical = 2.dp)
            .pointerInput(pixelsPerSecond) {
                detectTapGestures(
                    onTap = { offset ->
                        val time = (offset.x / pixelsPerSecond).toDouble()
                        onSeek(time)
                    }
                )
            }
    ) {
        // 片段区域
        val sortedClips = track.clips.sortedBy { it.timelineStart }
        Box(
            modifier = Modifier.fillMaxHeight()
        ) {
            // ★ 主轨显示坐标模型（剪映式）：块首尾相接、接缝 = 前一片段真正结束点，
            //   重叠区（转场进行中）归属前一块的尾部显示。
            data class DisplayRect(val clip: com.moon.videomerger.editor.data.Clip, val start: Double, val width: Double)
            val displayRects: List<DisplayRect> = if (track.type == com.moon.videomerger.editor.data.TrackType.MAIN) {
                var x = 0.0
                var prevEnd = 0.0
                sortedClips.map { c ->
                    val w = c.timelineEnd - prevEnd
                    val r = DisplayRect(c, x, w)
                    x += w
                    prevEnd = c.timelineEnd
                    r
                }
            } else {
                sortedClips.map { DisplayRect(it, it.timelineStart, it.timelineDuration) }
            }

            // ★ 选中片段最后绘制，保证选中的块不被相邻块盖住
            displayRects.sortedBy { it.clip.id == selectedClipId }.forEach { r ->
                ClipBlock(
                    clip = r.clip,
                    displayStartSeconds = r.start,
                    displayWidthSeconds = r.width,
                    pixelsPerSecond = pixelsPerSecond,
                    isSelected = r.clip.id == selectedClipId,
                    onClick = { onSelectClip(if (r.clip.id == selectedClipId) null else r.clip.id) }
                )
            }

            // 转场指示器：接缝处的圆形图标
            sortedClips.forEachIndexed { i, clip ->
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
 * 转场指示器 —— 位于接缝（前一片段真正结束点）的小圆形，点击可修改转场。
 */
@Composable
private fun TransitionBadge(
    clip: Clip,
    pixelsPerSecond: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val size = 16.dp
    val centerX = with(density) { (clip.timelineEnd * pixelsPerSecond).toFloat().toDp() }

    Box(
        modifier = Modifier
            .offset(x = centerX - size / 2, y = 14.dp)
            .size(size)
            .clip(CircleShape)
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
 * 片段块 —— 点击选中。显示位置/宽度由调用方计算。
 */
@Composable
private fun ClipBlock(
    clip: Clip,
    displayStartSeconds: Double,
    displayWidthSeconds: Double,
    pixelsPerSecond: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val width = with(density) { (displayWidthSeconds * pixelsPerSecond).toFloat().toDp() }
        .coerceAtLeast(20.dp)
    val offsetX = with(density) { (displayStartSeconds * pixelsPerSecond).toFloat().toDp() }

    Box(
        modifier = Modifier
            .offset(x = offsetX)
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
 * ★ 固定播放头 —— 钉死滚动视口正中央的竖线 + 顶部三角形手柄（剪映式）。
 * 绘制在滚动容器上层，不随胶片滚动。
 */
@Composable
private fun CenterPlayhead(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 竖线
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Color(0xFFFFFFFF).copy(alpha = 0.9f))
        )
        // 顶部手柄（小三角，倒置）
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color(0xFF2196F3),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(14.dp)
                .offset(y = (-2).dp)
        )
    }
}

/** 格式化时间码 */
private fun formatTimecode(seconds: Double): String {
    val totalSec = seconds.toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}'${s}\"" else "${s}\""
}
