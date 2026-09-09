package com.moon.videomerger.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.merger.MergeOptions
import com.moon.videomerger.merger.MergeType
import com.moon.videomerger.util.MediaUtils
import com.moon.videomerger.util.VideoHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    initialUris: List<Uri> = emptyList(),
    onBack: () -> Unit = {},
    viewModel: MergeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // ★ 首页“视频合并”入口带入的选片结果:进页即填充,免去二次选择。
    //   放在早退分支之前——全屏播放/历史页切换时不会离开组合而重复触发;
    //   仅在列表内容变化时重新执行,不会覆盖用户后续操作。
    LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty()) {
            viewModel.onVideosSelected(initialUris)
        }
    }

    // 视频选择器
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.onVideosSelected(uris)
        }
    }

    // 全屏播放
    if (uiState.isFullscreen && uiState.fullscreenVideoPath != null) {
        BackHandler { viewModel.closeFullscreen() }
        FullscreenVideoPlayer(
            videoPath = uiState.fullscreenVideoPath!!,
            onSaveClick = {
                viewModel.closeFullscreen()
                viewModel.saveResult()
            },
            onDismiss = { viewModel.closeFullscreen() }
        )
        return
    }

    // 历史记录页面
    if (uiState.showHistoryDialog) {
        BackHandler { viewModel.hideHistory() }
        HistoryPage(
            history = uiState.history,
            onItemClick = { entry ->
                viewModel.openFullscreen(entry.filePath)
            },
            onItemDelete = { entry -> viewModel.deleteHistoryEntry(entry) },
            onBack = { viewModel.hideHistory() }
        )
        return
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频合并", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showHistory() }) {
                        Icon(Icons.Default.History, contentDescription = "历史记录")
                    }
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "清除操作")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
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
            Spacer(modifier = Modifier.height(16.dp))

            // ── 1. 选择视频 + 缩略图 ──
            VideoSelectionSection(
                videoUris = uiState.selectedVideos,
                onPickClick = { videoPickerLauncher.launch("video/*") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 2. 合并模式选择 ──
            MergeTypeSection(
                selectedType = uiState.mergeType,
                onTypeSelected = { viewModel.onMergeTypeSelected(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 3. 选项设置 ──
            OptionsSection(
                mergeType = uiState.mergeType,
                options = uiState.options,
                onOptionsChanged = { viewModel.updateOptions(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 3.5 布局预览（合并前即可看到最终排布） ──
            // 已有合并结果时让位给真正的视频预览，避免两个预览区重复。
            if (uiState.mergeResult == null) {
                MergeLayoutPreviewSection(
                    videoUris = uiState.selectedVideos,
                    mergeType = uiState.mergeType,
                    options = uiState.options,
                    onShuffle = { viewModel.shufflePhotoWall() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 4. 合并按钮 ──
            Button(
                onClick = { viewModel.startMerge() },
                enabled = !uiState.isProcessing && uiState.selectedVideos.size >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.VideoCall, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始合并", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 5. 进度 + 滚动日志 ──
            if (uiState.isProcessing) {
                ProgressAndLogSection(
                    progress = uiState.progress,
                    message = uiState.statusMessage,
                    logText = uiState.logLines
                )
            }

            // ── 6. 预览合并结果 ──
            uiState.mergeResult?.let { result ->
                PreviewSection(
                    videoPath = result.outputFile.absolutePath,
                    mergeType = result.mergeType,
                    width = result.width,
                    height = result.height,
                    duration = result.duration,
                    isSaved = uiState.isSaved,
                    isSaving = uiState.isSaving,
                    onTap = { viewModel.openFullscreen() },
                    onLongPress = { viewModel.saveResult() },
                    onSaveClick = { viewModel.saveResult() },
                    onClear = { viewModel.clearResult() }
                )
            }

            // ── 7. 错误 ──
            uiState.errorMessage?.let { msg ->
                ErrorSection(message = msg) { viewModel.dismissError() }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────
// 视频缩略图
// ──────────────────────────────────────────────────────────

@Composable
fun VideoThumbnailFromUri(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            MediaUtils.loadThumbnailFromUri(context, uri)
        }
    }

    thumbnail?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } ?: run {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.VideoFile, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
fun VideoThumbnailFromFile(
    path: String,
    modifier: Modifier = Modifier
) {
    val thumbnail by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            MediaUtils.loadImageFromFile(path) ?: MediaUtils.loadThumbnailFromFile(path)
        }
    }

    thumbnail?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } ?: run {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.VideoFile, contentDescription = null, tint = Color.Gray)
        }
    }
}

// ──────────────────────────────────────────────────────────
// 1. 视频选择区域
// ──────────────────────────────────────────────────────────

@Composable
private fun VideoSelectionSection(
    videoUris: List<Uri>,
    onPickClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.VideoLibrary,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已选择 ${videoUris.size} 个视频",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))

            // 缩略图列表
            if (videoUris.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(videoUris) { uri ->
                        Box(
                            modifier = Modifier
                                .size(width = 100.dp, height = 160.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            VideoThumbnailFromUri(
                                uri = uri,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = onPickClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AddCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("从相册选择视频")
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 2. 合并模式
// ──────────────────────────────────────────────────────────

@Composable
private fun MergeTypeSection(
    selectedType: MergeType,
    onTypeSelected: (MergeType) -> Unit
) {
    val types = listOf(
        Triple(MergeType.GRID, "网格拼贴", "均匀网格布局"),
        Triple(MergeType.COLLAGE, "画中画", "一主多副布局"),
        Triple(MergeType.PHOTO_WALL, "照片墙", "错落有致+边框")
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "合并模式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.forEach { (type, title, subtitle) ->
                    MergeTypeCard(
                        title = title,
                        subtitle = subtitle,
                        isSelected = selectedType == type,
                        onClick = { onTypeSelected(type) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MergeTypeCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(2.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ──────────────────────────────────────────────────────────
// 3. 参数设置
// ──────────────────────────────────────────────────────────

@Composable
private fun OptionsSection(
    mergeType: MergeType,
    options: MergeOptions,
    onOptionsChanged: (MergeOptions) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "参数设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            // 画布尺寸
            var canvasExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { canvasExpanded = !canvasExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (canvasExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("画布尺寸: ${options.canvasWidth}×${options.canvasHeight}")
            }
            if (canvasExpanded) {
                Spacer(Modifier.height(8.dp))
                val presets = listOf(
                    "1920×1080" to MergeOptions(canvasWidth = 1920, canvasHeight = 1080),
                    "1080×1920" to MergeOptions(canvasWidth = 1080, canvasHeight = 1920),
                    "1080×1080" to MergeOptions(canvasWidth = 1080, canvasHeight = 1080),
                    "3840×2160" to MergeOptions(canvasWidth = 3840, canvasHeight = 2160)
                )
                presets.forEach { (label, preset) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOptionsChanged(options.copy(
                                canvasWidth = preset.canvasWidth,
                                canvasHeight = preset.canvasHeight
                            ))
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = options.canvasWidth == preset.canvasWidth &&
                                       options.canvasHeight == preset.canvasHeight,
                            onClick = {
                                onOptionsChanged(options.copy(
                                    canvasWidth = preset.canvasWidth,
                                    canvasHeight = preset.canvasHeight
                                ))
                            }
                        )
                        Text(label)
                    }
                }
            }

            // Collage 特有选项
            if (mergeType == MergeType.COLLAGE) {
                Spacer(Modifier.height(12.dp))
                Text("主窗口位置:", fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("left" to "左", "right" to "右", "top" to "上", "bottom" to "下").forEach { (value, label) ->
                        FilterChip(
                            selected = options.collageOrient == value,
                            onClick = { onOptionsChanged(options.copy(collageOrient = value)) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("主窗口占比: ${(options.collageMainRatio * 100).toInt()}%")
                Slider(
                    value = options.collageMainRatio.toFloat(),
                    onValueChange = { onOptionsChanged(options.copy(collageMainRatio = it.toDouble())) },
                    valueRange = 0.3f..0.8f
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 5. 进度 + 滚动日志
// ──────────────────────────────────────────────────────────

@Composable
private fun ProgressAndLogSection(
    progress: Float,
    message: String,
    logText: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 进度条
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // 滚动日志
            if (logText.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "FFmpeg 日志",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                val logScrollState = rememberScrollState()
                LaunchedEffect(logText) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .verticalScroll(logScrollState)
                        .padding(8.dp)
                ) {
                    Text(
                        text = logText,
                        color = Color(0xFF4CAF50),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 6. 预览合并结果
// ──────────────────────────────────────────────────────────

@Composable
private fun PreviewSection(
    videoPath: String,
    mergeType: String,
    width: Int,
    height: Int,
    duration: Double,
    isSaved: Boolean,
    isSaving: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSaveClick: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE8F5E9)
        )
    ) {
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
                    "合并完成！预览视频",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "清除", tint = Color(0xFF558B2F))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "$mergeType · ${width}×${height} · ${"%.1f".format(duration)}s",
                fontSize = 12.sp,
                color = Color(0xFF558B2F)
            )

            Spacer(Modifier.height(12.dp))

            // 内联视频播放器
            InlineVideoPlayer(
                videoPath = videoPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                onTap = onTap,
                onLongPress = onLongPress
            )

            Spacer(Modifier.height(12.dp))

            // 保存按钮 / 状态
            when {
                isSaving -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("保存中...", color = Color(0xFF558B2F))
                    }
                }
                isSaved -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("已保存到相册 Movies/VideoMerger/", color = Color(0xFF558B2F), fontSize = 13.sp)
                    }
                }
                else -> {
                    Button(
                        onClick = onSaveClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存到相册")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "提示: 长按视频也可保存 · 点击视频全屏播放",
                        fontSize = 11.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 8. 历史记录
// ──────────────────────────────────────────────────────────

@Composable
private fun HistorySection(
    history: List<VideoHistoryStore.HistoryEntry>,
    onItemClick: (VideoHistoryStore.HistoryEntry) -> Unit,
    onItemDelete: (VideoHistoryStore.HistoryEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "历史记录 (${history.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(history) { entry ->
                    HistoryItem(
                        entry = entry,
                        onClick = { onItemClick(entry) },
                        onDelete = { onItemDelete(entry) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    entry: VideoHistoryStore.HistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val dateStr = dateFormat.format(Date(entry.timestamp))
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除记录") },
            text = { Text("确定删除这条历史记录？\n视频文件也会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("删除", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(0.6f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteDialog = true }
            )
    ) {
        VideoThumbnailFromFile(
            path = entry.thumbnailPath,
            modifier = Modifier.fillMaxSize()
        )

        // 底部信息
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(6.dp)
        ) {
            Text(
                text = when (entry.mergeType) {
                    "GRID" -> "网格拼贴"
                    "COLLAGE" -> "画中画"
                    "PHOTO_WALL" -> "照片墙"
                    else -> entry.mergeType
                },
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dateStr,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
            Text(
                text = "${"%.1f".format(entry.duration)}s",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
        }

    }
}

// ──────────────────────────────────────────────────────────
// 错误
// ──────────────────────────────────────────────────────────

@Composable
private fun ErrorSection(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFEBEE)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = Color(0xFFF44336),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "出错了",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFFC62828)
            )
            Text(
                message,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = Color(0xFFB71C1C)
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 历史记录页面
// ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryPage(
    history: List<VideoHistoryStore.HistoryEntry>,
    onItemClick: (VideoHistoryStore.HistoryEntry) -> Unit,
    onItemDelete: (VideoHistoryStore.HistoryEntry) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史记录 (${history.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("暂无历史记录", color = Color.Gray, fontSize = 16.sp)
                }
            }
        } else {
            val rows = (history.size + 1) / 2
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                for (r in 0 until rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (c in 0..1) {
                            val idx = r * 2 + c
                            if (idx < history.size) {
                                HistoryItem(
                                    entry = history[idx],
                                    onClick = { onItemClick(history[idx]) },
                                    onDelete = { onItemDelete(history[idx]) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
