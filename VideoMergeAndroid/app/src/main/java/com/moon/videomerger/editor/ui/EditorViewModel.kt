package com.moon.videomerger.editor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.editor.data.*
import com.moon.videomerger.editor.engine.ExportEngine
import com.moon.videomerger.editor.engine.WatermarkDetector
import com.moon.videomerger.editor.engine.StickerRenderer
import com.moon.videomerger.editor.engine.VoskSpeechRecognizer
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log as AndroidLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    // ── 依赖与状态（★ 必须全部声明在 init 块之前：
    //    viewModelScope 用 Main.immediate，init 里的协程会在构造期间同步执行，
    //    若此时引用了尚未初始化的属性会直接 NPE）──
    private val exportEngine = ExportEngine(app.applicationContext)
    private val appContext = app.applicationContext

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // ★ 播放头独立 flow：播放时以 ~10Hz 高频更新，
    //   与低频的编辑状态（uiState）分离，避免整棵编辑器 UI 跟随播放重组。
    private val _playhead = MutableStateFlow(0.0)
    val playhead: StateFlow<Double> = _playhead.asStateFlow()

    // ── 撤销/重做栈（保存项目快照，最多 30 步）──
    private val undoStack = ArrayDeque<EditorProject>()
    private val redoStack = ArrayDeque<EditorProject>()

    /** 当前导出任务（用于取消） */
    private var exportJob: Job? = null

    /** 当前导入任务（用于取消） */
    private var importJob: Job? = null

    /**
     * ★ 导出成功后已从磁盘清除草稿，这里记录当时的项目引用：
     * 若防抖触发时的还是同一个实例（导出后未再做任何编辑），则不再写回草稿；
     * 用户后续继续编辑会产生新实例，草稿重新生效。
     */
    private var exportedAndClearedProject: EditorProject? = null

    init {
        // 启动时清理过期临时文件（导出成品/缩略图等，历史记录有独立拷贝，可安全删除）
        viewModelScope.launch(Dispatchers.IO) {
            MediaUtils.cleanupStaleCache(appContext)
        }

        // ★ 草稿自动保存：项目变化后防抖 800ms 写盘（单槽位，返回首页不丢内容）
        viewModelScope.launch {
            _uiState
                .map { it.project }
                .distinctUntilChanged()
                .debounce(800)
                .collect { project ->
                    val hasContent = !project.mainTrack?.clips.isNullOrEmpty() || project.subtitles.isNotEmpty()
                    if (hasContent && project !== exportedAndClearedProject) {
                        withContext(Dispatchers.IO) { DraftStore.save(appContext, project) }
                    }
                }
        }
    }

    // ═══════════════════════════════════════
    //  素材导入（统一入口）
    // ═══════════════════════════════════════

    /**
     * 批量导入视频：拷贝到编辑器素材目录（filesDir/editor_media，持久化）→
     * 提取元数据与首帧缩略图。IO 线程执行，逐个上报进度。
     */
    private suspend fun importVideoClips(
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Clip> = withContext(Dispatchers.IO) {
        val seed = System.currentTimeMillis()
        val total = uris.size
        uris.mapIndexed { index, uri ->
            val file = MediaUtils.copyUriToEditorMedia(appContext, uri, seed + index)
            val meta = MediaUtils.getVideoMeta(file.absolutePath)
            val thumb = MediaUtils.loadThumbnailFromFile(file.absolutePath)
            var thumbPath: String? = null
            if (thumb != null) {
                val thumbFile = File(MediaUtils.editorMediaDir(appContext), "thumb_${seed}_${index}.jpg")
                MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)
                thumbPath = thumbFile.absolutePath
            }
            onProgress(index + 1, total)
            Clip(
                mediaPath = file.absolutePath,
                mediaName = file.name,
                mediaDuration = meta.duration,
                width = meta.width,
                height = meta.height,
                hasAudio = meta.hasAudio,
                trimEnd = meta.duration,
                timelineStart = 0.0,
                thumbnailPath = thumbPath,
            )
        }
    }

    /** 进入导入中状态 */
    private fun beginImport(message: String) {
        _uiState.value = _uiState.value.copy(
            isImporting = true, importProgress = 0f, importMessage = message
        )
    }

    /** 结束导入状态（成功路径由各调用方整体重置 uiState） */
    private fun endImportOnError(e: Exception) {
        _uiState.value = _uiState.value.copy(isImporting = false, importMessage = "")
        if (e is kotlinx.coroutines.CancellationException) {
            // 取消属于正常流程（用户点了取消/新的导入接管），但必须重新抛出以完成协程取消
            throw e
        }
        showError("导入失败：${e.message}", MessageSeverity.ERROR)
    }

    // ═══════════════════════════════════════
    //  项目管理
    // ═══════════════════════════════════════

    /**
     * 创建新项目并导入视频。
     */
    fun createProject(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            beginImport("准备导入 ${uris.size} 个视频...")
            try {
                val clips = importVideoClips(uris) { done, total ->
                    _uiState.value = _uiState.value.copy(
                        importProgress = done.toFloat() / total,
                        importMessage = "正在导入视频 $done/$total"
                    )
                }

                // 时间轴布局（统一走重叠模型；初始无转场 = 首尾拼接）
                val mainTrack = Track(type = TrackType.MAIN, clips = relayoutMainTrackClips(clips).toMutableList())
                val project = EditorProject(
                    name = "项目 ${SimpleDateFormat("MMdd-HHmmss", Locale.CHINA).format(Date())}",
                    tracks = mutableListOf(mainTrack),
                )

                undoStack.clear()
                redoStack.clear()

                // ★ 单槽位草稿：新建项目即覆盖旧槽位，清理上一项目遗留的素材文件，
                //   只保留本次导入引用到的（防止 editor_media 无限膨胀）
                withContext(Dispatchers.IO) {
                    val keep = clips.map { it.mediaPath }.toSet() +
                        clips.mapNotNull { it.thumbnailPath }.toSet()
                    MediaUtils.editorMediaDir(appContext).listFiles()?.forEach { f ->
                        if (f.absolutePath !in keep) f.delete()
                    }
                }

                _uiState.value = EditorUiState(
                    project = project,
                    selectedClipId = clips.firstOrNull()?.id
                )
            } catch (e: Exception) {
                endImportOnError(e)
            }
        }
    }

    /** 取消正在进行的导入 */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _uiState.value = _uiState.value.copy(isImporting = false, importMessage = "")
    }

    // ═══════════════════════════════════════
    //  项目管理
    // ═══════════════════════════════════════

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
        val currentPos = _playhead.value
        val newPosition = if (clip != null &&
            (currentPos < clip.timelineStart - 0.05 || currentPos >= clip.timelineEnd - 0.05)
        ) {
            clip.timelineStart
        } else {
            currentPos
        }
        _playhead.value = newPosition
        _uiState.value = _uiState.value.copy(
            selectedClipId = clipId,
            currentPanel = if (clipId != null) _uiState.value.currentPanel else ToolPanel.NONE
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
        // 去重：栈顶与当前项目结构一致时不再入栈（连续空操作不会污染撤销栈）
        if (undoStack.isNotEmpty() && sameStructure(undoStack.last(), _uiState.value.project)) return
        undoStack.addLast(deepCopy(_uiState.value.project))
        if (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
        _uiState.value = _uiState.value.copy(canUndo = true, canRedo = false)
    }

    /** 项目结构比较（忽略 updatedAt 等非编辑字段），用于撤销栈去重 */
    private fun sameStructure(a: EditorProject, b: EditorProject): Boolean =
        a.tracks == b.tracks && a.subtitles == b.subtitles

    /**
     * 撤销后尽量保留选中与面板：若选中片段在恢复的项目中仍存在（id 不变），
     * 则保持选中并停留在当前面板；否则清空选中、关闭面板。
     */
    private fun restoreSelection(project: EditorProject): Pair<String?, ToolPanel> {
        val sel = _uiState.value.selectedClipId
        val exists = sel != null && project.tracks.any { t -> t.clips.any { it.id == sel } }
        return if (exists) {
            _uiState.value.selectedClipId to _uiState.value.currentPanel
        } else {
            null to ToolPanel.NONE
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(deepCopy(_uiState.value.project))
        val prev = undoStack.removeLast()
        val (sel, panel) = restoreSelection(prev)
        _uiState.value = _uiState.value.copy(
            project = prev,
            selectedClipId = sel,
            currentPanel = panel,
            canUndo = undoStack.isNotEmpty(),
            canRedo = true
        )
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(deepCopy(_uiState.value.project))
        val next = redoStack.removeLast()
        val (sel, panel) = restoreSelection(next)
        _uiState.value = _uiState.value.copy(
            project = next,
            selectedClipId = sel,
            currentPanel = panel,
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
     * ★ 设置项目画布尺寸（v1.1 画布面板）。
     * 宽高需为 16 的倍数（h264_mediacodec 硬编码 macroblock 对齐），面板侧已对齐；此处再兜底一次。
     */
    fun setCanvasSize(width: Int, height: Int) {
        val w = ((width + 15) / 16) * 16
        val h = ((height + 15) / 16) * 16
        val p = _uiState.value.project
        if (p.canvasWidth == w && p.canvasHeight == h) return
        pushUndo()
        _uiState.value = _uiState.value.copy(
            project = p.copy(canvasWidth = w, canvasHeight = h, updatedAt = System.currentTimeMillis())
        )
    }

    /**
     * 恢复草稿 —— 首页「继续编辑」入口调用。
     * 校验素材文件仍存在，剔除丢失的片段，避免导出阶段崩溃。
     */
    fun loadDraft() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { DraftStore.load(appContext) }
            if (loaded == null) {
                showError("草稿不存在或已损坏")
                return@launch
            }

            val cleaned = withContext(Dispatchers.IO) {
                val newTracks = loaded.tracks.mapNotNull { track ->
                    val kept = track.clips.filter { File(it.mediaPath).exists() }
                    if (track.type == TrackType.MAIN && kept.isEmpty()) {
                        null // 主轨全丢 → 整轨移除
                    } else {
                        track.copy(clips = kept.toMutableList())
                    }
                }.toMutableList()
                // 主轨重排（转场重叠布局），画中画保持自由定位
                val fixedTracks = newTracks.map { t ->
                    if (t.type == TrackType.MAIN) t.copy(clips = relayoutMainTrackClips(t.clips).toMutableList()) else t
                }.toMutableList()
                loaded.copy(tracks = fixedTracks)
            }

            undoStack.clear()
            redoStack.clear()
            _playhead.value = 0.0
            _uiState.value = EditorUiState(
                project = cleaned,
                selectedClipId = cleaned.mainTrack?.clips?.firstOrNull()?.id
            )
        }
    }

    /**
     * 重置内存状态 —— 返回首页时调用。
     * ★ 草稿已由自动保存落盘，这里只清空运行态；删除草稿走 DraftStore.clear()。
     */
    fun resetState() {
        _uiState.value = EditorUiState()
        _playhead.value = 0.0
    }

    /**
     * 添加更多视频到主轨末尾（在编辑器中追加，不重建项目）。
     */
    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            beginImport("准备添加 ${uris.size} 个视频...")
            try {
                val newClips = importVideoClips(uris) { done, total ->
                    _uiState.value = _uiState.value.copy(
                        importProgress = done.toFloat() / total,
                        importMessage = "正在添加视频 $done/$total"
                    )
                }
                pushUndo()

                val state = _uiState.value.copy(
                    // ★ 导入成功，清除导入中状态（否则浮层永远不消失）
                    isImporting = false, importProgress = 0f, importMessage = ""
                )
                val mainTrack = state.project.mainTrack
                if (mainTrack == null) {
                    // 没有主轨，直接创建
                    val track = Track(type = TrackType.MAIN, clips = relayoutMainTrackClips(newClips).toMutableList())
                    _uiState.value = state.copy(
                        project = state.project.copy(tracks = mutableListOf(track)),
                        // ★ 当前无选中片段时自动选中第一个，保证工具栏可用
                        selectedClipId = state.selectedClipId ?: newClips.firstOrNull()?.id
                    )
                } else {
                    // 追加到主轨末尾（含与前一片段的转场重叠），统一重排
                    val allClips = mainTrack.clips + newClips
                    val newTrack = mainTrack.copy(clips = relayoutMainTrackClips(allClips).toMutableList())
                    val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }
                    _uiState.value = state.copy(
                        project = state.project.copy(tracks = newTracks.toMutableList()),
                        selectedClipId = state.selectedClipId ?: newClips.firstOrNull()?.id
                    )
                }
            } catch (e: Exception) {
                endImportOnError(e)
            }
        }
    }

    /**
     * 添加画中画叠加层（PICTURE 轨）：把选中的视频/图片叠加在主轨之上。
     * 默认从当前播放头位置开始，叠加层占据整个源时长。
     *
     * ★ 图片与贴纸规则统一：mediaDuration 上限一致（300s，图片可循环填充），
     *   默认时长 3s；用户后续可在画中画面板把时长拉长。
     */
    fun addPipOverlay(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            beginImport("准备添加画中画...")
            try {
                val seed = System.currentTimeMillis()
                val maxImageDuration = 300.0
                val defaultImageDuration = 3.0
                val newClips = withContext(Dispatchers.IO) {
                    uris.mapIndexed { index, uri ->
                        val file = MediaUtils.copyUriToEditorMedia(appContext, uri, seed + index)
                        var thumbPath: String? = null

                        if (MediaUtils.isImageFile(file.absolutePath)) {
                            // 图片叠加：默认 3 秒、无音轨；上限与贴纸一致（300s）
                            val dims = MediaUtils.getImageDimensions(file.absolutePath)
                            val thumb = MediaUtils.loadImageFromFile(file.absolutePath)
                            if (thumb != null) {
                                val thumbFile = File(MediaUtils.editorMediaDir(appContext), "thumb_pip_${seed}_${index}.jpg")
                                MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)
                                thumbPath = thumbFile.absolutePath
                            }
                            Clip(
                                mediaPath = file.absolutePath,
                                mediaName = file.name,
                                mediaDuration = maxImageDuration,
                                width = dims?.first ?: 0,
                                height = dims?.second ?: 0,
                                hasAudio = false,
                                trimEnd = defaultImageDuration,
                                timelineStart = 0.0,
                                thumbnailPath = thumbPath,
                                pipEnabled = true,
                                isImage = true,
                            )
                        } else {
                            val meta = MediaUtils.getVideoMeta(file.absolutePath)
                            val thumb = MediaUtils.loadThumbnailFromFile(file.absolutePath)
                            if (thumb != null) {
                                val thumbFile = File(MediaUtils.editorMediaDir(appContext), "thumb_pip_${seed}_${index}.jpg")
                                MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)
                                thumbPath = thumbFile.absolutePath
                            }
                            Clip(
                                mediaPath = file.absolutePath,
                                mediaName = file.name,
                                mediaDuration = meta.duration,
                                width = meta.width,
                                height = meta.height,
                                hasAudio = meta.hasAudio,
                                trimEnd = meta.duration,
                                timelineStart = 0.0,
                                thumbnailPath = thumbPath,
                                pipEnabled = true,
                            )
                        }
                    }
                }

                pushUndo()
                val state = _uiState.value.copy(
                    // ★ 导入成功，清除导入中状态（否则浮层永远不消失）
                    isImporting = false, importProgress = 0f, importMessage = ""
                )
                // 默认从播放头处开始依次排布（不可变：copy 新实例）
                var pos = _playhead.value
                val placedClips = newClips.map { c ->
                    val placed = c.copy(timelineStart = pos)
                    pos += placed.timelineDuration
                    placed
                }

                val existingPip = state.project.tracks.find { it.type == TrackType.PICTURE }
                val newTracks = state.project.tracks.toMutableList()
                if (existingPip == null) {
                    newTracks.add(Track(type = TrackType.PICTURE, clips = placedClips.toMutableList()))
                } else {
                    val idx = newTracks.indexOfFirst { it.id == existingPip.id }
                    newTracks[idx] = existingPip.copy(clips = (existingPip.clips + placedClips).toMutableList())
                }

                _uiState.value = state.copy(
                    project = state.project.copy(tracks = newTracks, updatedAt = System.currentTimeMillis()),
                    selectedClipId = placedClips.firstOrNull()?.id,
                    selectedTrackId = newTracks.find { it.type == TrackType.PICTURE }?.id,
                    currentPanel = ToolPanel.PICTURE
                )
            } catch (e: Exception) {
                endImportOnError(e)
            }
        }
    }

    /** 添加贴纸：把 emoji 渲染成 PNG，作为图片叠加到当前播放头处（时长 3s，宽度 20%） */
    fun addSticker(emoji: String) {
        viewModelScope.launch {
            val png = withContext(Dispatchers.IO) {
                StickerRenderer.renderToPng(appContext, emoji)
            }
            pushUndo()
            val dur = 3.0
            val clip = Clip(
                mediaPath = png.absolutePath,
                mediaName = emoji,
                mediaDuration = 300.0,   // 图片可循环，留足时长上限
                width = 256,
                height = 256,
                hasAudio = false,
                trimEnd = dur,
                timelineStart = _playhead.value,
                thumbnailPath = png.absolutePath,
                pipEnabled = true,
                isImage = true,
                pipWidth = 0.2,
            )

            val state = _uiState.value
            val existingPip = state.project.tracks.find { it.type == TrackType.PICTURE }
            val newTracks = state.project.tracks.toMutableList()
            if (existingPip == null) {
                newTracks.add(Track(type = TrackType.PICTURE, clips = mutableListOf(clip)))
            } else {
                val idx = newTracks.indexOfFirst { it.id == existingPip.id }
                newTracks[idx] = existingPip.copy(clips = (existingPip.clips + clip).toMutableList())
            }
            // 选中新贴纸，并自动切到「画中画」面板直接调位置/大小/时间
            _uiState.value = state.copy(
                project = state.project.copy(tracks = newTracks, updatedAt = System.currentTimeMillis()),
                selectedClipId = clip.id,
                selectedTrackId = newTracks.find { it.type == TrackType.PICTURE }?.id,
                currentPanel = ToolPanel.PICTURE,
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

    /** 更新画中画时间：开始时间（时间轴绝对秒）+ 时长（秒） */
    fun updatePipTiming(clipId: String, start: Double, duration: Double) {
        updateClip(clipId) { clip ->
            val safeStart = start.coerceAtLeast(0.0)
            val safeDur = duration.coerceIn(0.5, 300.0)
            if (clip.isImage) {
                // 图片可循环：时长直接映射到 trimEnd
                clip.copy(
                    timelineStart = safeStart,
                    trimEnd = safeDur.coerceAtMost(clip.mediaDuration),
                )
            } else {
                // 视频：时长 = (trimEnd - trimStart) / speed → trimEnd = trimStart + duration * speed
                val newTrimEnd = (clip.trimStart + safeDur * clip.speed).coerceAtMost(clip.mediaDuration)
                clip.copy(timelineStart = safeStart, trimEnd = newTrimEnd)
            }
        }
    }

    /** 在当前播放头处添加画中画位置关键帧（记录片段当前静态位置 pipX/pipY） */
    fun addPipKeyframe(clipId: String) {
        val time = _playhead.value
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
        // 如果已到结尾，先回到开头再播放；空项目不进入播放态
        if (totalDur <= 0.0) return
        val cur = _playhead.value
        if (cur >= totalDur - 0.1) _playhead.value = 0.0
        _uiState.value = state.copy(isPlaying = !state.isPlaying)
    }

    /** 暂停播放（退后台/导出开始时调用，不改变播放头位置） */
    fun pausePlayback() {
        if (_uiState.value.isPlaying) {
            _uiState.value = _uiState.value.copy(isPlaying = false)
        }
    }

    fun seekTo(position: Double) {
        _playhead.value = position.coerceIn(0.0, _uiState.value.project.totalDuration)
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
        retimelineAll()
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
                    // ★ VM 内部触发的裁剪不经过面板手势，必须自己推撤销栈
                    pushUndo()
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
                // ★ 批量裁剪是 VM 内部行为，整批只推一次撤销栈（有实际裁剪才推）
                val willCut = cuts.any { (id, cut) ->
                    val c = _uiState.value.project.mainTrack?.clips?.find { it.id == id }
                    c != null && cut != null && cut < c.mediaDuration - 0.1
                }
                if (willCut) pushUndo()
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
     * 在当前播放头位置分割片段（剪映式交互）。
     * ★ 优先分割当前选中的片段（含画中画轨）；未选中或播放头不在其中时，
     *   回退到主轨上播放头所在片段。播放头必须落在片段内部（距边界 > 0.05s）。
     */
    fun splitAtPlayhead() {
        val state = _uiState.value
        val pos = _playhead.value
        val selected = state.selectedClip
        val clip = if (selected != null &&
            pos > selected.timelineStart + 0.05 && pos < selected.timelineEnd - 0.05
        ) {
            selected
        } else {
            state.project.mainTrack?.clips?.find {
                pos > it.timelineStart + 0.05 && pos < it.timelineEnd - 0.05
            } ?: return
        }
        splitClip(clip.id, pos)
    }

    /**
     * 删除片段（可撤销）。删除后自动重排后续片段并选中相邻片段。
     * ★ 删除主轨片段时，锚点（被删片段原起点）之后的字幕/画中画整体前移，
     *   保持与主轨画面对齐。
     */
    fun deleteClip(clipId: String) {
        val state = _uiState.value
        val trackIdx = state.project.tracks.indexOfFirst { it.clips.any { c -> c.id == clipId } }
        if (trackIdx < 0) return
        val track = state.project.tracks[trackIdx]
        val clipIdx = track.clips.indexOfFirst { it.id == clipId }
        if (clipIdx < 0) return
        val deletedClip = track.clips[clipIdx]

        pushUndo()

        val remaining = track.clips.toMutableList().also { it.removeAt(clipIdx) }
        // 主轨重排剩余片段（转场重叠布局）；
        // 其他轨（画中画等）为自由定位，删除后保持其余片段位置不变
        val relaid = if (track.type == TrackType.MAIN) {
            relayoutMainTrackClips(remaining).toMutableList()
        } else {
            remaining
        }

        val newTrack = track.copy(clips = relaid)
        val newTracks = state.project.tracks.toMutableList()
        newTracks[trackIdx] = newTrack

        var newProject = state.project.copy(
            tracks = newTracks,
            updatedAt = System.currentTimeMillis()
        )

        // ★ 主轨时长变化 → 锚点之后的叠加元素联动平移
        if (track.type == TrackType.MAIN) {
            val delta = newProject.totalDuration - state.project.totalDuration
            newProject = shiftOverlaysAfter(newProject, deletedClip.timelineStart, delta)
        }

        // 优先选中后一个片段，其次前一个
        val nextSelected = relaid.getOrNull(clipIdx)?.id ?: relaid.getOrNull(clipIdx - 1)?.id

        _uiState.value = state.copy(
            project = newProject,
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
            inPoint = _playhead.value,
            outPoint = state.outPoint?.takeIf { it > _playhead.value + 0.05 }
        )
    }

    /** 在当前播放头处打出出点（若已有入点且不合法则丢弃入点） */
    fun setOutPoint() {
        val state = _uiState.value
        _uiState.value = state.copy(
            outPoint = _playhead.value,
            inPoint = state.inPoint?.takeIf { it < _playhead.value - 0.05 }
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

        // 首尾重新拼接（统一走转场重叠布局）
        val relaid = relayoutMainTrackClips(kept).toMutableList()

        val newTrack = mainTrack.copy(clips = relaid)
        val newTracks = state.project.tracks.map { if (it.id == mainTrack.id) newTrack else it }

        var newProject = state.project.copy(
            tracks = newTracks.toMutableList(),
            updatedAt = System.currentTimeMillis()
        )

        // ★ 主轨缩短 → 入点之后的字幕/画中画整体前移，保持与画面对齐
        val delta = newProject.totalDuration - state.project.totalDuration
        newProject = shiftOverlaysAfter(newProject, inP, delta)

        // 播放头回到删除点，选中删除点后的片段
        _playhead.value = inP.coerceAtMost(relaid.lastOrNull()?.timelineEnd ?: 0.0)
        _uiState.value = state.copy(
            project = newProject,

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
        // 变速后该片段的时间轴时长改变（也影响与相邻片段的转场重叠），需要重排
        retimelineAll()
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
        // ★ 转场类型改变重叠时长（NONE=0.01s，其他为设定值），时间轴需重排
        retimelineAll()
    }

    fun updateTransitionDuration(clipId: String, duration: Double) {
        updateClip(clipId) { it.copy(transitionDuration = duration.coerceIn(0.2, 3.0)) }
        // ★ 转场时长直接决定与下一片段的重叠量，时间轴需重排
        retimelineAll()
    }

    // ═══════════════════════════════════════
    //  去水印
    // ═══════════════════════════════════════

    /**
     * 添加水印区域。
     * @param corner null=画面中央；"tl"/"tr"/"bl"/"br" = 对应角落预设
     *   （抖音水印常用位置，一键框住，再微调大小）
     */
    fun addWatermarkRegion(clipId: String, corner: String? = null) {
        pushUndo()
        updateClip(clipId) { clip ->
            val nth = clip.watermarkRegions.size
            val region = when (corner) {
                "tl" -> WatermarkRegion(x = 0.02, y = 0.03, w = 0.45, h = 0.18)
                "tr" -> WatermarkRegion(x = 0.53, y = 0.03, w = 0.45, h = 0.18)
                "bl" -> WatermarkRegion(x = 0.02, y = 0.79, w = 0.45, h = 0.18)
                "br" -> WatermarkRegion(x = 0.53, y = 0.79, w = 0.45, h = 0.18)
                else -> {
                    // 多个区域时纵向错开，避免完全重叠
                    val y = (0.42 + nth * 0.08).coerceAtMost(0.8)
                    WatermarkRegion(x = 0.55, y = y, w = 0.3, h = 0.12)
                }
            }
            clip.copy(watermarkRegions = clip.watermarkRegions + region)
        }
    }

    /** 自动检测静态水印（时域方差分析），结果追加到片段 */
    fun autoDetectWatermarks(clipId: String) {
        if (_uiState.value.isDetectingWatermark) return
        val clip = _uiState.value.selectedClip ?: return
        _uiState.value = _uiState.value.copy(isDetectingWatermark = true)
        viewModelScope.launch(Dispatchers.IO) {
            val found = WatermarkDetector.detect(appContext, clip.mediaPath)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isDetectingWatermark = false)
                if (found.isEmpty()) {
                    showError("未检测到静态水印，可手动框选区域")
                    return@withContext
                }
                pushUndo()
                updateClip(clipId) { c ->
                    // 与已有区域明显重叠的检测结果不重复添加
                    val merged = c.watermarkRegions +
                        found.filter { r -> c.watermarkRegions.none { it.overlaps(r) } }
                    c.copy(watermarkRegions = merged)
                }
                showError("检测到 ${found.size} 处水印，可微调位置和强度")
            }
        }
    }

    /** 更新水印区域（位置/大小/模式/强度/时间段） */
    fun updateWatermarkRegion(clipId: String, index: Int, region: WatermarkRegion) {
        updateClip(clipId) { clip ->
            if (index !in clip.watermarkRegions.indices) clip
            else clip.copy(watermarkRegions = clip.watermarkRegions.toMutableList().also {
                val end = if (region.endTime <= region.startTime) {
                    region.endTime // ≤起点 = 整段生效
                } else {
                    region.endTime.coerceAtLeast(region.startTime + 0.1)
                }
                it[index] = region.copy(
                    x = region.x.coerceIn(0.0, 0.98),
                    y = region.y.coerceIn(0.0, 0.98),
                    // ★ 保证 x+w / y+h 不超过 1（区域完整落在画面内）
                    w = region.w.coerceIn(0.01, 1.0 - region.x.coerceIn(0.0, 0.99)),
                    h = region.h.coerceIn(0.01, 1.0 - region.y.coerceIn(0.0, 0.99)),
                    startTime = region.startTime.coerceIn(0.0, clip.mediaDuration),
                    endTime = end.coerceAtMost(clip.mediaDuration),
                )
            })
        }
    }

    /** 删除水印区域 */
    fun removeWatermarkRegion(clipId: String, index: Int) {
        pushUndo()
        updateClip(clipId) { clip ->
            if (index !in clip.watermarkRegions.indices) clip
            else clip.copy(watermarkRegions = clip.watermarkRegions.toMutableList().also { it.removeAt(index) })
        }
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
                            // ★ 成片已入相册+历史记录，本项目视为完成：
                            //   清除草稿文件，避免下次进首页误提示「有未导出的草稿」。
                            //   素材文件保留（当前会话仍可编辑），新建项目时会统一清理。
                            exportedAndClearedProject = _uiState.value.project
                            withContext(Dispatchers.IO) { DraftStore.clear(appContext) }
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
                                errorMessage = "保存失败: ${e.message}",
                                errorSeverity = MessageSeverity.ERROR
                            )
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportProgress = 0f,
                        exportMessage = "",
                        errorMessage = "导出失败: ${e.message}",
                        errorSeverity = MessageSeverity.ERROR
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
        _uiState.value = _uiState.value.copy(errorMessage = null, errorSeverity = MessageSeverity.INFO)
    }

    /** 显示一条提示信息（浮层，自动消失） */
    fun showError(message: String, severity: MessageSeverity = MessageSeverity.INFO) {
        _uiState.value = _uiState.value.copy(errorMessage = message, errorSeverity = severity)
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
     *
     * ★ 布局规则（与导出 xfade 一致，见 relayoutMainTrackClips）：
     *   clip[i+1].timelineStart = clip[i].timelineEnd - 有效转场重叠时长。
     *   即转场期间两个片段在时间轴上重叠，时间轴总时长 = 导出成片时长。
     *
     * 当 trim/speed/transition 改变导致片段时长或重叠变化时，
     * 必须重算后续片段的 timelineStart，否则时间轴会错位/重叠不一致。
     */
    private fun retimelineAll() {
        val state = _uiState.value
        val mainTrack = state.project.mainTrack ?: return

        // 不可变重排：relayoutMainTrackClips 返回全新 Clip 实例，避免旧状态引用被修改
        val newClips = relayoutMainTrackClips(mainTrack.clips.sortedBy { it.timelineStart }).toMutableList()

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
