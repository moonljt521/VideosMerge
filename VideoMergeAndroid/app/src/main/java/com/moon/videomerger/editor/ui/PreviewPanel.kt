package com.moon.videomerger.editor.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbMatrix
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.moon.videomerger.editor.data.Clip
import com.moon.videomerger.editor.data.EditorUiState
import com.moon.videomerger.editor.data.PipShape
import com.moon.videomerger.editor.data.TRANSITION_PREVIEW_MIN_OVERLAP
import com.moon.videomerger.editor.data.TrackType
import com.moon.videomerger.editor.data.TransitionEffect
import com.moon.videomerger.editor.data.effectiveTransitionOverlap
import com.moon.videomerger.editor.data.findTransitionZone
import com.moon.videomerger.editor.data.interpolatePipPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.hypot

/**
 * 预览面板 —— ExoPlayer 实时预览（分层渲染）。
 *
 * ★ 转场实时预览：播放头进入转场重叠区时，前后两个片段同时渲染，
 *   上层按转场进度做 淡入淡出 / 滑动 / 擦除 近似动画，音频交叉衰减；
 *   与导出 xfade 的作用区间一致（见 findTransitionZone）。
 *
 * 其他说明：
 * - 单层模式：播放主轨当前播放头所在片段。
 * - 倒放片段按正放近似映射；旋转/翻转等几何变换仍只在导出时应用。
 * - 调色通过 Media3 视频特效实时预览。
 */

/** 视频层角色 */
private enum class LayerRole { SOLO, BOTTOM, TOP }

@Composable
fun PreviewPanel(
    state: EditorUiState,
    playheadFlow: StateFlow<Double>,
    onTogglePlay: () -> Unit,
    onSeek: (Double) -> Unit,
    onPlaybackEnded: () -> Unit = {}
) {
    // ★ 本地收集播放头：只有预览区域跟随播放重组
    val currentPosition by playheadFlow.collectAsState()
    val mainTrack = state.project.mainTrack
    val clips = mainTrack?.clips?.sortedBy { it.timelineStart } ?: emptyList()

    if (clips.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("导入视频后在此预览", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    // 当前是否处于转场重叠区
    val zone = findTransitionZone(clips, currentPosition)

    // 主显片段：转场区内取下一片段（顶层），否则取包含播放头的片段
    val primaryClip = zone?.next ?: clips.find { clip ->
        currentPosition >= clip.timelineStart - 0.05 &&
            currentPosition < clip.timelineEnd - 0.05
    } ?: clips.first()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // ★ 点击预览区域切换播放/暂停（剪映交互）
            .clickable { onTogglePlay() },
        contentAlignment = Alignment.Center
    ) {
        // 视频真实宽高比（含旋转），供水印/PiP 定位 aspect-fit 使用
        var videoAspect by remember { mutableStateOf<Float?>(null) }

        // ── 视频层 ──
        // ★ key(clip.id) 保证相邻组合间同一片段的播放器实例存活：
        //   进入转场区时底层（前一片段）不被重建，离开时顶层无缝转为单层
        val layers: List<Triple<Clip, LayerRole, Float>> = if (zone != null) {
            val p = zone.progress(currentPosition).toFloat()
            listOf(
                Triple(zone.prev, LayerRole.BOTTOM, p),
                Triple(zone.next, LayerRole.TOP, p),
            )
        } else {
            listOf(Triple(primaryClip, LayerRole.SOLO, 1f))
        }

        layers.forEach { (clip, role, p) ->
            key(clip.id) {
                VideoLayer(
                    clip = clip,
                    role = role,
                    transitionEffect = if (role == LayerRole.TOP) zone?.prev?.transition else null,
                    progress = p,
                    isPlaying = state.isPlaying,
                    playhead = currentPosition,
                    clips = clips,
                    drivesPlayhead = role != LayerRole.BOTTOM,
                    onSeek = onSeek,
                    onPlaybackEnded = onPlaybackEnded,
                    reportAspect = { videoAspect = it },
                )
            }
        }

        // ── 淡入白/黑的过曝遮罩 ──
        if (zone != null) {
            val p = zone.progress(currentPosition).toFloat()
            val scrimAlpha = 1f - kotlin.math.abs(2f * p - 1f)
            val effect = zone.prev.transition
            if ((effect == TransitionEffect.FADEWHITE || effect == TransitionEffect.FADEBLACK) && scrimAlpha > 0.01f) {
                val scrimColor = if (effect == TransitionEffect.FADEWHITE) Color.White else Color.Black
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = scrimAlpha }
                        .background(scrimColor)
                )
            }
        }

        // 图片水印叠加层（跟随主显片段）
        primaryClip.imageWatermarkPath?.let { path ->
            ImageWatermarkOverlay(
                clip = primaryClip,
                path = path,
                videoAspect = videoAspect,
                maxWidthDp = maxWidth.value,
                maxHeightDp = maxHeight.value,
            )
        }

        // 画中画叠加层预览（静态帧）
        PipOverlays(
            state = state,
            currentPosition = currentPosition,
            videoAspect = videoAspect,
            fallbackClip = primaryClip,
            maxWidthDp = maxWidth.value,
            maxHeightDp = maxHeight.value,
        )

        // 去水印区域标识（红框；实际模糊/马赛克在导出时应用；仅时间段内显示）
        if (primaryClip.watermarkRegions.isNotEmpty()) {
            WatermarkRegionOverlay(
                clip = primaryClip,
                playhead = currentPosition,
                videoAspect = videoAspect,
                maxWidthDp = maxWidth.value,
                maxHeightDp = maxHeight.value,
            )
        }

        // ★ 预览保真度提示：导出会应用但预览不实时呈现的效果
        // （转场已支持实时近似预览，不再列入）
        val unpreviewed = buildList {
            if (primaryClip.reversed) add("倒放")
            if (primaryClip.rotation != 0 || primaryClip.hflip || primaryClip.vflip) add("旋转/翻转")
            if (primaryClip.textOverlay != null) add("文字水印")
            if (primaryClip.logoCutTime != null) add("截断")
        }
        if (unpreviewed.isNotEmpty()) {
            Surface(
                color = Color(0x99000000),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "预览不含${unpreviewed.joinToString("/")}，以导出为准",
                    color = Color(0xFFCCCCCC),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 选中片段信息：★ 已移除文件名浮层（遮挡画面），选中状态看时间轴高亮即可

        // 时间进度（右上角，小字，不遮挡内容）
        Text(
            text = formatTime(currentPosition) + " / " + formatTime(state.project.totalDuration),
            color = Color(0xCCFFFFFF),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 8.dp)
                .background(Color(0x66000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
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
    }
}

// ═══════════════════════════════════════
//  单个视频层（一个 ExoPlayer 实例）
// ═══════════════════════════════════════

@Composable
private fun VideoLayer(
    clip: Clip,
    role: LayerRole,
    transitionEffect: TransitionEffect?,
    progress: Float,
    isPlaying: Boolean,
    playhead: Double,
    clips: List<Clip>,
    drivesPlayhead: Boolean,
    onSeek: (Double) -> Unit,
    onPlaybackEnded: () -> Unit = {},
    reportAspect: (Float?) -> Unit,
) {
    val context = LocalContext.current

    // 调色参数（量化到 0.01 降低特效链更新频率）
    val preset = clip.filterPreset
    val brightness = quantize001((preset.brightness + clip.brightness).toFloat().coerceIn(-1f, 1f))
    val contrast = quantize001((preset.contrast + clip.contrast).toFloat().coerceIn(-1f, 1f))
    val saturation = quantize001((preset.saturation + clip.saturation).toFloat().coerceIn(-1f, 1f))
    val hue = quantize001(preset.hue.toFloat())
    val blurSigma = if (clip.blurBgEnabled) quantize001((clip.blurStrength / 4f).coerceIn(0.5f, 10f)) else 0f

    val player = remember(clip.id) {
        // ★ 创建时刻的播放头决定初始源位置：
        //   SOLO/BOTTOM = 播放头所在处；TOP（刚进入转场区）≈ 片段起点
        val src = sourceTimeAt(clip, playhead)
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    reportAspect(aspectOf(videoSize))
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(clip.mediaPath))))
            prepare()
            playWhenReady = isPlaying
            playbackParameters = playbackParameters.withSpeed(clip.speed.toFloat())
            seekTo((src.coerceIn(clip.trimStart, endOf(clip)) * 1000).toLong())
        }
    }

    DisposableEffect(clip.id) {
        onDispose { player.release() }
    }

    // 转场音量交叉衰减：BOTTOM 1→0，TOP 0→1
    LaunchedEffect(role, progress) {
        player.volume = when (role) {
            LayerRole.SOLO -> 1f
            LayerRole.BOTTOM -> (1f - progress).coerceIn(0f, 1f)
            LayerRole.TOP -> progress.coerceIn(0f, 1f)
        }
    }

    // 播放/暂停同步
    LaunchedEffect(isPlaying) {
        player.playWhenReady = isPlaying
    }

    // 变速同步
    LaunchedEffect(clip.id, clip.speed) {
        player.playbackParameters = player.playbackParameters.withSpeed(clip.speed.toFloat())
    }

    // 调色/模糊特效原地更新
    LaunchedEffect(player, brightness, contrast, saturation, hue, blurSigma) {
        try {
            player.setVideoEffects(buildPreviewEffects(brightness, contrast, saturation, hue, blurSigma))
        } catch (e: Exception) {
            android.util.Log.w("PreviewPanel", "setVideoEffects 不支持: ${e.message}")
        }
    }

    // 部分设备 onVideoSizeChanged 不可靠，轮询兜底拿真实宽高比
    LaunchedEffect(player, clip.id) {
        while (true) {
            val vs = player.videoSize
            if (vs.width > 0 && vs.height > 0) {
                reportAspect(aspectOf(vs))
                break
            }
            delay(100)
        }
    }

    // 暂停态：播放头移动（拖时间轴）或裁剪/变速变化时重新定位
    var lastSeekedPos by remember(clip.id) { mutableStateOf(playhead) }
    LaunchedEffect(
        playhead, isPlaying,
        clip.trimStart, clip.trimEnd, clip.speed, clip.reversed
    ) {
        if (!isPlaying && playhead != lastSeekedPos) {
            lastSeekedPos = playhead
            val src = sourceTimeAt(clip, playhead)
            val targetMs = (src.coerceIn(clip.trimStart, endOf(clip)) * 1000).toLong()
            if (kotlin.math.abs(player.currentPosition - targetMs) > 100) {
                player.seekTo(targetMs)
            }
        }
    }

    // 播放推进：只有驱动层负责写播放头
    LaunchedEffect(isPlaying, drivesPlayhead, clip.id) {
        if (!drivesPlayhead) return@LaunchedEffect
        while (isPlaying) {
            val idx = clips.indexOfFirst { it.id == clip.id }
            val nextClip = clips.getOrNull(idx + 1)

            val trimEndMs = (endOf(clip) * 1000).toLong()
            if (clip.trimEnd > 0 && player.currentPosition >= trimEndMs) {
                if (nextClip != null) {
                    // 硬边界直接跳下一段起点（软边界通常先由区域切换接管，不会走到这）
                    onSeek(nextClip.timelineStart)
                    break
                } else {
                    // ★ 最后一段播完 → 停在结尾（单遍播放，不循环），通知上层暂停
                    onSeek(clip.timelineEnd)
                    onPlaybackEnded()
                    break
                }
            }

            // 正常推进：播放器位置 → 时间轴位置
            val posSec = player.currentPosition / 1000.0
            val timelinePos = timelinePosOf(clip, posSec)
                .coerceIn(clip.timelineStart, clip.timelineEnd)
            onSeek(timelinePos)
            delay(100)
        }
    }

    // 渲染：TOP 层应用转场变换
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .then(applyTransitionTransform(transitionEffect, progress))
    )
}

// ═══════════════════════════════════════
//  转场变换（对上层片段的近似动画）
// ═══════════════════════════════════════

/**
 * 根据转场类型生成上层片段的变换：
 * - 淡入类（fade/dissolve/circleclose 等）→ alpha 渐显（默认）
 * - 滑动类 → 平移进入
 * - 擦除类 → 矩形区域逐渐展开
 * - 圆形展开 → 圆形 clip 半径增大
 * - 淡入白/黑 → alpha 渐显 + 顶层遮罩（由父级绘制）
 */
private fun applyTransitionTransform(effect: TransitionEffect?, p: Float): Modifier {
    if (effect == null) return Modifier
    val q = p.coerceIn(0f, 1f)
    val base = when (effect) {
        TransitionEffect.SLIDELEFT, TransitionEffect.SMOOTHLEFT ->
            Modifier.graphicsLayer { translationX = size.width * (1f - q) }
        TransitionEffect.SLIDERIGHT, TransitionEffect.SMOOTHRIGHT ->
            Modifier.graphicsLayer { translationX = -size.width * (1f - q) }
        TransitionEffect.SLIDEUP ->
            Modifier.graphicsLayer { translationY = size.height * (1f - q) }
        TransitionEffect.SLIDEDOWN ->
            Modifier.graphicsLayer { translationY = -size.height * (1f - q) }
        TransitionEffect.WIPELEFT ->
            // 从右边缘向左擦除展开
            Modifier.drawWithContent {
                val x = size.width * (1f - q)
                clipRect(left = x) { this@drawWithContent.drawContent() }
            }
        TransitionEffect.WIPERIGHT ->
            Modifier.drawWithContent {
                val x = size.width * q
                clipRect(right = x) { this@drawWithContent.drawContent() }
            }
        TransitionEffect.WIPEUP ->
            Modifier.drawWithContent {
                val y = size.height * (1f - q)
                clipRect(top = y) { this@drawWithContent.drawContent() }
            }
        TransitionEffect.WIPEDOWN ->
            Modifier.drawWithContent {
                val y = size.height * q
                clipRect(bottom = y) { this@drawWithContent.drawContent() }
            }
        TransitionEffect.CIRCLEOPEN ->
            Modifier.drawWithContent {
                val r = hypot(size.width, size.height) / 2f * q
                if (r > 0f) {
                    val path = Path().apply { addOval(Rect(center, r)) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
            }
        else -> Modifier
    }
    // 所有转场都以渐显为基础（滑动/擦除的边缘更平滑，纯淡入类完全靠它）
    return base.graphicsLayer { alpha = q.coerceIn(0f, 1f) }
}

// ═══════════════════════════════════════
//  图片水印 / 画中画叠加
// ═══════════════════════════════════════

@Composable
private fun ImageWatermarkOverlay(
    clip: Clip,
    path: String,
    videoAspect: Float?,
    maxWidthDp: Float,
    maxHeightDp: Float,
) {
    val wmBitmap = remember(path) { BitmapFactory.decodeFile(path) }
    val aspect = videoAspect ?: if (clip.height > 0) clip.width.toFloat() / clip.height.toFloat() else null
    wmBitmap?.let { bmp ->
        if (aspect == null || aspect <= 0f || bmp.width <= 0 || bmp.height <= 0) return@let
        val scale = clip.imageWatermarkScale.toFloat()
        val opacity = clip.imageWatermarkOpacity.toFloat().coerceIn(0f, 1f)
        val pw = maxWidthDp
        val ph = maxHeightDp
        val previewAspect = pw / ph
        val videoW: Float
        val videoH: Float
        if (aspect > previewAspect) {
            videoW = pw; videoH = pw / aspect
        } else {
            videoH = ph; videoW = ph * aspect
        }
        val videoLeft = (pw - videoW) / 2f
        val videoTop = (ph - videoH) / 2f
        val wmWidthDp = (videoW * scale).coerceAtLeast(1f)
        val wmHeightDp = wmWidthDp * (bmp.height.toFloat() / bmp.width.toFloat())
        val padDp = (videoW * 0.01f).coerceAtLeast(2f)
        val xDp: Float
        val yDp: Float
        when (clip.imageWatermarkPosition) {
            "top-left" -> { xDp = videoLeft + padDp; yDp = videoTop + padDp }
            "top-right" -> { xDp = videoLeft + videoW - wmWidthDp - padDp; yDp = videoTop + padDp }
            "bottom-left" -> { xDp = videoLeft + padDp; yDp = videoTop + videoH - wmHeightDp - padDp }
            "center" -> { xDp = videoLeft + (videoW - wmWidthDp) / 2f; yDp = videoTop + (videoH - wmHeightDp) / 2f }
            else -> { xDp = videoLeft + videoW - wmWidthDp - padDp; yDp = videoTop + videoH - wmHeightDp - padDp }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                alpha = opacity,
                modifier = Modifier
                    .offset(x = xDp.dp, y = yDp.dp)
                    .size(width = wmWidthDp.dp, height = wmHeightDp.dp)
            )
        }
    }
}

@Composable
private fun PipOverlays(
    state: EditorUiState,
    currentPosition: Double,
    videoAspect: Float?,
    fallbackClip: Clip,
    maxWidthDp: Float,
    maxHeightDp: Float,
) {
    val pipClips = state.project.tracks
        .filter { it.type == TrackType.PICTURE }
        .flatMap { it.clips }
        .filter {
            it.pipEnabled &&
                currentPosition >= it.timelineStart - 0.05 &&
                currentPosition < it.timelineEnd - 0.05
        }
    if (pipClips.isEmpty()) return
    val aspect = videoAspect ?: if (fallbackClip.height > 0) {
        fallbackClip.width.toFloat() / fallbackClip.height.toFloat()
    } else null
    if (aspect == null || aspect <= 0f) return
    val pw = maxWidthDp
    val ph = maxHeightDp
    val previewAspect = pw / ph
    val videoW: Float
    val videoH: Float
    if (aspect > previewAspect) {
        videoW = pw; videoH = pw / aspect
    } else {
        videoH = ph; videoW = ph * aspect
    }
    val videoLeft = (pw - videoW) / 2f
    val videoTop = (ph - videoH) / 2f

    Box(modifier = Modifier.fillMaxSize()) {
        pipClips.forEach { pip ->
            val srcPath = if (pip.isImage) pip.mediaPath else pip.thumbnailPath
            val bmp = srcPath?.let { remember(srcPath) { BitmapFactory.decodeFile(srcPath) } }
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val pipW = (videoW * pip.pipWidth.toFloat().coerceIn(0.05f, 1f)).coerceAtLeast(1f)
                val pipH = pipW * (bmp.height.toFloat() / bmp.width.toFloat())
                val (px, py) = interpolatePipPosition(pip.pipKeyframes, currentPosition, pip.pipX, pip.pipY)
                val xDp = videoLeft + (videoW - pipW) * px.toFloat().coerceIn(0f, 1f)
                val yDp = videoTop + (videoH - pipH) * py.toFloat().coerceIn(0f, 1f)
                val shape = when (pip.pipShape) {
                    PipShape.RECT -> RoundedCornerShape(0.dp)
                    PipShape.ROUNDED -> RoundedCornerShape((pipW * pip.pipCornerRadius.toFloat()).dp)
                    PipShape.CIRCLE -> CircleShape
                }
                val pipModifier = Modifier
                    .offset(x = xDp.dp, y = yDp.dp)
                    .size(width = pipW.dp, height = pipH.dp)
                    .clip(shape)
                    .then(
                        if (pip.pipBorder) {
                            Modifier.border((pipW * pip.pipBorderWidth.toFloat()).dp, Color.White, shape)
                        } else Modifier
                    )
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    alpha = pip.pipOpacity.toFloat().coerceIn(0f, 1f),
                    modifier = pipModifier
                )
            }
        }
    }
}

// ═══════════════════════════════════════
//  去水印区域标识
// ═══════════════════════════════════════

@Composable
private fun WatermarkRegionOverlay(
    clip: Clip,
    playhead: Double,
    videoAspect: Float?,
    maxWidthDp: Float,
    maxHeightDp: Float,
) {
    val aspect = videoAspect ?: if (clip.height > 0) clip.width.toFloat() / clip.height.toFloat() else null
    if (aspect == null || aspect <= 0f) return
    // 播放头 → 源视频时间，判断各区域是否处于生效期（移动水印分时段显示）
    val srcTime = sourceTimeAt(clip, playhead)
    val activeRegions = clip.watermarkRegions.filter { it.activeAt(srcTime) }
    if (activeRegions.isEmpty()) return
    val pw = maxWidthDp
    val ph = maxHeightDp
    val previewAspect = pw / ph
    val videoW: Float
    val videoH: Float
    if (aspect > previewAspect) {
        videoW = pw; videoH = pw / aspect
    } else {
        videoH = ph; videoW = ph * aspect
    }
    val videoLeft = (pw - videoW) / 2f
    val videoTop = (ph - videoH) / 2f

    Box(modifier = Modifier.fillMaxSize()) {
        activeRegions.forEachIndexed { i, r ->
            val w = (videoW * r.w.toFloat()).coerceAtLeast(8f)
            val h = (videoH * r.h.toFloat()).coerceAtLeast(8f)
            val x = videoLeft + videoW * r.x.toFloat()
            val y = videoTop + videoH * r.y.toFloat()
            Box(
                modifier = Modifier
                    .offset(x = x.dp, y = y.dp)
                    .size(width = w.dp, height = h.dp)
                    .background(Color(0x22FF5252))
                    .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(2.dp))
            ) {
                Text(
                    text = "水印${i + 1}",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .background(Color(0xAAFF5252), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════
//  工具函数
// ═══════════════════════════════════════

/** 片段有效出点（源时间） */
private fun endOf(clip: Clip): Double =
    if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration

/**
 * 时间轴位置 → 预览用源时间。
 * 倒放片段按正放近似映射（预览不真正倒放）。
 */
private fun sourceTimeAt(clip: Clip, timelinePos: Double): Double =
    if (clip.reversed) {
        clip.trimStart + (timelinePos - clip.timelineStart) * clip.speed
    } else {
        clip.sourceTimeAt(timelinePos)
    }.coerceIn(clip.trimStart, endOf(clip))

/** 播放器源位置 → 时间轴位置（sourceTimeAt 的逆，倒放同样按正放近似） */
private fun timelinePosOf(clip: Clip, sourcePosSec: Double): Double =
    clip.timelinePosOf(sourcePosSec)

/** 含旋转的真实显示宽高比 */
private fun aspectOf(vs: VideoSize): Float? {
    val rot = ((vs.unappliedRotationDegrees % 360) + 360) % 360
    val w = if (rot == 90 || rot == 270) vs.height else vs.width
    val h = if (rot == 90 || rot == 270) vs.width else vs.height
    val aspect = w * vs.pixelWidthHeightRatio / h
    return if (aspect > 0f) aspect else null
}

/** 格式化时间 mm:ss */
private fun formatTime(seconds: Double): String {
    val m = (seconds / 60).toInt()
    val s = (seconds % 60).toInt()
    return "%02d:%02d".format(m, s)
}

/** 量化到 0.01，减少滑块拖动时特效链更新频率 */
private fun quantize001(v: Float): Float = Math.round(v * 100f) / 100f

/**
 * 构建预览特效链。
 *
 * 顺序与 FilterBuilder 保持一致：先做 RGB 的亮度/对比度/饱和度（ColorAdjustMatrix），
 * 再做色调旋转（HslAdjustment）。gamma 当前不参与预览，导出时由 FFmpeg eq 应用。
 */
private fun buildPreviewEffects(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    hue: Float,
    blurSigma: Float
): List<Effect> {
    val effects = mutableListOf<Effect>()
    // 模糊背景：Media3 单输入特效无法 1:1 还原“前景清晰 + 背景模糊”的分层，
    // 这里用整帧高斯模糊近似，让用户能看到强度变化；导出时仍按 split/overlay 精确处理。
    if (blurSigma > 0f) {
        effects.add(GaussianBlur(blurSigma))
    }
    if (brightness != 0f || contrast != 0f || saturation != 0f) {
        effects.add(ColorAdjustMatrix(brightness, contrast, saturation))
    }
    if (hue != 0f) {
        effects.add(
            HslAdjustment.Builder()
                .adjustHue(hue)
                .build()
        )
    }
    return effects
}

/**
 * 调色矩阵（亮度/对比度/饱和度 → 单个 4x4 RGB 矩阵，GPU 实时处理）。
 *
 * 变换公式：v' = k * S * v + t
 *   - S：饱和度矩阵（按 Rec.709 亮度权重混入灰度）
 *   - k：对比度缩放（绕 0.5 中点）
 *   - t：亮度偏移 + 对比度平移 0.5*(1-k)
 * 参数范围均为 [-1, 1]，0 = 不变。
 */
private class ColorAdjustMatrix(
    private val brightness: Float,
    private val contrast: Float,
    private val saturation: Float
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val s = 1f + saturation          // -1→灰度 0→原色
        val k = 1f + contrast            // 对比度缩放系数
        val oneMinusS = 1f - s
        // Rec.709 亮度权重
        val rw = 0.213f; val gw = 0.715f; val bw = 0.072f
        val t = brightness + 0.5f * (1f - k)

        // 列主序 4x4：index = col*4 + row，alpha 透传
        return floatArrayOf(
            k * (s + oneMinusS * rw), k * (oneMinusS * rw),     k * (oneMinusS * rw),     0f,
            k * (oneMinusS * gw),     k * (s + oneMinusS * gw), k * (oneMinusS * gw),     0f,
            k * (oneMinusS * bw),     k * (oneMinusS * bw),     k * (s + oneMinusS * bw), 0f,
            t,                        t,                        t,                        1f
        )
    }
}
