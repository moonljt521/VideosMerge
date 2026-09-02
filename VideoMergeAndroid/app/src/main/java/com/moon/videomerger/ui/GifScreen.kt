package com.moon.videomerger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.gif.GifCommandBuilder
import com.moon.videomerger.gif.GifQuality
import com.moon.videomerger.gif.GifUiState
import com.moon.videomerger.gif.GifViewModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * 视频转 GIF 页面：选择视频 → 设置宽度/帧率/质量 → 转换（进度） → 动画预览 → 保存到相册。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifScreen(
    viewModel: GifViewModel = viewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onVideoPicked(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频转GIF", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            val source = uiState.sourceFile
            if (source == null) {
                EmptyPickSection(onPick = { videoPicker.launch("video/*") })
            } else {
                SourceInfoSection(
                    state = uiState,
                    onRePick = { videoPicker.launch("video/*") }
                )
                Spacer(Modifier.height(16.dp))

                OptionsSection(
                    state = uiState,
                    enabled = !uiState.isConverting,
                    onWidth = viewModel::updateTargetWidth,
                    onFps = viewModel::updateFps,
                    onQuality = viewModel::updateQuality,
                    onLoop = viewModel::updateLoop
                )
                Spacer(Modifier.height(16.dp))

                uiState.resultFile?.let { file ->
                    ResultSection(
                        file = file,
                        state = uiState,
                        onSave = viewModel::saveResult,
                        onClear = viewModel::clearResult
                    )
                } ?: run {
                    ConvertSection(
                        state = uiState,
                        onConvert = viewModel::convert,
                        onCancel = viewModel::cancelConvert
                    )
                }
            }

            uiState.errorMessage?.let { msg ->
                Spacer(Modifier.height(16.dp))
                ErrorSection(message = msg, onDismiss = viewModel::dismissError)
            }

            Spacer(Modifier.height(16.dp))
            HintSection()
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────
// 1. 空态：选择视频
// ──────────────────────────────────────────────────────────

@Composable
private fun EmptyPickSection(onPick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp)),
        onClick = onPick
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.VideoFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("点击选择视频", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "从相册选择要转换为 GIF 的视频",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ──────────────────────────────────────────────────────────
// 2. 源视频信息
// ──────────────────────────────────────────────────────────

@Composable
private fun SourceInfoSection(state: GifUiState, onRePick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VideoFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.sourceName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    "%.1f 秒 · %d×%d".format(
                        Locale.US, state.sourceDuration, state.sourceWidth, state.sourceHeight
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRePick, enabled = !state.isConverting) {
                Text("重新选择", fontSize = 13.sp)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 3. 参数设置
// ──────────────────────────────────────────────────────────

@Composable
private fun OptionsSection(
    state: GifUiState,
    enabled: Boolean,
    onWidth: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onQuality: (GifQuality) -> Unit,
    onLoop: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("参数设置", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))

            ChipRow(
                label = "宽度",
                options = listOf(240, 360, 480, 720),
                selected = state.targetWidth,
                enabled = enabled,
                optionLabel = { "${it}px" },
                onSelect = onWidth
            )
            Spacer(Modifier.height(10.dp))
            ChipRow(
                label = "帧率",
                options = listOf(8, 10, 15),
                selected = state.fps,
                enabled = enabled,
                optionLabel = { "${it}fps" },
                onSelect = onFps
            )
            Spacer(Modifier.height(10.dp))
            ChipRow(
                label = "质量",
                options = GifQuality.values().toList(),
                selected = state.quality,
                enabled = enabled,
                optionLabel = { it.label },
                onSelect = onQuality
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("循环播放", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.loop, onCheckedChange = onLoop, enabled = enabled)
            }
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(optionLabel(option)) }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 4. 转换按钮 / 进度
// ──────────────────────────────────────────────────────────

@Composable
private fun ConvertSection(
    state: GifUiState,
    onConvert: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (state.isConverting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("转换中...", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${(state.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            } else {
                Button(
                    onClick = onConvert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Animation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("生成 GIF", fontSize = 16.sp)
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 5. 结果：动画预览 + 保存
// ──────────────────────────────────────────────────────────

@Composable
private fun ResultSection(
    file: File,
    state: GifUiState,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    val (gifW, gifH) = GifCommandBuilder.targetDimensions(
        state.sourceWidth, state.sourceHeight, state.targetWidth
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "GIF 生成成功",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "清除", tint = Color(0xFF999999))
                }
            }

            Text(
                "${gifW}×${gifH} · ${state.fps}fps · ${formatSize(file)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            GifPreview(
                file = file,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(Modifier.height(12.dp))
            when {
                state.isSaving -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("保存中...", color = Color(0xFF4CAF50))
                    }
                }
                state.isSaved -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已保存到相册 Pictures/VideoMerger/",
                            color = Color(0xFF4CAF50),
                            fontSize = 13.sp
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存到相册")
                    }
                }
            }
        }
    }
}

/**
 * GIF 动画预览。用平台自带的 android.graphics.Movie 解码播放，
 * 全 API 级别行为一致（Coil 不带 coil-gif 时只会显示静态首帧）。
 */
@Suppress("DEPRECATION")
@Composable
private fun GifPreview(file: File, modifier: Modifier = Modifier) {
    val movie = remember(file.absolutePath) {
        try {
            BufferedInputStream(FileInputStream(file), 64 * 1024).use { Movie.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
    if (movie == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Image,
                contentDescription = "GIF 预览不可用",
                tint = Color(0xFF777777),
                modifier = Modifier.size(40.dp)
            )
        }
    } else {
        AndroidView(
            modifier = modifier,
            factory = { context -> GifMovieView(context, movie) }
        )
    }
}

@Suppress("DEPRECATION")
private class GifMovieView(context: Context, private val movie: Movie) : android.view.View(context) {
    private val startTime = System.currentTimeMillis()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || movie.width() <= 0 || movie.height() <= 0) return
        val scale = minOf(vw / movie.width(), vh / movie.height())
        val dx = (vw - movie.width() * scale) / 2f
        val dy = (vh - movie.height() * scale) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        val duration = movie.duration()
        if (duration > 0) {
            movie.setTime(((System.currentTimeMillis() - startTime) % duration).toInt())
        }
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
        if (duration > 0) postInvalidateOnAnimation()
    }
}

private fun formatSize(file: File): String {
    val kb = file.length() / 1024.0
    return if (kb >= 1024) "%.1fMB".format(Locale.US, kb / 1024) else "%.0fKB".format(Locale.US, kb)
}

// ──────────────────────────────────────────────────────────
// 6. 错误 / 提示
// ──────────────────────────────────────────────────────────

@Composable
private fun ErrorSection(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1215))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                message,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = Color(0xFFE57373)
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("确定") }
        }
    }
}

@Composable
private fun HintSection() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("小贴士", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            listOf(
                "• 宽度越小、帧率越低，GIF 体积越小、生成越快",
                "• 「高质量」使用 Bayer 抖动，色彩过渡更平滑",
                "• GIF 不含声音，太长的视频建议先在编辑器里裁剪"
            ).forEach { tip ->
                Text(
                    tip,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
