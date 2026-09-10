package com.moon.videomerger.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.merger.MergeEngine
import com.moon.videomerger.merger.MergeOptions
import com.moon.videomerger.merger.MergeResult
import com.moon.videomerger.merger.MergeType
import com.moon.videomerger.util.MediaUtils
import com.moon.videomerger.util.VideoHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * UI 状态
 */
data class MergeUiState(
    val selectedVideos: List<Uri> = emptyList(),
    val mergeType: MergeType = MergeType.GRID,
    val options: MergeOptions = MergeOptions(),
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val logLines: String = "",
    val mergeResult: MergeResult? = null,
    val isSaved: Boolean = false,
    val isSaving: Boolean = false,
    val isFullscreen: Boolean = false,
    val fullscreenVideoPath: String? = null,
    val errorMessage: String? = null,
    val history: List<VideoHistoryStore.HistoryEntry> = emptyList(),
    val showHistoryDialog: Boolean = false
)

class MergeViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = MergeEngine(app.applicationContext)

    private val _uiState = MutableStateFlow(MergeUiState())
    val uiState: StateFlow<MergeUiState> = _uiState.asStateFlow()

    init {
        // 加载本地历史
        val history = engine.loadHistory()
        _uiState.value = _uiState.value.copy(history = history)
    }

    fun onVideosSelected(uris: List<Uri>) {
        _uiState.value = _uiState.value.copy(
            selectedVideos = uris,
            errorMessage = null,
            mergeResult = null,
            isSaved = false,
            logLines = ""
        )
    }

    /**
     * 追加视频（预览卡缩略图条末尾的「+」按钮）。
     * 按 Uri 字符串去重，避免重复选择同一视频；输入变化后旧结果作废。
     */
    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existing = _uiState.value.selectedVideos.map { it.toString() }.toSet()
        val fresh = uris.filter { it.toString() !in existing }
        if (fresh.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            selectedVideos = _uiState.value.selectedVideos + fresh,
            errorMessage = null,
            mergeResult = null,
            isSaved = false,
            logLines = ""
        )
    }

    /**
     * 移除单个已选视频（缩略图条右上角「−」按钮）。
     * 输入变化后旧结果作废；列表清空时顺带清掉错误提示。
     */
    fun removeVideo(index: Int) {
        val list = _uiState.value.selectedVideos.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _uiState.value = _uiState.value.copy(
            selectedVideos = list,
            errorMessage = if (list.isEmpty()) null else _uiState.value.errorMessage,
            mergeResult = null,
            isSaved = false,
            logLines = ""
        )
    }

    fun onMergeTypeSelected(type: MergeType) {
        val state = _uiState.value
        // 照片墙是随机布局：首次进入时固定一个种子，预览与导出才会是同一版式
        val options = if (type == MergeType.PHOTO_WALL && state.options.photoWallSeed == null) {
            state.options.copy(photoWallSeed = newPhotoWallSeed())
        } else {
            state.options
        }
        _uiState.value = state.copy(mergeType = type, options = options)
    }

    /** 照片墙「换一批」：换种子即换版式，预览与导出同步。 */
    fun shufflePhotoWall() {
        _uiState.value = _uiState.value.copy(
            options = _uiState.value.options.copy(photoWallSeed = newPhotoWallSeed())
        )
    }

    private fun newPhotoWallSeed(): Int = Random.nextInt(1, Int.MAX_VALUE)

    fun updateOptions(options: MergeOptions) {
        _uiState.value = _uiState.value.copy(options = options)
    }

    fun startMerge() {
        val state = _uiState.value
        if (state.selectedVideos.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "请先选择视频")
            return
        }
        if (state.selectedVideos.size == 1) {
            _uiState.value = state.copy(errorMessage = "至少需要选择 2 个视频")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(
                isProcessing = true,
                progress = 0f,
                statusMessage = "准备中...",
                errorMessage = null,
                mergeResult = null,
                isSaved = false,
                logLines = ""
            )

            val result = engine.merge(
                videoUris = state.selectedVideos,
                mergeType = state.mergeType,
                options = state.options,
                onProgress = { progress ->
                    val msg = when {
                        progress < 0.1f -> "复制视频文件中..."
                        progress < 0.98f -> "合并中... ${(progress * 100).toInt()}%"
                        else -> "生成缩略图..."
                    }
                    _uiState.value = _uiState.value.copy(
                        progress = progress,
                        statusMessage = msg
                    )
                },
                onLog = { line ->
                    _uiState.value = _uiState.value.copy(
                        logLines = _uiState.value.logLines + line
                    )
                }
            )

            result.fold(
                onSuccess = { mergeResult ->
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        progress = 1f,
                        statusMessage = "合并完成！预览中",
                        mergeResult = mergeResult
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        progress = 0f,
                        statusMessage = "",
                        errorMessage = e.message ?: "未知错误"
                    )
                }
            )
        }
    }

    /**
     * 保存到相册 + 本地历史。
     */
    fun saveResult() {
        val result = _uiState.value.mergeResult ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val saveResult = engine.saveResult(result)
            saveResult.fold(
                onSuccess = {
                    // 刷新历史
                    val history = engine.loadHistory()
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        isSaved = true,
                        history = history
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = "保存失败: ${e.message}"
                    )
                }
            )
        }
    }

    /**
     * 全屏播放合并结果。
     */
    fun openFullscreen() {
        val path = _uiState.value.mergeResult?.outputFile?.absolutePath ?: return
        _uiState.value = _uiState.value.copy(
            isFullscreen = true,
            fullscreenVideoPath = path
        )
    }

    /**
     * 全屏播放历史视频。
     */
    fun openFullscreen(path: String) {
        _uiState.value = _uiState.value.copy(
            isFullscreen = true,
            fullscreenVideoPath = path
        )
    }

    fun closeFullscreen() {
        _uiState.value = _uiState.value.copy(
            isFullscreen = false,
            fullscreenVideoPath = null
        )
    }

    /**
     * 清除合并结果，回到初始状态，并清理输出文件。
     */
    fun clearResult() {
        MediaUtils.cleanupOutputFiles(getApplication())
        _uiState.value = _uiState.value.copy(
            mergeResult = null,
            isSaved = false,
            logLines = ""
        )
    }

    /**
     * 清除全部操作，恢复初始状态。
     */
    fun clearAll() {
        MediaUtils.cleanupOutputFiles(getApplication())
        _uiState.value = _uiState.value.copy(
            selectedVideos = emptyList(),
            isProcessing = false,
            progress = 0f,
            statusMessage = "",
            logLines = "",
            mergeResult = null,
            isSaved = false,
            isSaving = false,
            errorMessage = null
        )
    }

    fun showHistory() {
        val history = engine.loadHistory()
        _uiState.value = _uiState.value.copy(history = history, showHistoryDialog = true)
    }

    fun hideHistory() {
        _uiState.value = _uiState.value.copy(showHistoryDialog = false)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun deleteHistoryEntry(entry: VideoHistoryStore.HistoryEntry) {
        engine.deleteHistoryEntry(entry)
        val history = engine.loadHistory()
        _uiState.value = _uiState.value.copy(history = history)
    }

    override fun onCleared() {
        super.onCleared()
        MediaUtils.cleanupOutputFiles(getApplication())
    }
}
