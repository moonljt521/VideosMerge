package com.moon.videomerger.editor.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.moon.videomerger.editor.data.EditorUiState
import com.moon.videomerger.editor.data.PipShape
import com.moon.videomerger.editor.data.TrackType
import kotlinx.coroutines.delay
import java.io.File

/**
 * 预览面板 —— ExoPlayer 实时预览。
 *
 * 播放主轨当前播放头所在片段。调整 trim/speed 时会重新 seek 到正确位置。
 * 支持手动拖动时间轴（非播放状态下 seek）。
 * 播放到结尾自动循环回到开头。
 *
 * 注意：旋转/翻转等几何变换暂未实时预览，导出时由 FFmpeg 应用。
 * 滤镜调色（预设/亮度/对比度/饱和度）通过 Media3 RgbFilter 实时预览。
 */
@Composable
fun PreviewPanel(
    state: EditorUiState,
    onTogglePlay: () -> Unit,
    onSeek: (Double) -> Unit
) {
    android.util.Log.w("PreviewPanel", "enter")
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

    // 当前片段的调色参数（滤镜预设 + 手动调节，对应 FilterBuilder 的调色参数）
    // ★ 量化到 0.01：避免滑块高频变化导致渲染管线频繁重建；
    //   色调（hue）通过 Media3 内置 HslAdjustment 实时预览，伽马（gamma）仍只在导出时应用。
    val preset = currentClip.filterPreset
    val previewBrightness = quantize001((preset.brightness + currentClip.brightness).toFloat().coerceIn(-1f, 1f))
    val previewContrast = quantize001((preset.contrast + currentClip.contrast).toFloat().coerceIn(-1f, 1f))
    val previewSaturation = quantize001((preset.saturation + currentClip.saturation).toFloat().coerceIn(-1f, 1f))
    val previewHue = quantize001(preset.hue.toFloat())
    val previewBlurSigma = if (currentClip.blurBgEnabled) {
        quantize001((currentClip.blurStrength / 4f).coerceIn(0.5f, 10f))
    } else {
        0f
    }
    android.util.Log.d("PreviewPanel", "clip=${currentClip.id} preset=${preset.name} b=${previewBrightness} c=${previewContrast} s=${previewSaturation} h=${previewHue}")

    // 时间轴位置 → 源视频位置；倒放片段按正放近似映射。
    // 放在 remember 外，避免同一套映射逻辑在多处复制。
    fun previewSourceTime(timelinePos: Double): Double =
        if (currentClip.reversed) {
            currentClip.trimStart + (timelinePos - currentClip.timelineStart) * currentClip.speed
        } else {
            currentClip.sourceTimeAt(timelinePos)
        }

    // ExoPlayer + PlayerView —— 片段或调色参数变化时整体重建。
    // 用 key(...) 包住播放器及其 PlayerView，参数变化时 Compose 会连同 PlayerView
    // 一起销毁重建，避免旧 player 已释放但 PlayerView 仍持有旧引用，导致滤镜残留或黑屏。
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 图片水印预览：用 Compose 叠加层绘制在视频之上（与播放按钮同一层，保证在视频 Surface 之上）。
        // 视频真实画面的宽高比来自 ExoPlayer 的 onVideoSizeChanged（已包含旋转），
        // 再据此在预览框内做 aspect-fit 计算，水印只会落在画面内，不会跑到黑边。
        var videoAspect by remember { mutableStateOf<Float?>(null) }

        key(
            currentClip.id,
            previewBrightness,
            previewContrast,
            previewSaturation,
            previewHue,
            previewBlurSigma
        ) {
            val sourceTime = previewSourceTime(state.currentPosition)
            val seekMs = sourceTime.coerceIn(currentClip.trimStart, currentClip.trimEnd)
                .let { (it * 1000).toLong() }

            val player = remember {
                ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            val rot = ((videoSize.unappliedRotationDegrees % 360) + 360) % 360
                            val w = if (rot == 90 || rot == 270) videoSize.height else videoSize.width
                            val h = if (rot == 90 || rot == 270) videoSize.width else videoSize.height
                            val aspect = w * videoSize.pixelWidthHeightRatio / h
                            if (aspect > 0f) videoAspect = aspect
                        }
                    })
                    try {
                        setVideoEffects(
                            buildPreviewEffects(
                                previewBrightness,
                                previewContrast,
                                previewSaturation,
                                previewHue,
                                previewBlurSigma
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("PreviewPanel", "setVideoEffects 不支持: ${e.message}")
                    }
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(File(currentClip.mediaPath))))
                    prepare()
                    playWhenReady = state.isPlaying
                    playbackParameters = playbackParameters.withSpeed(currentClip.speed.toFloat())
                    seekTo(seekMs)
                }
            }

            DisposableEffect(Unit) {
                onDispose { player.release() }
            }

            // 部分设备上 onVideoSizeChanged 回调不可靠，这里再轮询一次 player.videoSize，
            // 拿到含旋转的真实显示宽高比，作为水印定位的兜底来源。
            LaunchedEffect(player, currentClip.id) {
                while (true) {
                    val vs = player.videoSize
                    if (vs.width > 0 && vs.height > 0) {
                        val rot = ((vs.unappliedRotationDegrees % 360) + 360) % 360
                        val w = if (rot == 90 || rot == 270) vs.height else vs.width
                        val h = if (rot == 90 || rot == 270) vs.width else vs.height
                        val aspect = w * vs.pixelWidthHeightRatio / h
                        if (aspect > 0f) {
                            videoAspect = aspect
                            break
                        }
                    }
                    delay(100)
                }
            }

            LaunchedEffect(currentClip.id, currentClip.speed) {
                player.playbackParameters = player.playbackParameters.withSpeed(currentClip.speed.toFloat())
                if (!state.isPlaying) {
                    val targetMs = (previewSourceTime(state.currentPosition) * 1000).toLong()
                        .coerceIn(0L, (currentClip.mediaDuration * 1000).toLong())
                    if (kotlin.math.abs(player.currentPosition - targetMs) > 100) {
                        player.seekTo(targetMs)
                    }
                }
            }

            LaunchedEffect(currentClip.id, currentClip.trimStart) {
                val targetMs = (currentClip.trimStart * 1000).toLong()
                if (kotlin.math.abs(player.currentPosition - targetMs) > 200) {
                    player.seekTo(targetMs)
                }
            }

            LaunchedEffect(state.isPlaying) {
                player.playWhenReady = state.isPlaying
            }

            var lastSeekedTimelinePos by remember { mutableStateOf(state.currentPosition) }
            LaunchedEffect(state.currentPosition, state.isPlaying) {
                if (!state.isPlaying && state.currentPosition != lastSeekedTimelinePos) {
                    lastSeekedTimelinePos = state.currentPosition
                    val sourceTime = previewSourceTime(state.currentPosition)
                    val targetMs = (sourceTime * 1000).toLong()
                        .coerceIn(0L, (currentClip.mediaDuration * 1000).toLong())
                    if (kotlin.math.abs(player.currentPosition - targetMs) > 100) {
                        player.seekTo(targetMs)
                    }
                }
            }

            LaunchedEffect(state.isPlaying, currentClip.id) {
                while (state.isPlaying) {
                    val currentMs = player.currentPosition
                    val trimEndMs = (currentClip.trimEnd * 1000).toLong()

                    if (currentMs >= trimEndMs && currentClip.trimEnd > 0) {
                        val nextClip = clips.find { it.timelineStart >= currentClip.timelineEnd - 0.05 }
                        if (nextClip != null) {
                            onSeek(nextClip.timelineStart)
                        } else {
                            player.seekTo((currentClip.trimStart * 1000).toLong())
                            onSeek(0.0)
                            lastSeekedTimelinePos = 0.0
                        }
                        if (nextClip != null) break
                    }

                    val pos = player.currentPosition / 1000.0
                    val timelinePos = if (currentClip.reversed) {
                        currentClip.timelineStart + (pos - currentClip.trimStart) / currentClip.speed
                    } else {
                        currentClip.timelinePosOf(pos)
                    }.coerceIn(currentClip.timelineStart, currentClip.timelineEnd)
                    onSeek(timelinePos)
                    delay(100)
                }
            }

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
        }

        // 图片水印叠加层（覆盖在视频上，按真实视频宽高比在预览框内 aspect-fit 定位）。
        currentClip.imageWatermarkPath?.let { path ->
            val wmBitmap = remember(path) { BitmapFactory.decodeFile(path) }
            // ExoPlayer 的 onVideoSizeChanged 为最准确来源；未回调时用片段的流宽高兜底。
            val aspect = videoAspect ?: if (currentClip.height > 0) {
                currentClip.width.toFloat() / currentClip.height.toFloat()
            } else {
                null
            }
            wmBitmap?.let { bmp ->
                if (aspect != null && aspect > 0f && bmp.width > 0 && bmp.height > 0) {
                    val scale = currentClip.imageWatermarkScale.toFloat()
                    val opacity = currentClip.imageWatermarkOpacity.toFloat().coerceIn(0f, 1f)
                    val position = currentClip.imageWatermarkPosition

                    // 预览框尺寸（dp）
                    val pw = maxWidth.value
                    val ph = maxHeight.value
                    val previewAspect = pw / ph

                    // aspect-fit：视频在预览框内的实际宽高与左上角（dp）
                    val videoW: Float
                    val videoH: Float
                    if (aspect > previewAspect) {
                        videoW = pw
                        videoH = pw / aspect
                    } else {
                        videoH = ph
                        videoW = ph * aspect
                    }
                    val videoLeft = (pw - videoW) / 2f
                    val videoTop = (ph - videoH) / 2f

                    val wmWidthDp = (videoW * scale).coerceAtLeast(1f)
                    val wmHeightDp = wmWidthDp * (bmp.height.toFloat() / bmp.width.toFloat())
                    val padDp = (videoW * 0.01f).coerceAtLeast(2f)

                    val xDp: Float
                    val yDp: Float
                    when (position) {
                        "top-left" -> {
                            xDp = videoLeft + padDp
                            yDp = videoTop + padDp
                        }
                        "top-right" -> {
                            xDp = videoLeft + videoW - wmWidthDp - padDp
                            yDp = videoTop + padDp
                        }
                        "bottom-left" -> {
                            xDp = videoLeft + padDp
                            yDp = videoTop + videoH - wmHeightDp - padDp
                        }
                        "center" -> {
                            xDp = videoLeft + (videoW - wmWidthDp) / 2f
                            yDp = videoTop + (videoH - wmHeightDp) / 2f
                        }
                        else -> {
                            xDp = videoLeft + videoW - wmWidthDp - padDp
                            yDp = videoTop + videoH - wmHeightDp - padDp
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            alpha = opacity,
                            modifier = Modifier
                                .offset(
                                    x = xDp.dp,
                                    y = yDp.dp
                                )
                                .size(
                                    width = wmWidthDp.dp,
                                    height = wmHeightDp.dp
                                )
                        )
                    }
                }
            }
        }

        // 画中画叠加层预览（静态帧：视频显示缩略图、图片显示原图；导出时才是真实动态叠加）
        val pipClips = state.project.tracks
            .filter { it.type == TrackType.PICTURE }
            .flatMap { it.clips }
            .filter {
                it.pipEnabled &&
                    state.currentPosition >= it.timelineStart - 0.05 &&
                    state.currentPosition < it.timelineEnd - 0.05
            }
        if (pipClips.isNotEmpty()) {
            val aspect = videoAspect ?: if (currentClip.height > 0) {
                currentClip.width.toFloat() / currentClip.height.toFloat()
            } else null
            if (aspect != null && aspect > 0f) {
                val pw = maxWidth.value
                val ph = maxHeight.value
                val previewAspect = pw / ph
                val videoW: Float
                val videoH: Float
                if (aspect > previewAspect) {
                    videoW = pw
                    videoH = pw / aspect
                } else {
                    videoH = ph
                    videoW = ph * aspect
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
                            val xDp = videoLeft + (videoW - pipW) * pip.pipX.toFloat().coerceIn(0f, 1f)
                            val yDp = videoTop + (videoH - pipH) * pip.pipY.toFloat().coerceIn(0f, 1f)
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
                                    } else {
                                        Modifier
                                    }
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
        }

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

/** 量化到 0.01，减少滑块拖动时播放器重建次数 */
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
