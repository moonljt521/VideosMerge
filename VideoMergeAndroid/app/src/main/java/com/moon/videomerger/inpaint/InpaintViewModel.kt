package com.moon.videomerger.inpaint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.editor.engine.WatermarkDetector
import com.moon.videomerger.util.MediaUtils
import com.moon.videomerger.util.VideoHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频去水印（内容修复）页状态机。
 */
data class InpaintUiState(
    val sourceFile: File? = null,
    val sourceName: String = "",
    val meta: InpaintVideoMeta? = null,

    val probeBitmap: Bitmap? = null,
    val afterROI: Bitmap? = null,
    val maskROI: Bitmap? = null,
    val maskRect: RectPx? = null,
    val maskPixels: Int = 0,
    val hasPreview: Boolean = false,

    /** 归一化水印框（显示方向） */
    val bbox: RectF = RectF(0.60f, 0.82f, 0.96f, 0.96f),
    val probeTime: Double = 0.0,
    val dilate: Int = 13,
    val showAfter: Boolean = true,
    val showMask: Boolean = false,

    val isDetecting: Boolean = false,
    val isPreparing: Boolean = false,
    val hint: String? = null,

    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val phase: String = "",
    val resultFile: File? = null,

    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
)

class InpaintViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = InpaintEngine(app)
    private val appContext: Application get() = getApplication()

    private val _uiState = MutableStateFlow(InpaintUiState())
    val uiState: StateFlow<InpaintUiState> = _uiState.asStateFlow()

    private var prepared: InpaintPrepared? = null
    private var previewJob: Job? = null
    @Volatile private var previewGeneration = 0
    @Volatile private var cancelFlag = false
    private val probeCache by lazy { File(appContext.cacheDir, "inpaint_probe_current.png") }

    // ---------------------------------------------------------------- 选片

    fun onVideoPicked(uri: Uri) {
        if (_uiState.value.isProcessing) return
        cancelFlag = false
        previewJob?.cancel()
        prepared = null
        viewModelScope.launch {
            try {
                val context = appContext
                val file = withContext(Dispatchers.IO) {
                    val dest = File(context.cacheDir, "inpaint_src_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { out -> input.copyTo(out) }
                    } ?: throw IllegalStateException("无法读取视频")
                    dest
                }
                val old = _uiState.value
                old.sourceFile?.delete()
                old.resultFile?.delete()

                val (bmp, meta) = withContext(Dispatchers.IO) {
                    val first = File(context.cacheDir, "inpaint_first.png")
                    val bitmap = engine.extractProbeFrame(file.absolutePath, 0.0, first)
                        ?: throw IllegalStateException("无法读取视频画面，请换一个视频试试")
                    val m = engine.probeMeta(file.absolutePath, bitmap.width, bitmap.height)
                        ?: throw IllegalStateException("无法读取视频信息，请换一个视频试试")
                    bitmap to m
                }
                _uiState.value = InpaintUiState(
                    sourceFile = file,
                    sourceName = file.name,
                    meta = meta,
                    probeBitmap = bmp,
                    probeTime = min(meta.duration * 0.3, max(meta.duration - 0.05, 0.0)),
                    hint = if (meta.isVFR) "检测到可变帧率视频，处理后时长可能有轻微偏差" else null,
                )
                autoDetect()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "视频导入失败")
            }
        }
    }

    fun autoDetect() {
        val state = _uiState.value
        val file = state.sourceFile ?: return
        val meta = state.meta ?: return
        if (state.isDetecting) return
        _uiState.value = state.copy(isDetecting = true)

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val regions = WatermarkDetector.detect(appContext, file.absolutePath)
                    val best = regions.maxByOrNull { it.w * it.h }
                    var bbox: RectF
                    var hint: String? = null
                    if (best != null) {
                        bbox = clampBBox(RectF(
                            best.x.toFloat(), best.y.toFloat(),
                            (best.x + best.w).toFloat(), (best.y + best.h).toFloat()))
                    } else {
                        bbox = clampBBox(_uiState.value.bbox)
                        hint = "未自动检测到水印，已放置默认位置，请手动调整"
                    }
                    val t = engine.autoProbeTime(file.absolutePath, meta.duration, bbox)
                    Triple(bbox, t, hint)
                }
                val (bbox, t, hint) = result
                _uiState.value = _uiState.value.copy(
                    bbox = bbox,
                    probeTime = if (t > 0) t else _uiState.value.probeTime,
                    isDetecting = false,
                    hint = hint ?: _uiState.value.hint,
                )
                refreshPreview()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDetecting = false,
                    errorMessage = "自动检测失败，请手动框选水印",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 框选

    fun setBBox(bbox: RectF, generate: Boolean = true) {
        _uiState.value = _uiState.value.copy(bbox = clampBBox(bbox))
        if (generate) schedulePreview()
    }

    fun setProbeTime(t: Double) {
        val duration = _uiState.value.meta?.duration ?: return
        _uiState.value = _uiState.value.copy(probeTime = min(max(t, 0.0), max(duration - 0.05, 0.0)))
        schedulePreview()
    }

    fun setDilate(v: Int) {
        _uiState.value = _uiState.value.copy(dilate = v.coerceIn(5, 25))
        schedulePreview()
    }

    fun toggleAfter() { _uiState.value = _uiState.value.copy(showAfter = !_uiState.value.showAfter) }
    fun toggleMask() { _uiState.value = _uiState.value.copy(showMask = !_uiState.value.showMask) }

    private fun schedulePreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(380)
            refreshPreview()
        }
    }

    fun refreshPreview() {
        val state = _uiState.value
        val file = state.sourceFile ?: return
        val meta = state.meta ?: return
        val gen = ++previewGeneration

        val bboxPx = bboxPixels(state.bbox, meta)
        val probeTime = state.probeTime
        val dilate = state.dilate
        _uiState.value = state.copy(isPreparing = true, errorMessage = null)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.prepare(file.absolutePath, meta, probeTime, bboxPx, dilate, probeCache)
            }
            if (gen != previewGeneration) return@launch
            result.onSuccess { p ->
                prepared = p
                _uiState.value = _uiState.value.copy(
                    probeBitmap = p.probeBitmap,
                    afterROI = p.afterROI,
                    maskROI = p.maskROI,
                    maskRect = p.roi,
                    maskPixels = p.mask.pixelCount,
                    hasPreview = true,
                    isPreparing = false,
                )
            }.onFailure { e ->
                prepared = null
                _uiState.value = _uiState.value.copy(
                    hasPreview = false,
                    isPreparing = false,
                    hint = e.message,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 修复

    fun startRepair() {
        val state = _uiState.value
        val file = state.sourceFile ?: return
        val p = prepared
        if (!state.hasPreview || p == null) {
            _uiState.value = state.copy(errorMessage = "请先生成修复预览")
            return
        }
        if (state.isProcessing) return
        state.resultFile?.delete()

        val out = File(appContext.cacheDir, "inpaint_out_${System.currentTimeMillis()}.mp4")
        cancelFlag = false
        _uiState.value = state.copy(
            isProcessing = true, progress = 0f, phase = "分析画面",
            resultFile = null, isSaved = false, errorMessage = null,
        )

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.repair(
                    sourcePath = file.absolutePath,
                    prepared = p,
                    outputPath = out.absolutePath,
                    onProgress = { progress, phase ->
                        _uiState.value = _uiState.value.copy(
                            progress = progress.toFloat(), phase = phase,
                        )
                    },
                    isCancelled = { cancelFlag },
                )
            }
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false, progress = 1f, resultFile = it,
                )
            }.onFailure { e ->
                out.delete()
                if (cancelFlag) {
                    _uiState.value = _uiState.value.copy(isProcessing = false, progress = 0f, phase = "")
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false, progress = 0f, phase = "",
                        errorMessage = e.message ?: "修复失败",
                    )
                }
            }
        }
    }

    fun cancel() {
        if (!_uiState.value.isProcessing) return
        cancelFlag = true
        com.arthenica.ffmpegkit.FFmpegKit.cancel()
        _uiState.value = _uiState.value.copy(phase = "正在取消…")
    }

    fun clearResult() {
        _uiState.value.resultFile?.delete()
        _uiState.value = _uiState.value.copy(resultFile = null, isSaved = false, progress = 0f)
    }

    fun saveResult() {
        val state = _uiState.value
        val file = state.resultFile ?: return
        val meta = state.meta ?: return
        if (state.isSaving) return
        _uiState.value = state.copy(isSaving = true)

        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val ts = System.currentTimeMillis()
                val uri = MediaUtils.saveToGallery(appContext, file, "去水印_$ts.mp4")
                if (uri != null) {
                    val thumb = MediaUtils.loadThumbnailFromFile(file.absolutePath)
                    val thumbFile = File(appContext.cacheDir, "repair_thumb_$ts.jpg")
                    if (thumb != null) MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)
                    VideoHistoryStore.addToHistory(
                        appContext, file, thumbFile, "去水印",
                        meta.duration, meta.displayWidth, meta.displayHeight,
                    )
                    true
                } else false
            }
            _uiState.value = if (ok) {
                _uiState.value.copy(isSaving = false, isSaved = true)
            } else {
                _uiState.value.copy(isSaving = false, errorMessage = "保存到相册失败")
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        cancelFlag = true
        _uiState.value.sourceFile?.delete()
        _uiState.value.resultFile?.delete()
    }

    // ---------------------------------------------------------------- 几何

    companion object {
        fun clampBBox(r: RectF): RectF {
            var left = r.left.coerceIn(0f, 0.98f)
            var top = r.top.coerceIn(0f, 0.98f)
            var right = r.right.coerceIn(left + 0.02f, 0.98f)
            var bottom = r.bottom.coerceIn(top + 0.01f, 0.98f)
            if (right <= left) right = min(left + 0.02f, 0.98f)
            if (bottom <= top) bottom = min(top + 0.01f, 0.98f)
            return RectF(left, top, right, bottom)
        }

        fun bboxPixels(norm: RectF, meta: InpaintVideoMeta): RectPx = RectPx(
            (norm.left * meta.displayWidth).roundToInt(),
            (norm.top * meta.displayHeight).roundToInt(),
            max((norm.width() * meta.displayWidth).roundToInt(), 4),
            max((norm.height() * meta.displayHeight).roundToInt(), 4),
        )
    }
}
