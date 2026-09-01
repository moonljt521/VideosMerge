package com.moon.videomerger.ui

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 把播放绑定到 Activity 生命周期：App 退后台时暂停，回前台时恢复
 * （仅当退后台前处于播放状态；用户手动暂停过的不会自动恢复）。
 */
@Composable
private fun ExoPlayer.bindToLifecycle(activity: ComponentActivity?) {
    DisposableEffect(activity) {
        val observer = activity?.let { act ->
            object : DefaultLifecycleObserver {
                private var wasPlaying = false

                override fun onStop(owner: LifecycleOwner) {
                    wasPlaying = playWhenReady
                    pause()
                }

                override fun onStart(owner: LifecycleOwner) {
                    if (wasPlaying) playWhenReady = true
                }
            }.also { act.lifecycle.addObserver(it) }
        }
        onDispose {
            if (observer != null && activity != null) activity.lifecycle.removeObserver(observer)
        }
    }
}

/**
 * 内联视频播放器（小窗口预览）。
 * 短按全屏播放，长按触发保存。
 */
@Composable
fun InlineVideoPlayer(
    videoPath: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoPath))
            prepare()
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
        }
    }
    exoPlayer.bindToLifecycle(context as? ComponentActivity)

    DisposableEffect(videoPath) {
        onDispose { exoPlayer.release() }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    this.player = exoPlayer
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        // 提示文字
        Text(
            text = "点击全屏播放 · 长按保存到相册",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * 全屏视频播放器对话框。
 * 右上角有保存按钮。
 */
@Composable
fun FullscreenVideoPlayer(
    videoPath: String,
    onSaveClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoPath))
            prepare()
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
        }
    }
    exoPlayer.bindToLifecycle(context as? ComponentActivity)

    DisposableEffect(videoPath) {
        onDispose { exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                    this.player = exoPlayer
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        // 右上角保存按钮
        IconButton(
            onClick = onSaveClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
        ) {
            Icon(
                Icons.Default.Save,
                contentDescription = "保存到相册",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // 左上角关闭按钮
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
