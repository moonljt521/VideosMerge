package com.moon.videomerger.editor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.editor.data.*
import com.moon.videomerger.editor.engine.ExportEngine
import com.moon.videomerger.editor.engine.VoskSpeechRecognizer
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // ── 撤销/重做栈（保存项目快照，最多 30 步）──
    private val undoStack = ArrayDeque<EditorProject>()
    private val redoStack = ArrayDeque<EditorProject>()

    /** 当前导出任务（用于取消） */
    private var exportJob: Job? = null

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
            undoStack.clear()
            redoStack.clear()
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
        // 选中片段时把播放头移入该片段（若当前播放头不在其范围内），
        // 保证预览区显示的是正在编辑的片段，滤镜/裁剪等效果能立即看到。
        val state = _uiState.value
        val clip = if (clipId != null) {
            state.project.tracks.flatMap { it.clips }.find { it.id == clipId }
        } else {
            null
        }
        val currentPos = state.currentPosition
        val newPosition = if (clip != null &&
            (currentPos < clip.timelineStart - 0.05 || currentPos >= clip.timelineEnd - 0.05)
        ) {
            clip.timelineStart
        } else {
            currentPos
        }
        _uiState.value = _uiState.value.copy(
            selectedClipId = clipId,
            currentPanel = if (clipId != null) _uiState.value.currentPanel else ToolPanel.NONE,
            currentPosition = newPosition
        )
    }

    // ═══════════════════════════════════════
    //  撤销 / 重做
    // ═══════════════════════════════════════

    /**
     * 开始一次编辑手势 —— UI 在滑块拖动开始 / 按钮点击时调用，
     * 先保存快照，后续变更可撤销。整个手势只推一次栈。
     */
    fun beginEdit() {
        pushUndo()
    }

    private fun pushUndo() {
        undoStack.addLast(deepCopy(_uiState.value.project))
        if (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
        _uiState.value = _uiState.value.copy(canUndo = true, canRedo = false)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(deepCopy(_uiState.value.project))
        val prev = undoStack.removeLast()
        _uiState.value = _uiState.value.copy(
            project = prev,
            selectedClipId = null,
            currentPanel = ToolPanel.NONE,
            canUndo = undoStack.isNotEmpty(),
            canRedo = true
        )
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(deepCopy(_uiState.value.project))
        val next = redoStack.removeLast()
        _uiState.value = _uiState.value.copy(
            project = next,
            selectedClipId = null,
            currentPanel = ToolPanel.NONE,
            canUndo = true,
            canRedo = redoStack.isNotEmpty()
        )
    }

    /** 项目快照深拷贝（Clip 引用全部新建，避免后续可变操作污染快照） */
    private fun deepCopy(project: EditorProject): EditorProject = project.copy(
        tracks = project.tracks.map { track ->
            track.copy(clips = track.clips.map { it.copy() }.toMutableList())
        }.toMutableList()
    )

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
            pushUndo()
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
                    project = state.project.copy(tracks = mutableListOf(track)),
                    // ★ 当前无选中片段时自动选中第一个，保证工具栏可用
                    selectedClipId = state.selectedClipId ?: newClips.firstOrNull()?.id
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
                    project = state.project.copy(tracks = newTracks.toMutableList()),
                    // ★ 当前无选中片段时自动选中第一个，保证工具栏可用
                    selectedClipId = state.selectedClipId ?: allClips.firstOrNull()?.id
                )
            }
        }
    }

    /**
     * 添加画中画叠加层（PICTURE 轨）：把选中的视频/图片叠加在主轨之上。
     * 默认从当前播放头位置开始，叠加层占据整个源时长。
     */
    fun addPipOverlay(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            pushUndo()
            val newClips = withContext(Dispatchers.IO) {
                uris.mapIndexed { index, uri ->
                    val tempFile = MediaUtils.copyUriToTempFile(appContext, uri, System.currentTimeMillis().toInt() + index)
                    val thumbFile = File(appContext.cacheDir, "thumb_pip_${System.currentTimeMillis()}_${index}.jpg")

                    if (MediaUtils.isImageFile(tempFile.absolutePath)) {
                        // 图片叠加：默认时长 3 秒、无音轨
                        val dims = MediaUtils.getImageDimensions(tempFile.absolutePath)
                        val thumb = MediaUtils.loadImageFromFile(tempFile.absolutePath)
                        if (thumb != null) MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)
                        val dur = 3.0
                        Clip(
                            mediaPath = tempFile.absolutePath,
                            mediaName = tempFile.name,
                            mediaDuration = dur,
                            width = dims?.first ?: 0,
                            height = dims?.second ?: 0,
                            hasAudio = false,
                            trimEnd = dur,
                            timelineStart = 0.0,
                            thumbnailPath = thumbFile.absolutePath,
                            pipEnabled = true,
                            isImage = true,
                        )
                    } else {
                        val meta = MediaUtils.getVideoMeta(tempFile.absolutePath)
                        val thumb = MediaUtils.loadThumbnailFromFile(tempFile.absolutePath)
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
                            pipEnabled = true,
                        )
                    }
                }
            }

            val state = _uiState.value
            // 默认从播放头处开始叠加
            var pos = state.currentPosition
            newClips.forEach { it.timelineStart = pos; pos += it.timelineDuration }

            val existingPip = state.project.tracks.find { it.type == TrackType.PICTURE }
            val newTracks = state.project.tracks.toMutableList()
            if (existingPip == null) {
                newTracks.add(Track(type = TrackType.PICTURE, clips = newClips.toMutableList()))
            } else {
                val idx = newTracks.indexOfFirst { it.id == existingPip.id }
                newTracks[idx] = existingPip.copy(clips = (existingPip.clips + newClips).toMutableList())
            }

            _uiState.value = state.copy(
                project = state.project.copy(tracks = newTracks, updatedAt = System.currentTimeMillis()),
                selectedClipId = newClips.firstOrNull()?.id,
                selectedTrackId = newTracks.find { it.type == TrackType.PICTURE }?.id,
                currentPanel = ToolPanel.PICTURE
            )
        }
    }

    /** 更新画中画叠加层的位置/大小/透明度（pipX/pipY ∈ 0~1，pipWidth ∈ 0.05~1） */
    fun updatePipTransform(clipId: String, x: Double, y: Double, width: Double, opacity: Double) {
        updateClip(clipId) {
            it.copy(
                pipX = x.coerceIn(0.0, 1.0),
                pipY = y.coerceIn(0.0, 1.0),
                pipWidth = width.coerceIn(0.05, 1.0),
                pipOpacity = opacity.coerceIn(0.0, 1.0),
            )
        }
    }

    /** 更新画中画形状/描边（cornerRadius ∈ 0~0.5，borderWidth ∈ 0~0.2，均相对画中画宽度） */
    fun updatePipStyle(clipId: String, shape: PipShape, cornerRadius: Double, border: Boolean, borderWidth: Double) {
        updateClip(clipId) {
            it.copy(
                pipShape = shape,
                pipCornerRadius = cornerRadius.coerceIn(0.0, 0.5),
                pipBorder = border,
                pipBorderWidth = borderWidth.coerceIn(0.0, 0.2),
            )
        }
    }

    /** 在当前播放头处添加画中画位置关键帧（记录片段当前静态位置 pipX/pipY） */
    fun addPipKeyframe(clipId: String) {
        val time = _uiState.value.currentPosition
        updateClip(clipId) { clip ->
            val kf = PipKeyframe(
                time = time,
                x = clip.pipX.coerceIn(0.0, 1.0),
                y = clip.pipY.coerceIn(0.0, 1.0),
            )
            clip.copy(pipKeyframes = (clip.pipKeyframes + kf).sortedBy { it.time })
        }
    }

    /** 更新某个画中画关键帧的位置 */
    fun updatePipKeyframe(clipId: String, index: Int, x: Double, y: Double) {
        updateClip(clipId) { clip ->
            val kfs = clip.pipKeyframes.toMutableList()
            if (index in kfs.indices) {
                kfs[index] = kfs[index].copy(x = x.coerceIn(0.0, 1.0), y = y.coerceIn(0.0, 1.0))
            }
            clip.copy(pipKeyframes = kfs)
        }
    }

    /** 删除某个画中画关键帧 */
    fun removePipKeyframe(clipId: String, index: Int) {
        updateClip(clipId) { clip ->
            val kfs = clip.pipKeyframes.toMutableList()
            if (index in kfs.indices) kfs.removeAt(index)
            clip.copy(pipKeyframes = kfs)
        }
    }

    // ═══════════════════════════════════════
    //  字幕
    // ═══════════════════════════════════════

    /** 添加字幕（默认用当前播放头附近的时间范围） */
    fun addSubtitle(text: String, start: Double, end: Double) {
        if (text.isBlank() || end <= start) return
        pushUndo()
        val st = _uiState.value
        val sub = Subtitle(text = text.trim(), startTime = start.coerceAtLeast(0.0), endTime = end)
        _uiState.value = st.copy(
            project = st.project.copy(
                subtitles = (st.project.subtitles + sub).sortedBy { it.startTime },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 删除字幕 */
    fun removeSubtitle(id: String) {
        pushUndo()
        val st = _uiState.value
        _uiState.value = st.copy(
            project = st.project.copy(
                subtitles = st.project.subtitles.filterNot { it.id == id },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 语音转字幕：提取主轨第一个片段音频 → Vosk 离线识别 → 生成字幕条目 */
    fun transcribeSpeech() {
        if (_uiState.value.isTranscribing) return
        val project = _uiState.value.project
        val mainClip = project.mainTrack?.clips?.firstOrNull()
        if (mainClip == null) {
            showError("没有视频片段")
            return
        }
        if (!VoskSpeechRecognizer.isModelReady(appContext)) {
            showError("语音模型未就绪，请先放入模型：\n${VoskSpeechRecognizer.modelDir(appContext).absolutePath}")
            return
        }

        _uiState.value = _uiState.value.copy(isTranscribing = true)
        android.util.Log.d("EditorVM", "transcribeSpeech 开始, clip=${mainClip.mediaName}, modelReady=${VoskSpeechRecognizer.isModelReady(appContext)}")
        viewModelScope.launch {
            try {
                val subs = withContext(Dispatchers.IO) {
                    val raw = VoskSpeechRecognizer.extractAudio(appContext, mainClip.mediaPath)
                    android.util.Log.d("EditorVM", "音频提取完成: ${raw.absolutePath} (${raw.length()} bytes)")
                    val words = VoskSpeechRecognizer.recognize(appContext, raw)
                    android.util.Log.d("EditorVM", "识别词数: ${words.size}, 词列表: ${words.take(10).joinToString { it.text }}")
                    VoskSpeechRecognizer.groupToSubtitles(words)
                }
                android.util.Log.d("EditorVM", "生成字幕条数: ${subs.size}")
                if (subs.isEmpty()) {
                    showError("未识别到语音")
                } else {
                    pushUndo()
                    val st = _uiState.value
                    _uiState.value = st.copy(
                        project = st.project.copy(
                            subtitles = (st.project.subtitles + subs).sortedBy { it.startTime },
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    showError("已生成 ${subs.size} 条字幕")
                }
            } catch (e: Exception) {
                android.util.Log.e("EditorVM", "语音转字幕失败", e)
                showError("语音识别失败：${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isTranscribing = false)
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

    /**
     * 检测当前片段末尾的静止 logo/标语片段，并把出点裁到该位置。
     * 检测基于 freezedetect（最后一段持续到结尾的静止帧）。
     */
    fun detectTailLogo(clipId: String) {
        if (_uiState.value.isDetectingLogo) return
        _uiState.value = _uiState.value.copy(isDetectingLogo = true)

        viewModelScope.launch(Dispatchers.IO) {
            val clip = _uiState.value.project.mainTrack?.clips?.find { it.id == clipId }
            val cut = clip?.let { MediaUtils.detectLogoCut(it.mediaPath) }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isDetectingLogo = false)
                if (clip == null) return@withContext
                if (cut != null && cut < clip.mediaDuration - 0.1) {
                    updateTrim(clipId, clip.trimStart, cut)
                    showError("已去除片尾静止片段，出点裁至 ${"%.2f".format(cut)}s")
                } else {
                    showError("未检测到片尾静止 logo")
                }
            }
        }
    }

    /**
     * 批量检测主轨所有片段的片尾静止 logo/标语，并分别把出点裁到检测位置。
     */
    fun detectTailLogoAll() {
        if (_uiState.value.isDetectingLogo) return
        _uiState.value = _uiState.value.copy(isDetectingLogo = true)

        viewModelScope.launch(Dispatchers.IO) {
            val clips = _uiState.value.project.mainTrack?.clips?.sortedBy { it.timelineStart } ?: emptyList()
            val cuts = clips.map { clip ->
                clip.id to MediaUtils.detectLogoCut(clip.mediaPath)
            }

            withContext(Dispatchers.Main) {
                var removed = 0
                cuts.forEach { (id, cut) ->
                    val clip = _uiState.value.project.mainTrack?.clips?.find { it.id == id }
                    if (clip != null && cut != null && cut < clip.mediaDuration - 0.1) {
                        updateTrim(id, clip.trimStart, cut)
                        removed++
                    }
                }
                _uiState.value = _uiState.value.copy(isDetectingLogo = false)
                showError(
                    if (removed > 0) "已去除 $removed 个片段的片尾静止片段"
                    else "未检测到片尾静止片段"
                )
            }
        }
    }

    /**
     * 在当前播放头位置分割选中的片段（剪映式交互）。
     * 播放头必须落在片段内部（距边界 > 0.05s）才执行。
     */
    fun splitAtPlayhead() {
        val state = _uiState.value
        val pos = state.currentPosition
        val clip = state.project.mainTrack?.clips?.find {
            pos > it.timelineStart + 0.05 && pos < it.timelineEnd - 0.05
        } ?: return
        splitClip(clip.id, pos)
    }

    /**
     * 删除片段（可撤销）。删除后自动重排后续片段并选中相邻片段。
     */
    fun deleteClip(clipId: String) {
        val state = _uiState.value
        val trackIdx = state.project.tracks.indexOfFirst { it.clips.any { c -> c.id == clipId } }
        if (trackIdx < 0) return
        val track = state.project.tracks[trackIdx]
        val clipIdx = track.clips.indexOfFirst { it.id == clipId }
        if (clipIdx < 0) return

        pushUndo()

        val remaining = track.clips.toMutableList().also { it.removeAt(clipIdx) }
        // 重排剩余片段（不可变：全部新建 Clip 实例）
        var pos = 0.0
        val relaid = remaining.map { c ->
            val nc = c.copy(timelineStart = pos)
            pos += nc.timelineDuration
            nc
        }.toMutableList()

        val newTrack = track.copy(clips = relaid)
        val newTracks = state.project.tracks.toMutableList()
        newTracks[trackIdx] = newTrack

        // 优先选中后一个片段，其次前一个
        val nextSelected = relaid.getOrNull(clipIdx)?.id ?: relaid.getOrNull(clipIdx - 1)?.id

        _uiState.value = state.copy(
            project = state.project.copy(
                tracks = newTracks,
                updatedAt = System.currentTimeMillis()
            ),
            selectedClipId = nextSelected
        )
    }

    // ═══════════════════════════════════════
    //  区间删除（入点/出点）
    // ═══════════════════════════════════════

    /** 在当前播放头处打入点（若已有出点且不合法则丢弃出点） */
    fun setInPoint() {
        val state = _uiState.value
        _uiState.value = state.copy(
            inPoint = state.currentPosition,
            outPoint = state.outPoint?.takeIf { it > state.currentPosition + 0.05 }
        )
    }

    /** 在当前播放头处打出出点（若已有入点且不合法则丢弃入点） */
    fun setOutPoint() {
        val state = _uiState.value
        _uiState.value = state.copy(
            outPoint = state.currentPosition,
            inPoint = state.inPoint?.takeIf { it < state.currentPosition - 0.05 }
        )
    }

    /** 清除入点/出点 */
    fun clearRange() {
        _uiState.value = _uiState.value.copy(inPoint = null, outPoint = null)
    }

    /**
     * 删除 [inPoint, outPoint] 区间内的内容，保留其余部分（可撤销）。
     *
     * 对每个片段分四种情况处理：
     * - 完全在区间内 → 整段删除
     * - 区间完全落在片段内部 → 拆成前后两段保留
     * - 片段尾部在区间内 → 保留头部（trimEnd 收到入点）
     * - 片段头部在区间内 → 保留尾部（trimStart 推到出点）
     * 处理完后所有片段首尾重新拼接。
     */
    fun deleteRange() {
        val state = _uiState.value
        val inP = state.inPoint ?: return
        val outP = state.outPoint ?: return
        if (outP - inP < 0.05) return
        val mainTrack = state.project.mainTrack ?: return

        val eps = 0.001
        val kept = mutableListOf<Clip>()

        mainTrack.clips.sortedBy { it.timelineStart }.forEach { clip ->
            val cs = clip.timelineStart
            val ce = clip.timelineEnd
            // 区间边界对应的源时间（sourceTimeAt 自动处理倒放/变速）
            val srcIn = clip.sourceTimeAt(inP)
            val srcOut = clip.sourceTimeAt(outP)
            val srcBegin = clip.trimStart
            val srcEnd = if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration
            when {
                // 完全在区间内 → 删除
                cs >= inP - eps && ce <= outP + eps -> {}
                // 区间完全在片段内部 → 拆成两段
                cs < inP - eps && ce > outP + eps -> {
                    if (clip.reversed) {
                        // 倒放：前段（时间轴靠前）对应源视频后段
                        kept.add(clip.copy(trimStart = srcIn, trimEnd = srcEnd))
                        kept.add(clip.copy(trimStart = srcBegin, trimEnd = srcOut))
                    } else {
                        kept.add(clip.copy(trimEnd = srcIn.coerceAtMost(srcEnd)))
                        kept.add(clip.copy(
                            trimStart = srcOut.coerceAtMost(srcEnd - 0.1),
                            trimEnd = clip.trimEnd
                        ))
                    }
                }
                // 片段尾部落在区间内 → 保留时间轴前半段
                cs < inP - eps && ce > inP + eps -> {
                    if (clip.reversed) {
                        if (srcEnd - srcIn > 0.05) kept.add(clip.copy(trimStart = srcIn))
                    } else {
                        if (srcIn - srcBegin > 0.05) kept.add(clip.copy(trimEnd = srcIn))
                    }
                }
                // 片段头部落在区间内 → 保留时间轴后半段
                cs < outP - eps && ce > outP + eps -> {
                    if (clip.reversed) {
                        if (srcOut - srcBegin > 0.05) kept.add(clip.copy(trimEnd = srcOut))
                    } else {
                        if (srcEnd - srcOut > 0.05) kept.add(clip.copy(trimStart = srcOut))
                    }
                }
                // 无交集 → 原样保留
                else -> kept.add(clip)
            }
        }

        if (kept.isEmpty()) {
            showError("区间包含了全部内容，无法全部删除")
            return
        }

        pushUndo()

        // 首尾重新拼接
        var pos = 0.0
        val relaid = kept.map { c ->
            val nc = c.copy(timelineStart = pos)
            pos += nc.timelineDuration
            nc
        }.toMutableList()

        val newTrack = mainTrack.copy(clips = relaid)
        val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }

        _uiState.value = state.copy(
            project = state.project.copy(
                tracks = newTracks.toMutableList(),
                updatedAt = System.currentTimeMillis()
            ),
            // 播放头回到删除点，选中删除点后的片段
            currentPosition = inP.coerceAtMost(pos),
            selectedClipId = relaid.firstOrNull { it.timelineStart >= inP - 0.05 }?.id
                ?: relaid.lastOrNull()?.id,
            inPoint = null,
            outPoint = null
        )
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

        pushUndo()

        // 分割点的源时间（sourceTimeAt 自动处理倒放/变速）
        val splitSourceTime = clip.sourceTimeAt(atPosition)
        android.util.Log.d("EditorVM", "splitClip: splitSourceTime=$splitSourceTime")

        // 创建两个新片段：
        // 正放：前半段 = [trimStart, 分割点]，后半段 = [分割点, trimEnd]
        // 倒放：时间轴前半段对应源视频后段 [分割点, trimEnd]，后半段对应 [trimStart, 分割点]
        val firstClip: Clip
        val secondClip: Clip
        if (clip.reversed) {
            firstClip = clip.copy(trimStart = splitSourceTime)
            secondClip = clip.copy(
                trimEnd = splitSourceTime,
                timelineStart = clip.timelineStart + splitOffset,
            )
        } else {
            firstClip = clip.copy(trimEnd = splitSourceTime)
            secondClip = clip.copy(
                trimStart = splitSourceTime,
                timelineStart = clip.timelineStart + splitOffset,
            )
        }

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

    /** 切换倒放（对应 reverse_video.py） */
    fun toggleReverse(clipId: String) {
        updateClip(clipId) { it.copy(reversed = !it.reversed) }
    }

    /** 音频淡入淡出时长（对应 audio_fade.py），不超过片段时长一半 */
    fun updateAudioFade(clipId: String, fadeIn: Double, fadeOut: Double) {
        updateClip(clipId) {
            // 注意：音频经过 atrim/atempo/areverse 后时长是 timelineDuration，
            // 不是 effectiveDuration，否则变速片段的淡入淡出会落在错误位置。
            val maxD = it.timelineDuration / 2
            it.copy(
                audioFadeIn = fadeIn.coerceIn(0.0, maxD),
                audioFadeOut = fadeOut.coerceIn(0.0, maxD)
            )
        }
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

    /** 变声音高倍率（>1 高音，<1 低音） */
    fun updatePitchShift(clipId: String, pitch: Double) {
        updateClip(clipId) { it.copy(pitchShift = pitch.coerceIn(0.5, 2.0)) }
    }

    /** 切换降噪 */
    fun toggleNoiseReduction(clipId: String) {
        updateClip(clipId) { it.copy(noiseReduction = !it.noiseReduction) }
    }

    // ═══════════════════════════════════════
    //  文字水印
    // ═══════════════════════════════════════

    fun setTextOverlay(clipId: String, text: String?) {
        // 只在“无文字 ↔ 有文字”转变时推一次撤销栈，避免每输入一个字符都推栈
        val oldEmpty = _uiState.value.selectedClip?.textOverlay.isNullOrBlank()
        val newEmpty = text.isNullOrBlank()
        if (oldEmpty != newEmpty) pushUndo()
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

    fun setImageWatermark(clipId: String, path: String?) {
        updateClip(clipId) { it.copy(imageWatermarkPath = path) }
    }

    fun updateImageWatermark(clipId: String, scale: Double, opacity: Double, position: String) {
        updateClip(clipId) {
            it.copy(
                imageWatermarkScale = scale.coerceIn(0.05, 1.0),
                imageWatermarkOpacity = opacity.coerceIn(0.0, 1.0),
                imageWatermarkPosition = position
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
        if (state.isExporting) return
        exportJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
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
                    // 记入历史记录（首页可回看）
                    exportEngine.recordToHistory(file, state.project)

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

    /** 取消正在进行的导出 */
    fun cancelExport() {
        exportEngine.cancel()
        exportJob?.cancel()
        exportJob = null
        _uiState.value = _uiState.value.copy(
            isExporting = false,
            exportProgress = 0f,
            exportMessage = "",
            errorMessage = "已取消导出"
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 显示一条提示信息（浮层，自动消失） */
    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
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
        val sortedClips = mainTrack.clips.sortedBy { it.timelineStart }

        // 不可变重排：全部新建 Clip 实例，避免旧状态引用被修改
        var pos = 0.0
        val newClips = sortedClips.map { clip ->
            val newClip = clip.copy(timelineStart = pos)
            pos += newClip.timelineDuration
            newClip
        }.toMutableList()

        val newTrack = mainTrack.copy(clips = newClips)
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
        val sortedClips = mainTrack.clips.sortedBy { it.timelineStart }

        val startIdx = sortedClips.indexOfFirst { it.id == clipId }
        if (startIdx < 0) return

        // 起始位置：前一个片段的结尾（如果是第一个片段，从 0 开始）
        val startPos = if (startIdx > 0) {
            sortedClips[startIdx - 1].timelineEnd
        } else {
            0.0
        }

        // 从 startIdx 开始累加 timelineStart（不可变：新建 Clip 实例）
        var pos = startPos
        val newClips = sortedClips.mapIndexed { index, clip ->
            if (index >= startIdx) {
                val newClip = clip.copy(timelineStart = pos)
                pos += newClip.timelineDuration
                newClip
            } else {
                clip
            }
        }.toMutableList()

        val newTrack = mainTrack.copy(clips = newClips)
        val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }
        _uiState.value = state.copy(
            project = state.project.copy(
                tracks = newTracks.toMutableList(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
