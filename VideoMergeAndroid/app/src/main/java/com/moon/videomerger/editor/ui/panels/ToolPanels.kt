package com.moon.videomerger.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.videomerger.editor.data.Clip
import com.moon.videomerger.editor.data.EditorUiState
import com.moon.videomerger.editor.data.FilterPreset
import com.moon.videomerger.editor.data.TransitionEffect

// ═══════════════════════════════════════
//  裁剪面板（含旋转/翻转 + 播放头分割 + 删除）
// ═══════════════════════════════════════

@Composable
fun TrimPanel(
    clip: Clip,
    currentPosition: Double,
    onTrimChange: (Double, Double) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDelete: () -> Unit,
    onRotation: (Int) -> Unit,
    onHFlip: () -> Unit,
    onVFlip: () -> Unit,
    onDetectLogo: () -> Unit,
    onDetectLogoAll: () -> Unit,
    isDetectingLogo: Boolean,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var trimStart by remember(clip.id) { mutableStateOf(clip.trimStart.toFloat()) }
    var trimEnd by remember(clip.id) { mutableStateOf(
        (if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration).toFloat()
    ) }

    // 播放头是否在当前片段内（决定分割按钮可用性）
    val playheadInside = currentPosition > clip.timelineStart + 0.05 &&
        currentPosition < clip.timelineEnd - 0.05

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("剪辑", onClose)

        Text(
            "片段：${clip.mediaName}",
            color = Color(0xFF888888),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        SliderRow(
            label = "入点",
            value = trimStart,
            range = 0f..clip.mediaDuration.toFloat(),
            onValueChange = {
                trimStart = it.coerceAtMost(trimEnd - 0.1f)
                onTrimChange(trimStart.toDouble(), trimEnd.toDouble())
            },
            onValueChangeStarted = onEditStart
        )

        SliderRow(
            label = "出点",
            value = trimEnd,
            range = 0f..clip.mediaDuration.toFloat(),
            onValueChange = {
                trimEnd = it.coerceAtLeast(trimStart + 0.1f)
                onTrimChange(trimStart.toDouble(), trimEnd.toDouble())
            },
            onValueChangeStarted = onEditStart
        )

        // ── 旋转 / 翻转（对应 rotate_video.py，引擎已支持，这里接入 UI）──
        Text("旋转 / 翻转", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val rotateItems = listOf(
                Triple("逆时针90°", -90, clip.rotation == -90),
                Triple("顺时针90°", 90, clip.rotation == 90),
                Triple("180°", 180, clip.rotation == 180),
            )
            items(rotateItems) { item ->
                FilterChip(
                    selected = item.third,
                    onClick = {
                        onEditStart()
                        // 再次点击同一角度 → 恢复 0°
                        onRotation(if (item.third) 0 else item.second)
                    },
                    label = { Text(item.first, fontSize = 11.sp) }
                )
            }
            item {
                FilterChip(
                    selected = clip.hflip,
                    onClick = { onEditStart(); onHFlip() },
                    label = { Text("水平镜像", fontSize = 11.sp) }
                )
            }
            item {
                FilterChip(
                    selected = clip.vflip,
                    onClick = { onEditStart(); onVFlip() },
                    label = { Text("垂直镜像", fontSize = 11.sp) }
                )
            }
        }

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("有效时长：${String.format("%.2f", trimEnd - trimStart)}s",
                color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            // 在播放头处分割（剪映式）
            Button(
                onClick = onSplitAtPlayhead,
                enabled = playheadInside,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("分割", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text("删除", fontSize = 12.sp)
            }
        }

        // ── 片尾静止 logo/标语检测截断 ──
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("片尾静止片段", color = Color.White, fontSize = 14.sp)
                Text("检测并去掉视频末尾静止的 logo/标语画面",
                    color = Color(0xFF666666), fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onDetectLogo,
                enabled = !isDetectingLogo,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("当前片段", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onDetectLogoAll,
                enabled = !isDetectingLogo,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(if (isDetectingLogo) "检测中..." else "全部片段", fontSize = 12.sp)
            }
        }

        if (!playheadInside) {
            Text(
                "将播放头移到当前片段内可分割",
                color = Color(0xFF666666), fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
            )
        }
    }
}

// ═══════════════════════════════════════
//  变速面板
// ═══════════════════════════════════════

@Composable
fun SpeedPanel(
    clip: Clip,
    onSpeedChange: (Double) -> Unit,
    onReverseToggle: () -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var speed by remember(clip.id) { mutableStateOf(clip.speed.toFloat()) }

    val presets = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("变速", onClose)

        // 预设
        LazyRow(
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presets) { preset ->
                FilterChip(
                    selected = speed == preset,
                    onClick = {
                        onEditStart()
                        speed = preset
                        onSpeedChange(preset.toDouble())
                    },
                    label = { Text("${preset}x") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2196F3),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // 精确滑块
        SliderRow(
            label = "倍速",
            value = speed,
            range = 0.25f..4.0f,
            onValueChange = {
                speed = it
                onSpeedChange(it.toDouble())
            },
            onValueChangeStarted = onEditStart
        )

        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "原时长: ${String.format("%.2f", clip.effectiveDuration)}s → 变速后: ${String.format("%.2f", clip.effectiveDuration / speed)}s",
                color = Color(0xFFAAAAAA), fontSize = 12.sp
            )
        }

        // ── 倒放（对应 reverse_video.py）──
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("倒放", color = Color.White, fontSize = 14.sp)
                Text("片段倒序播放，较长片段导出时占用内存更高",
                    color = Color(0xFF666666), fontSize = 11.sp)
            }
            Switch(
                checked = clip.reversed,
                onCheckedChange = {
                    onEditStart()
                    onReverseToggle()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3))
            )
        }
    }
}

// ═══════════════════════════════════════
//  滤镜面板
// ═══════════════════════════════════════

@Composable
fun FilterPanel(
    clip: Clip,
    onFilterSelect: (FilterPreset) -> Unit,
    onColorChange: (Double, Double, Double) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var brightness by remember(clip.id) { mutableStateOf(clip.brightness.toFloat()) }
    var contrast by remember(clip.id) { mutableStateOf(clip.contrast.toFloat()) }
    var saturation by remember(clip.id) { mutableStateOf(clip.saturation.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("滤镜", onClose)

        // 预设滤镜
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(FilterPreset.entries.toList()) { preset ->
                FilterChip(
                    selected = clip.filterPreset == preset,
                    onClick = {
                        onEditStart()
                        onFilterSelect(preset)
                    },
                    label = { Text(preset.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2196F3),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // 手动调色
        SliderRow("亮度", brightness, -1f..1f, onValueChange = {
            brightness = it
            onColorChange(brightness.toDouble(), contrast.toDouble(), saturation.toDouble())
        }, onValueChangeStarted = onEditStart)
        SliderRow("对比度", contrast, -1f..1f, onValueChange = {
            contrast = it
            onColorChange(brightness.toDouble(), contrast.toDouble(), saturation.toDouble())
        }, onValueChangeStarted = onEditStart)
        SliderRow("饱和度", saturation, -1f..1f, onValueChange = {
            saturation = it
            onColorChange(brightness.toDouble(), contrast.toDouble(), saturation.toDouble())
        }, onValueChangeStarted = onEditStart)
    }
}

// ═══════════════════════════════════════
//  文字面板
// ═══════════════════════════════════════

@Composable
fun TextPanel(
    clip: Clip,
    onTextChange: (String?) -> Unit,
    onTextStyleChange: (size: Int, color: String, position: String, opacity: Float, border: Boolean) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var text by remember(clip.id) { mutableStateOf(clip.textOverlay ?: "") }
    var size by remember(clip.id) { mutableStateOf(clip.textSize.toFloat()) }
    var color by remember(clip.id) { mutableStateOf(clip.textColor) }
    var position by remember(clip.id) { mutableStateOf(clip.textPosition) }
    var opacity by remember(clip.id) { mutableStateOf(clip.textOpacity) }
    var border by remember(clip.id) { mutableStateOf(clip.textBorder) }

    val colors_list = listOf("white", "yellow", "red", "cyan", "black", "green")
    val positions = listOf("top-left", "top-right", "bottom-left", "bottom-right", "center")
    val positionNames = mapOf(
        "top-left" to "左上", "top-right" to "右上",
        "bottom-left" to "左下", "bottom-right" to "右下",
        "center" to "居中"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("文字水印", onClose)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onTextChange(if (it.isBlank()) null else it)
            },
            label = { Text("输入文字内容", color = Color(0xFF888888)) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = Color(0xFF2196F3),
                cursorColor = Color(0xFF2196F3)
            )
        )

        Spacer(Modifier.height(12.dp))

        // 字号
        SliderRow("字号", size, 12f..120f, onValueChange = {
            size = it
            onTextStyleChange(size.toInt(), color, position, opacity, border)
        }, onValueChangeStarted = onEditStart)

        // 透明度
        SliderRow("透明度", opacity, 0f..1f, onValueChange = {
            opacity = it
            onTextStyleChange(size.toInt(), color, position, opacity, border)
        }, onValueChangeStarted = onEditStart)

        // 颜色选择
        Text("颜色", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(colors_list) { c ->
                val isSelected = color == c
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            when (c) {
                                "white" -> Color.White
                                "yellow" -> Color.Yellow
                                "red" -> Color.Red
                                "cyan" -> Color.Cyan
                                "black" -> Color.Black
                                "green" -> Color.Green
                                else -> Color.White
                            }
                        )
                        .then(
                            if (isSelected) Modifier.padding(2.dp)
                            else Modifier
                        )
                        .clickable {
                            onEditStart()
                            color = c
                            onTextStyleChange(size.toInt(), color, position, opacity, border)
                        }
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = if (c == "white" || c == "yellow" || c == "cyan") Color.Black else Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // 位置选择
        Text("位置", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(positions) { p ->
                val isSelected = position == p
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        onEditStart()
                        position = p
                        onTextStyleChange(size.toInt(), color, position, opacity, border)
                    },
                    label = { Text(positionNames[p] ?: p, fontSize = 11.sp) }
                )
            }
        }

        // 描边开关
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = border,
                onCheckedChange = {
                    onEditStart()
                    border = it
                    onTextStyleChange(size.toInt(), color, position, opacity, border)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3))
            )
            Spacer(Modifier.width(8.dp))
            Text("黑色描边（增强可读性）", color = Color(0xFF888888), fontSize = 12.sp)
        }
    }
}

// ═══════════════════════════════════════
//  音频面板
// ═══════════════════════════════════════

@Composable
fun AudioPanel(
    clip: Clip,
    onVolumeChange: (Double) -> Unit,
    onFadeChange: (Double, Double) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var volume by remember(clip.id) { mutableStateOf(clip.volume.toFloat()) }
    var fadeIn by remember(clip.id) { mutableStateOf(clip.audioFadeIn.toFloat()) }
    var fadeOut by remember(clip.id) { mutableStateOf(clip.audioFadeOut.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("音频", onClose)

        if (!clip.hasAudio) {
            Text("该片段没有音频", color = Color(0xFF666666), fontSize = 13.sp,
                modifier = Modifier.padding(16.dp))
        } else {
            SliderRow("音量", volume, 0f..3f, onValueChange = {
                volume = it
                onVolumeChange(it.toDouble())
            }, onValueChangeStarted = onEditStart)

            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                Button(
                    onClick = { onEditStart(); volume = 0f; onVolumeChange(0.0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("静音", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onEditStart(); volume = 1f; onVolumeChange(1.0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("原音", fontSize = 12.sp) }
            }

            // ── 淡入淡出（对应 audio_fade.py）──
            Text("淡入淡出", color = Color(0xFF888888), fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            SliderRow("淡入", fadeIn, 0f..5f, onValueChange = {
                fadeIn = it
                onFadeChange(fadeIn.toDouble(), fadeOut.toDouble())
            }, onValueChangeStarted = onEditStart)
            SliderRow("淡出", fadeOut, 0f..5f, onValueChange = {
                fadeOut = it
                onFadeChange(fadeIn.toDouble(), fadeOut.toDouble())
            }, onValueChangeStarted = onEditStart)
            Text(
                "单位：秒，最长不超过片段时长一半",
                color = Color(0xFF666666), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// ═══════════════════════════════════════
//  模糊背景面板
// ═══════════════════════════════════════

@Composable
fun BlurBgPanel(
    clip: Clip,
    onToggle: () -> Unit,
    onStrengthChange: (Int) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var strength by remember(clip.id) { mutableStateOf(clip.blurStrength.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("模糊背景", onClose)
        Spacer(Modifier.height(8.dp))

        Text(
            "将视频按原比例放置在画布中央，背景用模糊画面填充。",
            color = Color(0xFF888888), fontSize = 12.sp
        )
        Text(
            "适用于横屏视频转竖屏，或竖屏转横屏。",
            color = Color(0xFF666666), fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = clip.blurBgEnabled,
                onCheckedChange = {
                    onEditStart()
                    onToggle()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3))
            )
            Spacer(Modifier.width(8.dp))
            Text(if (clip.blurBgEnabled) "已启用" else "已关闭",
                color = Color.White, fontSize = 14.sp)
        }

        if (clip.blurBgEnabled) {
            Spacer(Modifier.height(16.dp))
            SliderRow("模糊强度", strength, 2f..40f, onValueChange = {
                strength = it
                onStrengthChange(it.toInt())
            }, onValueChangeStarted = onEditStart)
        }
    }
}

// ═══════════════════════════════════════
//  转场面板
// ═══════════════════════════════════════

@Composable
fun TransitionPanel(
    clip: Clip,
    onTransitionChange: (TransitionEffect) -> Unit,
    onDurationChange: (Double) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var duration by remember(clip.id) { mutableStateOf(clip.transitionDuration.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("转场", onClose)
        Spacer(Modifier.height(8.dp))

        Text(
            "设置当前片段到下一片段的转场效果。",
            color = Color(0xFF888888), fontSize = 12.sp
        )

        Spacer(Modifier.height(12.dp))

        // 转场效果网格
        val effects = TransitionEffect.values().toList()
        val chunked = effects.chunked(3)
        chunked.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { effect ->
                    val isSelected = clip.transition == effect
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onEditStart()
                            onTransitionChange(effect)
                        },
                        label = { Text(effect.displayName, fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 补齐最后一行
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (clip.transition != TransitionEffect.NONE) {
            Spacer(Modifier.height(12.dp))
            SliderRow("转场时长", duration, 0.2f..3f, onValueChange = {
                duration = it
                onDurationChange(it.toDouble())
            }, onValueChangeStarted = onEditStart)
            Text("${String.format("%.1f", duration)}s",
                color = Color(0xFF888888), fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// ═══════════════════════════════════════
//  导出面板
// ═══════════════════════════════════════

@Composable
fun ExportPanel(
    state: EditorUiState,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        PanelHeader("导出", onClose)
        Spacer(Modifier.height(16.dp))

        val project = state.project
        Text("项目: ${project.name}", color = Color.White, fontSize = 14.sp)
        Text("画布: ${project.canvasWidth}x${project.canvasHeight}", color = Color(0xFF888888), fontSize = 12.sp)
        Text("时长: ${String.format("%.2f", project.totalDuration)}s", color = Color(0xFF888888), fontSize = 12.sp)
        Text("片段数: ${project.mainTrack?.clips?.size ?: 0}", color = Color(0xFF888888), fontSize = 12.sp)

        Spacer(Modifier.height(24.dp))

        if (state.isExporting) {
            LinearProgressIndicator(
                progress = { state.exportProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Color(0xFF2196F3),
                trackColor = Color(0xFF333333)
            )
            Text(state.exportMessage, color = Color(0xFF888888), fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("导出视频", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        state.outputPath?.let {
            Spacer(Modifier.height(8.dp))
            Text("✅ 已导出: $it", color = Color(0xFF4CAF50), fontSize = 12.sp)
        }
    }
}
