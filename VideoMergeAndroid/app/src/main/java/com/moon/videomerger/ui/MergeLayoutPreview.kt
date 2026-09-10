package com.moon.videomerger.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.videomerger.merger.LayoutRect
import com.moon.videomerger.merger.MergeLayout
import com.moon.videomerger.merger.MergeOptions
import com.moon.videomerger.merger.MergeType
import com.moon.videomerger.merger.toAligned16
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 合并布局预览 —— 合并前就能看到最终排布。
 *
 * 布局矩形直接来自 [MergeLayout]（与三个 Merger 共用同一套算法），
 * 所以预览与导出结果一致，而不是「示意性」的假预览。
 */

/** 一个待渲染的窗口 */
private data class PreviewCell(
    val uri: Uri,
    val rect: LayoutRect,
    val rotation: Float
)

/** 预览画布尺寸 + 所有窗口 */
private data class PreviewLayout(
    val outW: Int,
    val outH: Int,
    val cells: List<PreviewCell>
)

@Composable
fun MergeLayoutPreviewSection(
    videoUris: List<Uri>,
    mergeType: MergeType,
    options: MergeOptions,
    onShuffle: () -> Unit,
    onRemove: (Int) -> Unit,
    onPickClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 网格布局需要各视频宽高比（取「主导比例」决定单元格形状）
    val aspects by produceState(initialValue = emptyList<Double>(), videoUris) {
        value = if (videoUris.size >= 2) {
            withContext(Dispatchers.IO) {
                videoUris.map { uri ->
                    MediaUtils.getVideoAspectFromUri(context, uri) ?: (16.0 / 9.0)
                }
            }
        } else {
            emptyList()
        }
    }

    val layout = remember(videoUris, mergeType, options, aspects) {
        buildPreviewLayout(videoUris, mergeType, options, aspects)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "布局预览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (mergeType == MergeType.PHOTO_WALL && videoUris.size >= 2) {
                    TextButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("换一批", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                previewSubtitle(mergeType, layout, videoUris.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (videoUris.isEmpty()) {
                // 空态：占位区直接给主入口，选片不用再去别处找按钮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = onPickClick) {
                            Icon(Icons.Default.AddCircle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("从相册选择视频")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "选好视频后这里实时显示合并布局",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (videoUris.size < 2) {
                // 只有 1 个：给出明确的「还差 1 个」引导
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "已选 1 个，再选 1 个视频即可查看布局预览",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onPickClick) {
                            Icon(Icons.Default.AddCircle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("继续添加")
                        }
                    }
                }
            } else {
                LayoutCanvas(
                    layout = layout,
                    isPhotoWall = mergeType == MergeType.PHOTO_WALL
                )
            }

            // 已选视频缩略图条（紧凑版，末尾「+」继续添加）
            if (videoUris.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(videoUris) { index, uri ->
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 100.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            VideoThumbnailFromUri(
                                uri = uri,
                                modifier = Modifier.fillMaxSize()
                            )
                            // 右上角「−」移除按钮
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { onRemove(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "−",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClick = onPickClick),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AddCircle,
                                contentDescription = "继续添加视频",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "已选 ${videoUris.size} 个视频 · 点 + 添加 · 点 − 移除",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LayoutCanvas(layout: PreviewLayout, isPhotoWall: Boolean) {
    val maxCanvasHeight = 360.dp
    val canvasBg = if (isPhotoWall) Color(0xFF28282D) else Color.Black

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Dp per 输出像素：同时受可用宽度与最大高度约束
        val scale = minOf(
            maxWidth / layout.outW.toFloat(),
            maxCanvasHeight / layout.outH.toFloat()
        )
        val canvasW = scale * layout.outW
        val canvasH = scale * layout.outH

        Box(
            modifier = Modifier
                .size(canvasW, canvasH)
                .clip(RoundedCornerShape(8.dp))
                .background(canvasBg)
        ) {
            layout.cells.forEach { cell ->
                Box(
                    modifier = Modifier
                        .offset(x = scale * cell.rect.x, y = scale * cell.rect.y)
                        .size(scale * cell.rect.w, scale * cell.rect.h)
                        .graphicsLayer { rotationZ = cell.rotation }
                        .then(
                            if (isPhotoWall) Modifier.border(1.5.dp, Color.White)
                            else Modifier
                        )
                ) {
                    VideoThumbnailFromUri(
                        uri = cell.uri,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private fun previewSubtitle(mergeType: MergeType, layout: PreviewLayout, count: Int): String {
    if (count < 2) return "合并前先选好视频，预览会实时跟随参数变化"
    val typeName = when (mergeType) {
        MergeType.GRID -> "网格拼贴"
        MergeType.COLLAGE -> "画中画"
        MergeType.PHOTO_WALL -> "照片墙"
    }
    val note = when (mergeType) {
        MergeType.GRID -> " · 按主导比例自适应"
        MergeType.COLLAGE -> " · 主窗口占比可调"
        MergeType.PHOTO_WALL -> " · 随机布局可换一批"
    }
    return "$typeName · 输出 ${layout.outW}×${layout.outH} · $count 个视频$note"
}

private fun buildPreviewLayout(
    uris: List<Uri>,
    mergeType: MergeType,
    options: MergeOptions,
    aspects: List<Double>
): PreviewLayout {
    if (uris.isEmpty()) return PreviewLayout(1, 1, emptyList())

    return when (mergeType) {
        MergeType.GRID -> {
            val safeAspects = aspects.ifEmpty { List(uris.size) { 16.0 / 9.0 } }
            val spec = MergeLayout.gridSpec(uris.size, safeAspects, options.gridCellSize)
            val rects = spec.rects()
            PreviewLayout(
                outW = spec.outW,
                outH = spec.outH,
                cells = uris.mapIndexedNotNull { i, uri ->
                    rects.getOrNull(i)?.let { PreviewCell(uri, it, 0f) }
                }
            )
        }

        MergeType.COLLAGE -> {
            val layout = MergeLayout.collageLayout(
                n = uris.size,
                canvasW = options.canvasWidth.toAligned16(),
                canvasH = options.canvasHeight.toAligned16(),
                mainRatio = options.collageMainRatio,
                orient = options.collageOrient,
                gap = options.gap,
                mainIdx = options.collageMainIndex
            )
            val rects = layout.rects()
            PreviewLayout(
                outW = layout.outW,
                outH = layout.outH,
                cells = uris.mapIndexed { i, uri -> PreviewCell(uri, rects[i], 0f) }
            )
        }

        MergeType.PHOTO_WALL -> {
            val wall = MergeLayout.photoWallLayout(
                n = uris.size,
                canvasW = options.canvasWidth.toAligned16(),
                canvasH = options.canvasHeight.toAligned16(),
                seed = options.photoWallSeed
            )
            PreviewLayout(
                outW = wall.outW,
                outH = wall.outH,
                cells = uris.mapIndexed { i, uri ->
                    PreviewCell(uri, wall.rects[i], wall.rotations[i].toFloat())
                }
            )
        }
    }
}
