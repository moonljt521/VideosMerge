package com.moon.videomerger.editor.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.moon.videomerger.editor.data.EditorUiState
import kotlinx.coroutines.delay
import java.io.File

/**
 * 预览面板 —— ExoPlayer 实时预览。
 *
 * 播放主轨当前播放头所在片段。调整 trim/speed 时会重新 seek 到正确位置。
 * 支持手动拖动时间轴（非播放状态下 seek）。
 * 播放到结尾自动循环回到开头。
 *
 * 注意：实时滤镜预览（调色/旋转 等）暂未实现，导出时由 FFmpeg 应用。
 * 这里仅播放原始视频的 trim 区间。
 */
@Composable
fun PreviewPanel(
    state: EditorUiState,
    onTogglePlay: () -> Unit,
    onSeek: (Double) -> Unit
) {
    val context = LocalContext.current
    val mainTrack = state.project.mainTrack
    val clips = mainTrack?.clips?.sortedBy { it.timelineStart } ?: emptyList()

    // 找到当前播放位置对应的片段
    val currentClip = clips.find { clip ->
        state.currentPosition >= clip.timelineStart - 0.05 &&
            state.currentPosition < clip.timelineEnd - 0.05
    } ?: clips.firstOrNull()

    if (currentClip == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("导入视频后在此预览", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    // ExoPlayer —— 当片段切换时重建，并 seek 到当前播放头对应的源时间
    val player = remember(currentClip.id) {
        // 时间轴位置 → 源视频位置
        val sourceTime = currentClip.trimStart +
            (state.currentPosition - currentClip.timelineStart) * currentClip.speed
        val seekMs = sourceTime.coerceIn(currentClip.trimStart, currentClip.trimEnd)
            .let { (it * 1000).toLong() }

        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(currentClip.mediaPath))))
            prepare()
            playWhenReady = state.isPlaying
            // 跳到当前播放头对应的源时间（而非固定 trimStart）
            seekTo(seekMs)
        }
    }

    DisposableEffect(currentClip.id) {
        onDispose { player.release() }
    }

    // 当 trimStart 改变时重新 seek（用户拖动入点滑块）
    LaunchedEffect(currentClip.id, currentClip.trimStart) {
        val targetMs = (currentClip.trimStart * 1000).toLong()
        if (kotlin.math.abs(player.currentPosition - targetMs) > 200) {
            player.seekTo(targetMs)
        }
    }

    // 播放状态同步
    LaunchedEffect(state.isPlaying) {
        player.playWhenReady = state.isPlaying
    }

    // ★ 手动 seek：非播放状态下，当 currentPosition 变化时 seek player
    //    用一个变量记录上次 seek 的位置，避免循环
    var lastSeekedTimelinePos by remember(currentClip.id) { mutableStateOf(state.currentPosition) }
    LaunchedEffect(state.currentPosition, state.isPlaying) {
        if (!state.isPlaying && state.currentPosition != lastSeekedTimelinePos) {
            lastSeekedTimelinePos = state.currentPosition
            // 时间轴位置 → 源视频位置
            val sourceTime = currentClip.trimStart +
                (state.currentPosition - currentClip.timelineStart) * currentClip.speed
            val targetMs = (sourceTime * 1000).toLong()
                .coerceIn(0L, (currentClip.mediaDuration * 1000).toLong())
            if (kotlin.math.abs(player.currentPosition - targetMs) > 100) {
                player.seekTo(targetMs)
            }
        }
    }

    // 播放进度更新 + 片段边界检测 + 循环
    LaunchedEffect(state.isPlaying, currentClip.id) {
        while (state.isPlaying) {
            val currentMs = player.currentPosition
            val trimEndMs = (currentClip.trimEnd * 1000).toLong()

            // 检查是否到达片段结尾
            if (currentMs >= trimEndMs && currentClip.trimEnd > 0) {
                val nextClip = clips.find { it.timelineStart >= currentClip.timelineEnd - 0.05 }
                if (nextClip != null) {
                    // 跳到下一片段的入点，break 让 LaunchedEffect 因 currentClip.id 变化而重启
                    onSeek(nextClip.timelineStart)
                } else {
                    // ★ 没有下一片段，循环回到开头（不 break，继续播放）
                    player.seekTo((currentClip.trimStart * 1000).toLong())
                    onSeek(0.0)
                    lastSeekedTimelinePos = 0.0
                }
                if (nextClip != null) break  // 切换片段时需要 break 让 player 重建
            }

            // 计算时间轴位置并通知 ViewModel
            val pos = player.currentPosition / 1000.0
            val timelinePos = currentClip.timelineStart + (pos - currentClip.trimStart) * currentClip.speed
            onSeek(timelinePos)
            delay(100)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 播放/暂停按钮（覆盖在预览上）
        if (!state.isPlaying) {
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0x88000000))
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 选中片段信息
        state.selectedClip?.let { clip ->
            Surface(
                color = Color(0x88000000),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = clip.mediaName,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 播放控制
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x88000000)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = formatTime(state.currentPosition) + " / " + formatTime(state.project.totalDuration),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/** 格式化时间 mm:ss */
private fun formatTime(seconds: Double): String {
    val m = (seconds / 60).toInt()
    val s = (seconds % 60).toInt()
    return "%02d:%02d".format(m, s)
}
