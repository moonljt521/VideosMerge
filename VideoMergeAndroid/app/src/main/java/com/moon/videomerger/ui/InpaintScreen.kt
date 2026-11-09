package com.moon.videomerger.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moon.videomerger.inpaint.InpaintUiState
import com.moon.videomerger.inpaint.InpaintViewModel
import com.moon.videomerger.inpaint.RectPx
import kotlin.math.abs
import kotlin.math.min

/**
 * 视频去水印（内容修复）：选片 → 自动检测/框选 → 探测帧前后对比 → 修复 → 保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InpaintScreen(
    viewModel: InpaintViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onVideoPicked(it) } }
    var showFullscreen by remember { mutableStateOf(false) }

    BackHandler(enabled = showFullscreen) { showFullscreen = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("画面去水印", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            if (state.sourceFile == null) {
                EmptyPickSection(onPick = { videoPicker.launch("video/*") })
            } else {
                SourceSection(state, onPick = { videoPicker.launch("video/*") })
                Spacer(Modifier.height(12.dp))
                PreviewSection(state, viewModel)
                Spacer(Modifier.height(12.dp))
                if (state.resultFile != null) {
                    ResultSection(state, viewModel, onFullscreen = { showFullscreen = true })
                } else {
                    ProcessSection(state, viewModel)
                }
            }
            state.errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                ErrorSection(it, viewModel::dismissError)
            }
            state.hint?.let {
                Spacer(Modifier.height(12.dp))
                HintSection(it)
            }
            Spacer(Modifier.height(12.dp))
            TipsSection()
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showFullscreen) {
        state.resultFile?.let { file ->
            FullscreenVideoPlayer(
                videoPath = file.absolutePath,
                onSaveClick = { showFullscreen = false; viewModel.saveResult() },
                onDismiss = { showFullscreen = false },
            )
        }
    }
}

// ------------------------------------------------------------------ 空态

@Composable
private fun EmptyPickSection(onPick: () -> Unit) {
    Card(
        onClick = onPick,
        modifier = Modifier.fillMaxWidth().height(200.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null,
                tint = Color(0xFFAB47BC), modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text("选择视频开始修复", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("适合水印位置固定、背景较简单的视频", color = Color(0xFF666666), fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ 源信息

@Composable
private fun SourceSection(state: InpaintUiState, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.VideoFile, contentDescription = null, tint = Color(0xFFAB47BC))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(state.sourceName, color = Color.White, fontSize = 14.sp, maxLines = 1)
            state.meta?.let {
                Text(
                    "%.1fs · %d×%d".format(it.duration, it.displayWidth, it.displayHeight),
                    color = Color(0xFF888888), fontSize = 12.sp,
                )
            }
        }
        TextButton(onClick = onPick, enabled = !state.isProcessing) {
            Text("换一个", color = Color(0xFF2196F3), fontSize = 13.sp)
        }
    }
}

// ------------------------------------------------------------------ 预览/框选

@Composable
private fun PreviewSection(state: InpaintUiState, vm: InpaintViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("水印位置", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = vm::autoDetect,
                    enabled = !state.isDetecting && !state.isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    if (state.isDetecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.isDetecting) "检测中…" else "自动检测", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            BBoxCanvas(state, vm)
            Spacer(Modifier.height(8.dp))
            Text("拖动红框移动位置，拖右下角圆点缩放", color = Color(0xFF888888), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                CornerChip("左上") { vm.setBBox(RectF(0.02f, 0.03f, 0.34f, 0.14f)) }
                Spacer(Modifier.width(8.dp))
                CornerChip("右上") { vm.setBBox(RectF(0.66f, 0.03f, 0.98f, 0.14f)) }
                Spacer(Modifier.width(8.dp))
                CornerChip("左下") { vm.setBBox(RectF(0.02f, 0.85f, 0.34f, 0.96f)) }
                Spacer(Modifier.width(8.dp))
                CornerChip("右下") { vm.setBBox(RectF(0.66f, 0.85f, 0.98f, 0.96f)) }
                Spacer(Modifier.weight(1f))
                if (state.isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF888888),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("生成预览…", color = Color(0xFF888888), fontSize = 11.sp)
                } else if (state.hasPreview) {
                    Text("遮罩 ${state.maskPixels} 像素", color = Color(0xFF888888), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToggleChip("修复后", state.showAfter) { vm.toggleAfter() }
                Spacer(Modifier.width(8.dp))
                ToggleChip("遮罩", state.showMask) { vm.toggleMask() }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::refreshPreview, enabled = !state.isPreparing && !state.isProcessing) {
                    Text("刷新预览", color = Color(0xFF2196F3), fontSize = 12.sp)
                }
            }

            val bbox = state.bbox
            NormSlider("水平", bbox.left, onChange = { v ->
                vm.setBBox(RectF(v, bbox.top, min(v + bbox.width(), 0.98f), bbox.bottom), generate = false)
            }, onFinished = vm::refreshPreview)
            NormSlider("垂直", bbox.top, onChange = { v ->
                vm.setBBox(RectF(bbox.left, v, bbox.right, min(v + bbox.height(), 0.98f)), generate = false)
            }, onFinished = vm::refreshPreview)
            NormSlider("宽度", bbox.width(), onChange = { v ->
                vm.setBBox(RectF(bbox.left, bbox.top, min(bbox.left + v, 0.98f), bbox.bottom), generate = false)
            }, onFinished = vm::refreshPreview)
            NormSlider("高度", bbox.height(), onChange = { v ->
                vm.setBBox(RectF(bbox.left, bbox.top, bbox.right, min(bbox.top + v, 0.98f)), generate = false)
            }, onFinished = vm::refreshPreview)

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("探测帧", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.width(54.dp))
                Slider(
                    value = state.probeTime.toFloat(),
                    onValueChange = { vm.setProbeTime(it.toDouble()) },
                    valueRange = 0f..maxOf((state.meta?.duration ?: 1.0).toFloat() - 0.02f, 0.1f),
                    modifier = Modifier.weight(1f),
                )
                Text("%.1f 秒".format(state.probeTime), color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.width(50.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("边缘扩展", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.width(54.dp))
                Slider(
                    value = state.dilate.toFloat(),
                    onValueChange = { vm.setDilate(it.toInt()) },
                    valueRange = 5f..25f,
                    steps = 9,
                    modifier = Modifier.weight(1f),
                )
                Text("${state.dilate}", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(30.dp))
            }
        }
    }
}

@Composable
private fun BBoxCanvas(state: InpaintUiState, vm: InpaintViewModel) {
    val bmp = state.probeBitmap ?: return
    val density = LocalDensity.current
    val handleRadius = with(density) { 11.dp.toPx() }
    val touchRadius = with(density) { 26.dp.toPx() }
    val bboxState = rememberUpdatedState(state.bbox)
    var startBBox by remember { mutableStateOf<RectF?>(null) }
    var startOffset by remember { mutableStateOf(Offset.Zero) }
    var resizing by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(330.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101010))
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val fit = remember(bmp, maxW, maxH) { fitRect(bmp.width, bmp.height, maxW, maxH) }
        val bbox = bboxState.value
        val box = remember(bbox, fit) { boxRect(bbox, fit) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp, fit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            startBBox = bboxState.value
                            startOffset = offset
                            val b = boxRect(bboxState.value, fit)
                            resizing = (offset - Offset(b.right, b.bottom)).getDistance() < touchRadius
                        },
                        onDrag = { change, _ ->
                            val start = startBBox ?: return@detectDragGestures
                            val d = change.position - startOffset
                            val fw = fit.width()
                            val fh = fit.height()
                            if (fw <= 1f || fh <= 1f) return@detectDragGestures
                            val b = RectF(start)
                            if (resizing) {
                                b.right = (start.right + d.x / fw).coerceIn(start.left + 0.02f, 0.98f)
                                b.bottom = (start.bottom + d.y / fh).coerceIn(start.top + 0.01f, 0.98f)
                            } else {
                                val w = start.width()
                                val h = start.height()
                                val left = (start.left + d.x / fw).coerceIn(0f, 0.98f - w)
                                val top = (start.top + d.y / fh).coerceIn(0f, 0.98f - h)
                                b.set(left, top, left + w, top + h)
                            }
                            vm.setBBox(b, generate = false)
                            change.consume()
                        },
                        onDragEnd = {
                            startBBox = null
                            vm.refreshPreview()
                        },
                        onDragCancel = { startBBox = null },
                    )
                }
        ) {
            drawImage(bmp.asImageBitmap(),
                dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
                dstSize = IntSize(fit.width().toInt(), fit.height().toInt()))

            if (state.showAfter) {
                state.afterROI?.let { roi -> state.maskRect?.let { r ->
                    drawRoi(roi.asImageBitmap(), r, bmp.width, bmp.height, fit)
                } }
            }
            if (state.showMask) {
                state.maskROI?.let { roi -> state.maskRect?.let { r ->
                    drawRoi(roi.asImageBitmap(), r, bmp.width, bmp.height, fit)
                } }
            }

            drawRect(
                color = Color(0xFFFF4D4D),
                topLeft = Offset(box.left, box.top),
                size = Size(box.width(), box.height()),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = Color(0xFFFF4D4D),
                radius = handleRadius,
                center = Offset(box.right, box.bottom),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoi(
    image: androidx.compose.ui.graphics.ImageBitmap,
    roi: RectPx,
    imgW: Int,
    imgH: Int,
    fit: android.graphics.RectF,
) {
    val sx = fit.width() / imgW
    val sy = fit.height() / imgH
    drawImage(
        image = image,
        dstOffset = IntOffset((fit.left + roi.x * sx).toInt(), (fit.top + roi.y * sy).toInt()),
        dstSize = IntSize((roi.w * sx).toInt(), (roi.h * sy).toInt()),
    )
}

@Composable
private fun CornerChip(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(label, color = Color(0xFFBBBBBB), fontSize = 11.sp)
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) Color(0xFF2196F3) else Color(0xFF2A2A2A)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(label, color = if (on) Color.White else Color(0xFFBBBBBB), fontSize = 11.sp)
    }
}

@Composable
private fun NormSlider(label: String, value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.width(54.dp))
        Slider(
            value = value.coerceIn(0f, 0.98f),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = 0f..0.98f,
            modifier = Modifier.weight(1f),
        )
        Text("%.2f".format(value), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(40.dp))
    }
}

// ------------------------------------------------------------------ 处理/结果

@Composable
private fun ProcessSection(state: InpaintUiState, vm: InpaintViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (state.isProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.phase, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("${(state.progress * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFAB47BC),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消", color = Color(0xFFCCCCCC))
                }
            } else {
                Button(
                    onClick = vm::startRepair,
                    enabled = state.hasPreview && !state.isPreparing,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFAB47BC),
                        disabledContainerColor = Color(0xFF3A3A3A),
                    ),
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始修复", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ResultSection(state: InpaintUiState, vm: InpaintViewModel, onFullscreen: () -> Unit) {
    val file = state.resultFile ?: return
    val sizeText = if (file.length() >= 1024 * 1024)
        "%.1fMB".format(file.length() / 1024.0 / 1024.0)
    else "%.0fKB".format(file.length() / 1024.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(Modifier.width(8.dp))
                Text("修复完成", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::clearResult) {
                    Text("重新处理", color = Color(0xFF999999), fontSize = 12.sp)
                }
            }
            state.meta?.let {
                Text(
                    "%d×%d · %.1fs · %s".format(it.displayWidth, it.displayHeight, it.duration, sizeText),
                    color = Color(0xFF888888), fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            InlineVideoPlayer(
                videoPath = file.absolutePath,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp)),
                onTap = onFullscreen,
                onLongPress = { vm.saveResult() },
            )
            Spacer(Modifier.height(12.dp))
            when {
                state.isSaving -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = Color(0xFF4CAF50))
                    Spacer(Modifier.width(8.dp))
                    Text("保存中…", color = Color(0xFF4CAF50), fontSize = 13.sp)
                }
                state.isSaved -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                        tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("已保存到相册", color = Color(0xFF4CAF50), fontSize = 13.sp)
                }
                else -> Button(
                    onClick = vm::saveResult,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("保存到相册", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 提示

@Composable
private fun ErrorSection(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2A1215))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color(0xFFE57373), fontSize = 13.sp)
        TextButton(onClick = onDismiss) { Text("知道了", color = Color(0xFFE57373), fontSize = 14.sp) }
    }
}

@Composable
private fun HintSection(message: String) {
    Text(
        message,
        color = Color(0xFFE0A030),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2A2312))
            .padding(12.dp),
    )
}

@Composable
private fun TipsSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(16.dp),
    ) {
        Text("使用提示", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        listOf(
            "适合水印位置固定、背景较简单（墙面 / 桌面 / 虚化背景）的视频。",
            "背景纹理复杂时可能出现涂抹痕迹，请先看预览再处理。",
            "处理会重新编码画面，时长与帧率保持不变；音频与 AI 生成标识等元数据会保留。",
        ).forEach {
            Text(it, color = Color(0xFF888888), fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ 几何

private fun fitRect(imgW: Int, imgH: Int, maxW: Float, maxH: Float): android.graphics.RectF {
    if (imgW <= 0 || imgH <= 0 || maxW <= 0 || maxH <= 0) return android.graphics.RectF()
    val scale = min(maxW / imgW, maxH / imgH)
    val w = imgW * scale
    val h = imgH * scale
    val left = (maxW - w) / 2f
    val top = (maxH - h) / 2f
    return android.graphics.RectF(left, top, left + w, top + h)
}

private fun boxRect(bbox: RectF, fit: android.graphics.RectF): android.graphics.RectF {
    val left = fit.left + bbox.left * fit.width()
    val top = fit.top + bbox.top * fit.height()
    return android.graphics.RectF(
        left, top,
        left + bbox.width() * fit.width(),
        top + bbox.height() * fit.height(),
    )
}
