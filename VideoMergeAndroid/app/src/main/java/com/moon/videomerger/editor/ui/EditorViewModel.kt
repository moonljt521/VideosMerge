package com.moon.videomerger.editor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.editor.data.*
import com.moon.videomerger.editor.engine.ExportEngine
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log as AndroidLog
import java.io.File

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val exportEngine = ExportEngine(app.applicationContext)
    private val appContext = app.applicationContext

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // ═══════════════════════════════════════
    //  项目管理
    // ═══════════════════════════════════════

    /**
     * 创建新项目并导入视频。
     */
    fun createProject(uris: List<Uri>) {
        viewModelScope.launch {
            android.util.Log.d("EditorVM", "createProject: uris.size=${uris.size}")
            val clips = withContext(Dispatchers.IO) {
                uris.mapIndexed { index, uri ->
                    android.util.Log.d("EditorVM", "createProject: processing uri[$index]=$uri")
                    val tempFile = MediaUtils.copyUriToTempFile(appContext, uri, index)
                    val meta = MediaUtils.getVideoMeta(tempFile.absolutePath)
                    val thumb = MediaUtils.loadThumbnailFromFile(tempFile.absolutePath)
                    val thumbFile = File(appContext.cacheDir, "thumb_${System.currentTimeMillis()}_${index}.jpg")
                    if (thumb != null) MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)

                    Clip(
                        mediaPath = tempFile.absolutePath,
                        mediaName = tempFile.name,
                        mediaDuration = meta.duration,
                        width = meta.width,
                        height = meta.height,
                        hasAudio = meta.hasAudio,
                        trimEnd = meta.duration,
                        timelineStart = 0.0,
                        thumbnailPath = thumbFile.absolutePath,
                    )
                }
            }

            android.util.Log.d("EditorVM", "createProject: clips.size=${clips.size}")

            // 计算时间轴位置（首尾拼接）
            var timelinePos = 0.0
            clips.forEach { clip ->
                clip.timelineStart = timelinePos
                timelinePos += clip.timelineDuration
            }

            val mainTrack = Track(type = TrackType.MAIN, clips = clips.toMutableList())
            val project = EditorProject(
                name = "项目 ${System.currentTimeMillis() % 10000}",
                tracks = mutableListOf(mainTrack),
            )

            // ★ 导入视频后自动选中第一个片段，工具栏按钮立即可用
            _uiState.value = EditorUiState(
                project = project,
                selectedClipId = clips.firstOrNull()?.id
            )
        }
    }

    // ═══════════════════════════════════════
    //  片段选择
    // ═══════════════════════════════════════

    fun selectClip(clipId: String?) {
        _uiState.value = _uiState.value.copy(
            selectedClipId = clipId,
            currentPanel = if (clipId != null) _uiState.value.currentPanel else ToolPanel.NONE
        )
    }

    // ═══════════════════════════════════════
    //  工具面板切换
    // ═══════════════════════════════════════

    fun showPanel(panel: ToolPanel) {
        _uiState.value = _uiState.value.copy(currentPanel = panel)
    }

    fun closePanel() {
        _uiState.value = _uiState.value.copy(currentPanel = ToolPanel.NONE)
    }

    /**
     * 重置状态 —— 返回首页时调用，清空项目和播放状态。
     * 下次进入 Editor 时用 key 强制重建 ViewModel，确保干净状态。
     */
    fun resetState() {
        _uiState.value = EditorUiState()
    }

    /**
     * 添加更多视频到主轨末尾（在编辑器中追加，不重建项目）。
     */
    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val newClips = withContext(Dispatchers.IO) {
                uris.mapIndexed { index, uri ->
                    val tempFile = MediaUtils.copyUriToTempFile(appContext, uri, System.currentTimeMillis().toInt() + index)
                    val meta = MediaUtils.getVideoMeta(tempFile.absolutePath)
                    val thumb = MediaUtils.loadThumbnailFromFile(tempFile.absolutePath)
                    val thumbFile = File(appContext.cacheDir, "thumb_${System.currentTimeMillis()}_${index}_add.jpg")
                    if (thumb != null) MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)

                    Clip(
                        mediaPath = tempFile.absolutePath,
                        mediaName = tempFile.name,
                        mediaDuration = meta.duration,
                        width = meta.width,
                        height = meta.height,
                        hasAudio = meta.hasAudio,
                        trimEnd = meta.duration,
                        timelineStart = 0.0,
                        thumbnailPath = thumbFile.absolutePath,
                    )
                }
            }

            val state = _uiState.value
            val mainTrack = state.project.mainTrack
            if (mainTrack == null) {
                // 没有主轨，直接创建
                var pos = 0.0
                newClips.forEach { it.timelineStart = pos; pos += it.timelineDuration }
                val track = Track(type = TrackType.MAIN, clips = newClips.toMutableList())
                _uiState.value = state.copy(
                    project = state.project.copy(tracks = mutableListOf(track))
                )
            } else {
                // 追加到主轨末尾
                val allClips = mainTrack.clips.toMutableList()
                var pos = state.project.totalDuration
                newClips.forEach {
                    it.timelineStart = pos
                    pos += it.timelineDuration
                    allClips.add(it)
                }
                val newTrack = mainTrack.copy(clips = allClips)
                val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }
                _uiState.value = state.copy(
                    project = state.project.copy(tracks = newTracks.toMutableList())
                )
            }
        }
    }

    // ═══════════════════════════════════════
    //  播放控制
    // ═══════════════════════════════════════

    fun togglePlay() {
        val state = _uiState.value
        val totalDur = state.project.totalDuration
        // 如果已到结尾，先回到开头再播放
        val newPos = if (state.currentPosition >= totalDur - 0.1) 0.0 else state.currentPosition
        _uiState.value = state.copy(
            isPlaying = !state.isPlaying,
            currentPosition = newPos
        )
    }

    fun seekTo(position: Double) {
        _uiState.value = _uiState.value.copy(currentPosition = position.coerceIn(0.0, _uiState.value.project.totalDuration))
    }

    // ═══════════════════════════════════════
    //  裁剪
    // ═══════════════════════════════════════

    fun updateTrim(clipId: String, trimStart: Double, trimEnd: Double) {
        updateClip(clipId) {
            // 边界检查：trimStart >= 0，trimEnd <= mediaDuration，trimStart < trimEnd
            val safeStart = trimStart.coerceIn(0.0, it.mediaDuration - 0.1)
            val safeEnd = trimEnd.coerceIn(safeStart + 0.1, it.mediaDuration)
            it.copy(trimStart = safeStart, trimEnd = safeEnd)
        }
        retimelineAfter(clipId)
    }

    fun splitClip(clipId: String, atPosition: Double) {
        val state = _uiState.value
        android.util.Log.d("EditorVM", "splitClip: clipId=$clipId atPos=$atPosition")

        // 找到包含该 clip 的 track
        val trackIdx = state.project.tracks.indexOfFirst { it.clips.any { c -> c.id == clipId } }
        if (trackIdx < 0) { android.util.Log.d("EditorVM", "splitClip: track not found"); return }
        val track = state.project.tracks[trackIdx]

        val clipIdx = track.clips.indexOfFirst { it.id == clipId }
        if (clipIdx < 0) { android.util.Log.d("EditorVM", "splitClip: clip not found"); return }
        val clip = track.clips[clipIdx]

        // 在 atPosition（相对于片段时间轴位置）处分割
        val splitOffset = atPosition - clip.timelineStart
        android.util.Log.d("EditorVM", "splitClip: splitOffset=$splitOffset timelineDur=${clip.timelineDuration}")
        if (splitOffset <= 0 || splitOffset >= clip.timelineDuration) {
            android.util.Log.d("EditorVM", "splitClip: splitOffset out of range, aborting")
            return
        }

        // 原始入点 + 偏移 * 速度 = 分割点的源时间
        val splitSourceTime = clip.trimStart + splitOffset * clip.speed
        android.util.Log.d("EditorVM", "splitClip: splitSourceTime=$splitSourceTime")

        // 创建两个新片段
        val firstClip = clip.copy(trimEnd = splitSourceTime)
        val secondClip = clip.copy(
            trimStart = splitSourceTime,
            timelineStart = clip.timelineStart + splitOffset,
        )

        // 创建全新的 track 和 clips 列表（不可变副本）
        val newClips = track.clips.toMutableList()
        newClips.removeAt(clipIdx)
        newClips.add(clipIdx, firstClip)
        newClips.add(clipIdx + 1, secondClip)

        val newTrack = track.copy(clips = newClips)
        val newTracks = state.project.tracks.toMutableList()
        newTracks[trackIdx] = newTrack

        val newProject = state.project.copy(
            tracks = newTracks,
            updatedAt = System.currentTimeMillis()
        )

        _uiState.value = state.copy(
            project = newProject,
            selectedClipId = firstClip.id  // 选中第一段
        )
        // 分割后重算后续片段的时间轴位置
        retimelineAll()
        android.util.Log.d("EditorVM", "splitClip: done, new clips count=${newClips.size}")
    }

    // ═══════════════════════════════════════
    //  变速
    // ═══════════════════════════════════════

    fun updateSpeed(clipId: String, speed: Double) {
        val s = speed.coerceIn(0.25, 4.0)
        updateClip(clipId) { it.copy(speed = s) }
        // 变速后该片段的时间轴时长改变，需要重算后续片段位置
        retimelineAfter(clipId)
    }

    // ═══════════════════════════════════════
    //  旋转
    // ═══════════════════════════════════════

    fun setRotation(clipId: String, rotation: Int) {
        updateClip(clipId) { it.copy(rotation = rotation) }
    }

    fun toggleHFlip(clipId: String) {
        updateClip(clipId) { it.copy(hflip = !it.hflip) }
    }

    fun toggleVFlip(clipId: String) {
        updateClip(clipId) { it.copy(vflip = !it.vflip) }
    }

    // ═══════════════════════════════════════
    //  滤镜
    // ═══════════════════════════════════════

    fun setFilterPreset(clipId: String, preset: FilterPreset) {
        updateClip(clipId) { it.copy(filterPreset = preset) }
    }

    fun updateColorParams(clipId: String, brightness: Double, contrast: Double, saturation: Double) {
        updateClip(clipId) { it.copy(brightness = brightness, contrast = contrast, saturation = saturation) }
    }

    // ═══════════════════════════════════════
    //  音频
    // ═══════════════════════════════════════

    fun updateVolume(clipId: String, volume: Double) {
        updateClip(clipId) { it.copy(volume = volume.coerceIn(0.0, 5.0)) }
    }

    // ═══════════════════════════════════════
    //  文字水印
    // ═══════════════════════════════════════

    fun setTextOverlay(clipId: String, text: String?) {
        updateClip(clipId) { it.copy(textOverlay = text) }
    }

    fun setTextStyle(clipId: String, size: Int, color: String, position: String, opacity: Float, border: Boolean) {
        updateClip(clipId) {
            it.copy(
                textSize = size.coerceIn(12, 120),
                textColor = color,
                textPosition = position,
                textOpacity = opacity.coerceIn(0f, 1f),
                textBorder = border
            )
        }
    }

    // ═══════════════════════════════════════
    //  模糊背景
    // ═══════════════════════════════════════

    fun toggleBlurBg(clipId: String) {
        updateClip(clipId) { it.copy(blurBgEnabled = !it.blurBgEnabled) }
    }

    fun updateBlurStrength(clipId: String, strength: Int) {
        updateClip(clipId) { it.copy(blurStrength = strength.coerceIn(2, 40)) }
    }

    // ═══════════════════════════════════════
    //  转场
    // ═══════════════════════════════════════

    fun setTransition(clipId: String, effect: TransitionEffect) {
        updateClip(clipId) { it.copy(transition = effect) }
    }

    fun updateTransitionDuration(clipId: String, duration: Double) {
        updateClip(clipId) { it.copy(transitionDuration = duration.coerceIn(0.2, 3.0)) }
    }

    // ═══════════════════════════════════════
    //  导出
    // ═══════════════════════════════════════

    fun export() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(
                isExporting = true,
                exportProgress = 0f,
                exportMessage = "准备导出...",
                errorMessage = null,
            )

            val result = exportEngine.export(
                project = state.project,
                onProgress = { progress ->
                    _uiState.value = _uiState.value.copy(
                        exportProgress = progress,
                        exportMessage = "导出中... ${(progress * 100).toInt()}%"
                    )
                },
                onLog = { log ->
                    AndroidLog.d("EditorVM", log)
                }
            )

            result.fold(
                onSuccess = { file ->
                    val saveResult = exportEngine.saveToGallery(file, state.project.name)
                    saveResult.fold(
                        onSuccess = { uri ->
                            _uiState.value = _uiState.value.copy(
                                isExporting = false,
                                exportProgress = 1f,
                                exportMessage = "导出完成！已保存到相册",
                                outputPath = file.absolutePath,
                            )
                        },
                        onFailure = { e ->
                            _uiState.value = _uiState.value.copy(
                                isExporting = false,
                                errorMessage = "保存失败: ${e.message}"
                            )
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportProgress = 0f,
                        exportMessage = "",
                        errorMessage = "导出失败: ${e.message}"
                    )
                }
            )
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    // ═══════════════════════════════════════
    //  通用工具
    // ═══════════════════════════════════════

    private fun updateClip(clipId: String, transform: (Clip) -> Clip) {
        val state = _uiState.value
        val oldProject = state.project
        var updated = false

        // 不可变更新：创建全新的 tracks 列表和 Track/Clip 实例，
        // 这样 StateFlow 才能感知变化并触发 UI 重组。
        val newTracks = oldProject.tracks.map { track ->
            val idx = track.clips.indexOfFirst { it.id == clipId }
            if (idx >= 0) {
                updated = true
                val newClips = track.clips.toMutableList()
                newClips[idx] = transform(newClips[idx])
                track.copy(clips = newClips)
            } else {
                track
            }
        }

        if (updated) {
            val newProject = oldProject.copy(
                tracks = newTracks.toMutableList(),
                updatedAt = System.currentTimeMillis()
            )
            _uiState.value = state.copy(project = newProject)
        }
    }

    /**
     * 重算主轨所有片段的时间轴位置（timelineStart）。
     * 按 clips 在列表中的顺序首尾拼接：
     *   clip[i].timelineStart = clip[i-1].timelineEnd
     *
     * 当 trim/speed 改变导致某个片段的 timelineDuration 变化时，
     * 必须重算后续片段的 timelineStart，否则时间轴会错位/重叠。
     */
    private fun retimelineAll() {
        val state = _uiState.value
        val mainTrack = state.project.mainTrack ?: return
        val sortedClips = mainTrack.clips.sortedBy { it.timelineStart }.toMutableList()

        var pos = 0.0
        for (clip in sortedClips) {
            // 由于 Clip.timelineStart 是 var，直接修改
            clip.timelineStart = pos
            pos += clip.timelineDuration
        }

        // 触发状态更新（创建新的 project 实例让 StateFlow 感知）
        val newTrack = mainTrack.copy(clips = sortedClips.toMutableList())
        val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }
        _uiState.value = state.copy(
            project = state.project.copy(
                tracks = newTracks.toMutableList(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 从指定片段开始重算时间轴位置（该片段及其后续片段）。
     * 用于 trim/speed 变更后只更新受影响的部分。
     */
    private fun retimelineAfter(clipId: String) {
        val state = _uiState.value
        val mainTrack = state.project.mainTrack ?: return
        val sortedClips = mainTrack.clips.sortedBy { it.timelineStart }.toMutableList()

        val startIdx = sortedClips.indexOfFirst { it.id == clipId }
        if (startIdx < 0) return

        // 起始位置：前一个片段的结尾（如果是第一个片段，从 0 开始）
        val startPos = if (startIdx > 0) {
            sortedClips[startIdx - 1].timelineEnd
        } else {
            0.0
        }

        // 从 startIdx 开始累加 timelineStart
        var pos = startPos
        for (i in startIdx until sortedClips.size) {
            sortedClips[i].timelineStart = pos
            pos += sortedClips[i].timelineDuration
        }

        val newTrack = mainTrack.copy(clips = sortedClips.toMutableList())
        val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }
        _uiState.value = state.copy(
            project = state.project.copy(
                tracks = newTracks.toMutableList(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}

