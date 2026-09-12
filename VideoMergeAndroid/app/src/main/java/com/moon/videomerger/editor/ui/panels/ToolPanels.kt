package com.moon.videomerger.editor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.videomerger.editor.data.Clip
import com.moon.videomerger.editor.data.EditorUiState
import com.moon.videomerger.editor.data.FilterPreset
import com.moon.videomerger.editor.data.PipShape
import com.moon.videomerger.editor.data.TransitionEffect
import com.moon.videomerger.util.MediaUtils

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
    val keyboardController = LocalSoftwareKeyboardController.current

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
            .pointerInput(Unit) {
                detectTapGestures(onTap = { keyboardController?.hide() })
            }
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
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
    onPitchChange: (Double) -> Unit,
    onNoiseReductionToggle: () -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var volume by remember(clip.id) { mutableStateOf(clip.volume.toFloat()) }
    var fadeIn by remember(clip.id) { mutableStateOf(clip.audioFadeIn.toFloat()) }
    var fadeOut by remember(clip.id) { mutableStateOf(clip.audioFadeOut.toFloat()) }
    var pitch by remember(clip.id) { mutableStateOf(clip.pitchShift.toFloat()) }

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

            // ── 变声 ──
            Text("变声", color = Color(0xFF888888), fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            val pitchPresets = listOf(
                0.5f to "低沉", 0.7f to "大叔", 1.0f to "原声", 1.3f to "女声", 1.6f to "萝莉"
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pitchPresets) { (p, label) ->
                    FilterChip(
                        selected = pitch == p,
                        onClick = { onEditStart(); pitch = p; onPitchChange(p.toDouble()) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2196F3),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            SliderRow("音高", pitch, 0.5f..2f, onValueChange = {
                pitch = it
                onPitchChange(it.toDouble())
            }, onValueChangeStarted = onEditStart)

            // ── 降噪 ──
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = clip.noiseReduction,
                    onCheckedChange = { onEditStart(); onNoiseReductionToggle() },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3))
                )
                Spacer(Modifier.width(8.dp))
                Text("降噪（去背景噪声）", color = Color(0xFF888888), fontSize = 12.sp)
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
//  字幕面板
// ═══════════════════════════════════════

@Composable
fun SubtitlePanel(
    state: EditorUiState,
    currentPosition: Double,
    onAdd: (String, Double, Double) -> Unit,
    onRemove: (String) -> Unit,
    onTranscribe: () -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(currentPosition.toFloat()) }
    var end by remember { mutableStateOf((currentPosition + 2.0).toFloat()) }
    val total = state.project.totalDuration.toFloat().coerceAtLeast(0.1f)
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            // 点击空白处收起键盘（子组件按钮/输入框会先消费点击，不影响它们）
            .pointerInput(Unit) {
                detectTapGestures(onTap = { keyboardController?.hide() })
            }
    ) {
        PanelHeader("字幕", onClose)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("字幕文本", color = Color(0xFF888888)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = Color(0xFF2196F3),
                cursorColor = Color(0xFF2196F3)
            )
        )

        SliderRow("开始时间", start, 0f..total, onValueChange = {
            start = it.coerceAtMost(end - 0.1f)
        })
        SliderRow("结束时间", end, 0f..total, onValueChange = {
            end = it.coerceAtLeast(start + 0.1f)
        })

        Button(
            onClick = { onEditStart(); onAdd(text, start.toDouble(), end.toDouble()); text = "" },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
            modifier = Modifier.fillMaxWidth(),
            enabled = text.isNotBlank()
        ) {
            Text("添加字幕", fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { keyboardController?.hide(); onTranscribe() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isTranscribing
        ) {
            Text(if (state.isTranscribing) "识别中…" else "🎤 语音转字幕", fontSize = 13.sp)
        }
        if (state.isTranscribing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 8.dp),
                color = Color(0xFF4CAF50),
                trackColor = Color(0xFF333333)
            )
        }

        Spacer(Modifier.height(12.dp))
        if (state.project.subtitles.isEmpty()) {
            Text("暂无字幕。设置文本和时间后点「添加字幕」。", color = Color(0xFF666666), fontSize = 11.sp)
        } else {
            state.project.subtitles.sortedBy { it.startTime }.forEach { sub ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sub.text, color = Color.White, fontSize = 13.sp)
                        Text(
                            "${String.format("%.1f", sub.startTime)}s ~ ${String.format("%.1f", sub.endTime)}s",
                            color = Color(0xFF888888), fontSize = 11.sp
                        )
                    }
                    Text(
                        "删除",
                        color = Color(0xFFFF7043), fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onEditStart(); onRemove(sub.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  贴纸面板
// ═══════════════════════════════════════

@Composable
fun StickerPanel(
    onPick: (String) -> Unit,
    onClose: () -> Unit
) {
    val stickers = listOf(
        "😀", "😂", "❤️", "👍", "🔥", "⭐", "🎉", "💯", "😍", "🤔",
        "😎", "🥰", "😭", "🙏", "👏", "✨", "🌹", "🍉", "🐱", "🚀"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("贴纸", onClose)
        Spacer(Modifier.height(8.dp))
        Text("点选一个贴纸，叠加到当前播放头处（可在「画中画」面板里调位置/大小）",
            color = Color(0xFF888888), fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))
        stickers.chunked(5).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { emoji ->
                    Text(
                        emoji,
                        fontSize = 34.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(emoji) }
                            .padding(10.dp)
                    )
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ═══════════════════════════════════════
//  图片水印面板
// ═══════════════════════════════════════

@Composable
fun ImageWatermarkPanel(
    clip: Clip,
    onSelect: (String?) -> Unit,
    onChange: (Double, Double, String) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember(clip.id) { mutableStateOf(clip.imageWatermarkScale.toFloat()) }
    var opacity by remember(clip.id) { mutableStateOf(clip.imageWatermarkOpacity.toFloat()) }
    var position by remember(clip.id) { mutableStateOf(clip.imageWatermarkPosition) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onEditStart()
            val file = MediaUtils.copyUriToTempFile(context, uri, 0)
            onSelect(file.absolutePath)
        }
    }

    val positions = listOf(
        "top-left" to "左上",
        "top-right" to "右上",
        "bottom-left" to "左下",
        "bottom-right" to "右下",
        "center" to "居中"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("图片水印", onClose)

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (clip.imageWatermarkPath == null) "未选择图片" else "已选择图片",
                    color = Color.White,
                    fontSize = 14.sp
                )
                clip.imageWatermarkPath?.let { path ->
                    Text(path.substringAfterLast('/'), color = Color(0xFF666666), fontSize = 11.sp)
                }
            }
            Button(onClick = { imagePicker.launch("image/*") }) {
                Text("选择图片", fontSize = 12.sp)
            }
            if (clip.imageWatermarkPath != null) {
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onEditStart(); onSelect(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("移除", fontSize = 12.sp)
                }
            }
        }

        Text("位置", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(positions) { (key, label) ->
                FilterChip(
                    selected = position == key,
                    onClick = {
                        onEditStart()
                        position = key
                        onChange(scale.toDouble(), opacity.toDouble(), key)
                    },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        SliderRow("大小", scale, 0.05f..1.0f, onValueChange = {
            scale = it
            onChange(scale.toDouble(), opacity.toDouble(), position)
        }, onValueChangeStarted = onEditStart)

        SliderRow("透明度", opacity, 0f..1f, onValueChange = {
            opacity = it
            onChange(scale.toDouble(), opacity.toDouble(), position)
        }, onValueChangeStarted = onEditStart)
    }
}

// ═══════════════════════════════════════
//  画中画面板
// ═══════════════════════════════════════

@Composable
fun PicturePanel(
    clip: Clip,
    currentPosition: Double,
    totalDuration: Double,
    onTransformChange: (Double, Double, Double, Double) -> Unit,
    onStyleChange: (PipShape, Double, Boolean, Double) -> Unit,
    onTimingChange: (Double, Double) -> Unit,
    onSpeedChange: (Double) -> Unit,
    onReverseToggle: () -> Unit,
    onRotation: (Int) -> Unit,
    onHFlip: () -> Unit,
    onVFlip: () -> Unit,
    onAddKeyframe: () -> Unit,
    onRemoveKeyframe: (Int) -> Unit,
    onRemove: () -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    var x by remember(clip.id) { mutableStateOf(clip.pipX.toFloat()) }
    var y by remember(clip.id) { mutableStateOf(clip.pipY.toFloat()) }
    var width by remember(clip.id) { mutableStateOf(clip.pipWidth.toFloat()) }
    var opacity by remember(clip.id) { mutableStateOf(clip.pipOpacity.toFloat()) }
    var shape by remember(clip.id) { mutableStateOf(clip.pipShape) }
    var cornerRadius by remember(clip.id) { mutableStateOf(clip.pipCornerRadius.toFloat()) }
    var border by remember(clip.id) { mutableStateOf(clip.pipBorder) }
    var borderWidth by remember(clip.id) { mutableStateOf(clip.pipBorderWidth.toFloat()) }
    var speed by remember(clip.id) { mutableStateOf(clip.speed.toFloat()) }
    var startTime by remember(clip.id) { mutableStateOf(clip.timelineStart.toFloat()) }
    var duration by remember(clip.id) { mutableStateOf(clip.timelineDuration.toFloat()) }

    val speedPresets = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("画中画", onClose)
        Spacer(Modifier.height(8.dp))

        Text("片段：${clip.mediaName}", color = Color(0xFF888888), fontSize = 12.sp)

        SliderRow("水平位置", x, 0f..1f, onValueChange = {
            x = it
            onTransformChange(x.toDouble(), y.toDouble(), width.toDouble(), opacity.toDouble())
        }, onValueChangeStarted = onEditStart)
        SliderRow("垂直位置", y, 0f..1f, onValueChange = {
            y = it
            onTransformChange(x.toDouble(), y.toDouble(), width.toDouble(), opacity.toDouble())
        }, onValueChangeStarted = onEditStart)
        SliderRow("大小", width, 0.05f..1f, onValueChange = {
            width = it
            onTransformChange(x.toDouble(), y.toDouble(), width.toDouble(), opacity.toDouble())
        }, onValueChangeStarted = onEditStart)
        SliderRow("透明度", opacity, 0f..1f, onValueChange = {
            opacity = it
            onTransformChange(x.toDouble(), y.toDouble(), width.toDouble(), opacity.toDouble())
        }, onValueChangeStarted = onEditStart)

        // ── 时间（开始 + 时长）──
        Text("时间", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        SliderRow("开始时间", startTime, 0f..totalDuration.toFloat().coerceAtLeast(0.1f), onValueChange = {
            startTime = it
            onTimingChange(startTime.toDouble(), duration.toDouble())
        }, onValueChangeStarted = onEditStart)
        SliderRow("时长", duration, 0.5f..60f, onValueChange = {
            duration = it
            onTimingChange(startTime.toDouble(), duration.toDouble())
        }, onValueChangeStarted = onEditStart)

        // ── 关键帧（位移动画）──
        Text("关键帧（位移动画）", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Button(
            onClick = { onEditStart(); onAddKeyframe() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("在播放头添加关键帧 @${String.format("%.1f", currentPosition)}s", fontSize = 12.sp)
        }
        if (clip.pipKeyframes.isEmpty()) {
            Text(
                "提示：先调「水平/垂直位置」，再到播放头处添加关键帧；多个关键帧之间会自动位移插值。",
                color = Color(0xFF666666), fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            clip.pipKeyframes.forEachIndexed { index, kf ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "t=${String.format("%.2f", kf.time)}s  (${"%.2f".format(kf.x)}, ${"%.2f".format(kf.y)})",
                        color = Color(0xFFDDDDDD), fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "删除",
                        color = Color(0xFFFF7043), fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onEditStart(); onRemoveKeyframe(index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ── 形状 ──
        Text("形状", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PipShape.entries.toList()) { s ->
                FilterChip(
                    selected = shape == s,
                    onClick = {
                        onEditStart()
                        shape = s
                        onStyleChange(shape, cornerRadius.toDouble(), border, borderWidth.toDouble())
                    },
                    label = { Text(s.displayName, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2196F3),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        if (shape == PipShape.ROUNDED) {
            SliderRow("圆角", cornerRadius, 0f..0.5f, onValueChange = {
                cornerRadius = it
                onStyleChange(shape, cornerRadius.toDouble(), border, borderWidth.toDouble())
            }, onValueChangeStarted = onEditStart)
        }

        // ── 描边 ──
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = border,
                onCheckedChange = {
                    onEditStart()
                    border = it
                    onStyleChange(shape, cornerRadius.toDouble(), border, borderWidth.toDouble())
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3))
            )
            Spacer(Modifier.width(8.dp))
            Text("描边", color = Color(0xFF888888), fontSize = 12.sp)
        }
        if (border) {
            SliderRow("描边宽度", borderWidth, 0f..0.2f, onValueChange = {
                borderWidth = it
                onStyleChange(shape, cornerRadius.toDouble(), border, borderWidth.toDouble())
            }, onValueChangeStarted = onEditStart)
        }

        // ── 变速 ──
        Text("变速", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(speedPresets) { preset ->
                FilterChip(
                    selected = speed == preset,
                    onClick = {
                        onEditStart()
                        speed = preset
                        onSpeedChange(preset.toDouble())
                    },
                    label = { Text("${preset}x", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2196F3),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // ── 倒放 ──
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = clip.reversed,
                onCheckedChange = { onEditStart(); onReverseToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2196F3))
            )
            Spacer(Modifier.width(8.dp))
            Text("倒放", color = Color(0xFF888888), fontSize = 12.sp)
        }

        // ── 旋转 / 翻转 ──
        Text("旋转 / 翻转", color = Color(0xFF888888), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onEditStart(); onRemove() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("移除画中画", fontSize = 13.sp)
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
//  画布面板（比例 + 清晰度，v1.1）
// ═══════════════════════════════════════

/** 16 对齐（h264_mediacodec 宏块对齐要求） */
private fun align16(v: Int) = ((v + 15) / 16) * 16

/**
 * 按比例 + 短边基准计算画布尺寸（16 对齐）。
 * 「720P/1080P」指短边；横屏即高为基准，竖屏即宽为基准。
 */
internal fun canvasDims(ratioW: Int, ratioH: Int, shortEdge: Int): Pair<Int, Int> =
    if (ratioW >= ratioH) {
        align16(shortEdge) to align16(shortEdge * ratioW / ratioH)
    } else {
        align16(shortEdge * ratioW / ratioH) to align16(shortEdge)
    }

@Composable
fun CanvasPanel(
    project: com.moon.videomerger.editor.data.EditorProject,
    onSetCanvas: (Int, Int) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    data class RatioOpt(val label: String, val w: Int, val h: Int)
    data class QualityOpt(val label: String, val shortEdge: Int)

    val ratios = listOf(RatioOpt("9:16", 9, 16), RatioOpt("1:1", 1, 1), RatioOpt("16:9", 16, 9))
    val qualities = listOf(QualityOpt("720P", 720), QualityOpt("1080P", 1088), QualityOpt("2K", 1440))

    val currentW = project.canvasWidth
    val currentH = project.canvasHeight

    // 反推当前选中的比例与清晰度；对不上（自定义/遗留值）时取最接近档位
    val matched = qualities.firstOrNull { q ->
        ratios.any { r -> canvasDims(r.w, r.h, q.shortEdge) == currentW to currentH }
    }
    val curShort = matched?.shortEdge
        ?: qualities.minByOrNull { kotlin.math.abs(it.shortEdge - minOf(currentW, currentH)) }?.shortEdge
        ?: 1088
    val curRatioLabel = ratios.firstOrNull { canvasDims(it.w, it.h, curShort) == currentW to currentH }?.label
        ?: ratios.firstOrNull { it.label == "9:16" }?.label ?: "9:16"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("画布", onClose)

        Text(
            "比例",
            color = Color(0xFF888888),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        // ── 比例卡片（带形状预览）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ratios.forEach { r ->
                val dims = canvasDims(r.w, r.h, curShort)
                val selected = r.label == curRatioLabel &&
                    dims == currentW to currentH
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFF123A5C) else Color(0xFF2A2A2A))
                        .clickable {
                            onEditStart()
                            onSetCanvas(dims.first, dims.second)
                        }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 形状预览：在 56x56dp 区域内按比例缩放
                    val maxSide = 52.dp
                    val pw = if (r.h >= r.w) maxSide * r.w / r.h else maxSide
                    val ph = if (r.h >= r.w) maxSide else maxSide * r.h / r.w
                    Box(
                        modifier = Modifier
                            .height(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(pw)
                                .height(ph)
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) Color(0xFF2196F3) else Color(0xFF888888),
                                    RoundedCornerShape(3.dp)
                                )
                                .background(Color(0xFF111111))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        r.label,
                        color = if (selected) Color(0xFF2196F3) else Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "清晰度",
            color = Color(0xFF888888),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        // ── 清晰度 chips ──
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            qualities.forEach { q ->
                val r = ratios.firstOrNull { it.label == curRatioLabel } ?: ratios.first()
                val dims = canvasDims(r.w, r.h, q.shortEdge)
                FilterChip(
                    selected = matched?.shortEdge == q.shortEdge && dims == currentW to currentH,
                    onClick = {
                        onEditStart()
                        onSetCanvas(dims.first, dims.second)
                    },
                    label = { Text(q.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2196F3),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "当前画布：${currentW}×${currentH}（预览以导出为准）",
            color = Color(0xFF666666),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
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

// ═══════════════════════════════════════
//  去水印面板
// ═══════════════════════════════════════

@Composable
fun WatermarkRemovePanel(
    clip: Clip,
    isDetecting: Boolean,
    onDetect: () -> Unit,
    onAddRegion: () -> Unit,
    onAddRegionAt: (String) -> Unit,
    onUpdateRegion: (Int, com.moon.videomerger.editor.data.WatermarkRegion) -> Unit,
    onRemoveRegion: (Int) -> Unit,
    onEditStart: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(vertical = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader("去水印", onClose)
        Spacer(Modifier.height(8.dp))
        Text(
            "框选画面中的水印位置（预览中红框标识），导出时对该区域做模糊/马赛克覆盖。\n" +
                "自动检测识别静态水印（如平台 logo/昵称），检测不到可手动添加。",
            color = Color(0xFF888888),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Button(
                onClick = onDetect,
                enabled = !isDetecting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                if (isDetecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("检测中...", color = Color.White, fontSize = 13.sp)
                } else {
                    Text("自动检测水印", color = Color.White, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onAddRegion) {
                Text("手动添加区域", color = Color.White, fontSize = 13.sp)
            }
        }

        // 四角快捷预设（抖音水印常用位置，一键框住再微调）
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "常用水印位置：",
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            listOf("tl" to "左上", "tr" to "右上", "bl" to "左下", "br" to "右下").forEach { (corner, label) ->
                Text(
                    label,
                    color = Color(0xFF2196F3),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onAddRegionAt(corner) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (clip.watermarkRegions.isEmpty()) {
            Text(
                "暂无区域",
                color = Color(0xFF666666),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        clip.watermarkRegions.forEachIndexed { index, region ->
            var mode by remember(clip.id, index) { mutableStateOf(region.mode) }
            Surface(
                color = Color(0xFF242424),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "区域 ${index + 1}",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        // 模式切换
                        listOf(
                            com.moon.videomerger.editor.data.WatermarkMode.BLUR to "模糊",
                            com.moon.videomerger.editor.data.WatermarkMode.MOSAIC to "马赛克",
                        ).forEach { (m, label) ->
                            Text(
                                label,
                                color = if (mode == m) Color(0xFF2196F3) else Color(0xFF888888),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onEditStart()
                                        mode = m
                                        onUpdateRegion(index, region.copy(mode = m))
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        IconButton(onClick = { onEditStart(); onRemoveRegion(index) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "删除区域", tint = Color(0xFFFF7043), modifier = Modifier.size(16.dp))
                        }
                    }

                    SliderRow("位置X", region.x.toFloat(), 0f..0.99f, onValueChange = {
                        onUpdateRegion(index, region.copy(x = it.toDouble()))
                    }, onValueChangeStarted = onEditStart)
                    SliderRow("位置Y", region.y.toFloat(), 0f..0.99f, onValueChange = {
                        onUpdateRegion(index, region.copy(y = it.toDouble()))
                    }, onValueChangeStarted = onEditStart)
                    SliderRow("宽度", region.w.toFloat(), 0.02f..1f, onValueChange = {
                        onUpdateRegion(index, region.copy(w = it.toDouble()))
                    }, onValueChangeStarted = onEditStart)
                    SliderRow("高度", region.h.toFloat(), 0.02f..1f, onValueChange = {
                        onUpdateRegion(index, region.copy(h = it.toDouble()))
                    }, onValueChangeStarted = onEditStart)
                    SliderRow(
                        if (mode == com.moon.videomerger.editor.data.WatermarkMode.BLUR) "模糊强度" else "马赛克块",
                        region.strength.toFloat(),
                        (if (mode == com.moon.videomerger.editor.data.WatermarkMode.BLUR) 1f else 4f)..40f,
                        onValueChange = {
                            onUpdateRegion(index, region.copy(strength = it.toInt().coerceAtLeast(1)))
                        },
                        onValueChangeStarted = onEditStart
                    )

                    // 生效时间段（源视频秒；移动水印可分时段框选）
                    val durF = clip.mediaDuration.toFloat().coerceAtLeast(0.5f)
                    SliderRow("起始", region.startTime.toFloat().coerceIn(0f, durF), 0f..durF,
                        onValueChange = {
                            onUpdateRegion(index, region.copy(startTime = it.toDouble()))
                        },
                        onValueChangeStarted = onEditStart
                    )
                    val shownEnd = if (region.endTime <= region.startTime) durF else region.endTime.toFloat()
                    SliderRow("结束", shownEnd.coerceIn(0f, durF), 0f..durF,
                        onValueChange = {
                            onUpdateRegion(index, region.copy(endTime = it.toDouble()))
                        },
                        onValueChangeStarted = onEditStart
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
