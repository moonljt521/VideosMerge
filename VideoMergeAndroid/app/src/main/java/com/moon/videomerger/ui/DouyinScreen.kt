package com.moon.videomerger.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.douyin.DouyinViewModel

/**
 * 抖音去水印页面：粘贴分享文案 → 解析 → 下载 → 预览播放 → 保存到相册 / 系统分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouyinScreen(
    viewModel: DouyinViewModel = viewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showFullscreen by remember { mutableStateOf(false) }

    val shareResult = {
        uiState.resultFile?.let { file ->
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "分享无水印视频"))
        }
        Unit
    }

    // 全屏播放
    if (showFullscreen && uiState.resultFile != null) {
        BackHandler { showFullscreen = false }
        FullscreenVideoPlayer(
            videoPath = uiState.resultFile!!.absolutePath,
            onSaveClick = {
                showFullscreen = false
                viewModel.saveResult()
            },
            onDismiss = { showFullscreen = false }
        )
        return
    }

    // 页面级返回：离开时清空本次会话的输入与结果（系统返回键/手势走这里）
    BackHandler {
        viewModel.resetAll()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("短视频去水印", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.resetAll()
                        onBack()
                    }) {
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
            Spacer(modifier = Modifier.height(16.dp))

            // ── 1. 输入区 ──
            InputSection(
                input = uiState.input,
                isProcessing = uiState.isProcessing,
                onInputChange = { viewModel.updateInput(it) },
                onPaste = {
                    val text = clipboard.getText()?.text ?: ""
                    if (text.isNotBlank()) viewModel.updateInput(text)
                },
                onParse = { viewModel.parseAndDownload() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 2. 进度 ──
            if (uiState.isProcessing) {
                DouyinProgressSection(
                    stage = uiState.stage,
                    progress = uiState.progress
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── 3. 结果预览 ──
            uiState.resultFile?.let { file ->
                DouyinResultSection(
                    title = uiState.title,
                    author = uiState.author,
                    durationMs = uiState.durationMs,
                    videoPath = file.absolutePath,
                    isSaved = uiState.isSaved,
                    isSaving = uiState.isSaving,
                    onTap = { showFullscreen = true },
                    onLongPress = { viewModel.saveResult() },
                    onSaveClick = { viewModel.saveResult() },
                    onShareClick = shareResult,
                    onClear = { viewModel.clearResult() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── 4. 错误 ──
            uiState.errorMessage?.let { msg ->
                DouyinErrorSection(message = msg) { viewModel.dismissError() }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── 5. 使用说明 ──
            UsageHintSection()

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────
// 1. 输入区
// ──────────────────────────────────────────────────────────

@Composable
private fun InputSection(
    input: String,
    isProcessing: Boolean,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onParse: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ContentPaste,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "粘贴视频分享文案",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "在视频 App 里点「分享 → 复制链接」，把整段文案粘贴到下面即可",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                placeholder = {
                    Text(
                        "粘贴 App 内复制的分享文案或视频链接",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                },
                maxLines = 6,
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onPaste, enabled = !isProcessing) {
                    Text("粘贴")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        // 开始解析即收起软键盘,避免挡住下方进度与结果
                        keyboard?.hide()
                        onParse()
                    },
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("解析视频")
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 2. 进度
// ──────────────────────────────────────────────────────────

@Composable
private fun DouyinProgressSection(stage: String, progress: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stage, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (progress >= 0) {
                    Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (progress >= 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 3. 结果预览
// ──────────────────────────────────────────────────────────

@Composable
private fun DouyinResultSection(
    title: String,
    author: String,
    durationMs: Long,
    videoPath: String,
    isSaved: Boolean,
    isSaving: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
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
                    "解析成功 · 无水印",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "清除", tint = Color(0xFF999999))
                }
            }

            if (title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        title,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                buildString {
                    append(author.ifBlank { "短视频" })
                    if (durationMs > 0) append(" · ${durationMs / 1000}s")
                },
                fontSize = 12.sp,
                color = Color(0xFF4CAF50)
            )

            Spacer(Modifier.height(12.dp))

            InlineVideoPlayer(
                videoPath = videoPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                onTap = onTap,
                onLongPress = onLongPress
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isSaving -> {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("保存中...", color = Color(0xFF4CAF50))
                        }
                    }
                    isSaved -> {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "已保存到相册 Movies/VideoMerger/",
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    else -> {
                        Button(
                            onClick = onSaveClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("保存到相册")
                        }
                    }
                }
                OutlinedButton(onClick = onShareClick) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("分享")
                }
            }
            if (!isSaving && !isSaved) {
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

// ──────────────────────────────────────────────────────────
// 4. 错误
// ──────────────────────────────────────────────────────────

@Composable
private fun DouyinErrorSection(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1215))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "解析失败",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFFFF5252)
            )
            Text(
                message,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = Color(0xFFE57373)
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 5. 使用说明
// ──────────────────────────────────────────────────────────

@Composable
private fun UsageHintSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "使用步骤",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            val steps = listOf(
                "1. 打开视频 App，找到想保存的视频",
                "2. 点右侧「分享」→「复制链接」",
                "3. 回到这里粘贴，点「解析视频」",
                "4. 预览无水印视频，确认后保存到相册"
            )
            steps.forEach { step ->
                Text(
                    step,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
