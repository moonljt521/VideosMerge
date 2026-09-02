package com.moon.videomerger.gif

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.editor.engine.FFmpegRunner
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * GIF 质量档位（对应 video_to_gif.py 的 fast/normal/high）。
 */
enum class GifQuality(val label: String) {
    FAST("快速"), NORMAL("标准"), HIGH("高质量")
}

/**
 * 视频转 GIF 的 UI 状态。
 */
data class GifUiState(
    val sourceFile: File? = null,
    val sourceName: String = "",
    val sourceDuration: Double = 0.0,   // 秒
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val targetWidth: Int = 480,         // GIF 目标宽度（等比缩放）
    val fps: Int = 10,
    val quality: GifQuality = GifQuality.NORMAL,
    val loop: Boolean = true,
    val isConverting: Boolean = false,
    val progress: Float = 0f,           // 0~1
    val resultFile: File? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

class GifViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(GifUiState())
    val uiState: StateFlow<GifUiState> = _uiState.asStateFlow()

    private var runner: FFmpegRunner? = null

    fun updateTargetWidth(width: Int) {
        _uiState.value = _uiState.value.copy(targetWidth = width)
    }

    fun updateFps(fps: Int) {
        _uiState.value = _uiState.value.copy(fps = fps)
    }

    fun updateQuality(quality: GifQuality) {
        _uiState.value = _uiState.value.copy(quality = quality)
    }

    fun updateLoop(loop: Boolean) {
        _uiState.value = _uiState.value.copy(loop = loop)
    }

    /** 选择视频后：复制到缓存，探测宽高/时长 */
    fun onVideoPicked(uri: Uri) {
        if (_uiState.value.isConverting) return
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val file = withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                val meta = try {
                    withContext(Dispatchers.IO) { MediaUtils.getVideoMeta(file.absolutePath) }
                } catch (e: Exception) {
                    file.delete()
                    throw IllegalStateException("无法读取视频信息，请换一个视频试试")
                }
                if (meta.duration <= 0.0 || meta.width <= 0) {
                    file.delete()
                    throw IllegalStateException("无法读取视频信息，请换一个视频试试")
                }
                // 换新源时清掉上一轮的源文件与结果
                val old = _uiState.value
                if (old.isConverting) return@launch
                old.sourceFile?.delete()
                old.resultFile?.delete()
                _uiState.value = GifUiState(
                    sourceFile = file,
                    sourceName = withContext(Dispatchers.IO) {
                        queryDisplayName(context, uri) ?: "已选视频"
                    },
                    sourceDuration = meta.duration,
                    sourceWidth = meta.width,
                    sourceHeight = meta.height
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "视频导入失败")
            }
        }
    }

    /** 开始转换（palettegen/paletteuse 两步法，见 GifCommandBuilder） */
    fun convert() {
        val state = _uiState.value
        val src = state.sourceFile ?: return
        if (state.isConverting) return

        val out = File(getApplication<Application>().cacheDir, "gif_out_${System.currentTimeMillis()}.gif")
        val (w, h) = GifCommandBuilder.targetDimensions(state.sourceWidth, state.sourceHeight, state.targetWidth)
        val command = GifCommandBuilder.build(
            inputPath = src.absolutePath,
            outputPath = out.absolutePath,
            fps = state.fps,
            width = w,
            height = h,
            quality = state.quality,
            loop = state.loop
        )
        _uiState.value = state.copy(
            isConverting = true, progress = 0f,
            resultFile = null, isSaved = false, errorMessage = null
        )
        runner = FFmpegRunner(
            command = command,
            totalDuration = state.sourceDuration,
            onProgress = { p ->
                // 按整数百分比过滤，减少高频回调触发的重组
                val cur = _uiState.value.progress
                if ((cur * 100).toInt() != (p * 100).toInt()) {
                    _uiState.value = _uiState.value.copy(progress = p)
                }
            },
            onLog = {},
            onComplete = { success, msg ->
                runner = null
                _uiState.value = if (success && out.exists() && out.length() > 0) {
                    _uiState.value.copy(isConverting = false, progress = 1f, resultFile = out)
                } else {
                    out.delete()
                    _uiState.value.copy(
                        isConverting = false, progress = 0f,
                        // 取消不算错误，静默回到参数页
                        errorMessage = if (msg == "已取消") null else "转换失败：$msg"
                    )
                }
            }
        ).also { it.execute() }
    }

    fun cancelConvert() {
        runner?.cancel()
    }

    /** 清除结果，回到参数页（缓存中的 GIF 可直接删除，相册已有拷贝） */
    fun clearResult() {
        _uiState.value.resultFile?.delete()
        _uiState.value = _uiState.value.copy(resultFile = null, isSaved = false, progress = 0f)
    }

    /** 保存到相册 Pictures/VideoMerger/ */
    fun saveResult() {
        val file = _uiState.value.resultFile ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val ok = withContext(Dispatchers.IO) {
                MediaUtils.saveGifToGallery(
                    getApplication(),
                    file,
                    "gif_${System.currentTimeMillis()}.gif"
                ) != null
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
        runner?.cancel()
        _uiState.value.sourceFile?.delete()
        _uiState.value.resultFile?.delete()
    }

    private fun copyUriToCache(context: Context, uri: Uri): File {
        val dest = File(context.cacheDir, "gif_src_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取视频: $uri")
        return dest
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}
