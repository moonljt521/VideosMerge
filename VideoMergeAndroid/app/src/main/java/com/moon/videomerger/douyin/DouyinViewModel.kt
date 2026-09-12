package com.moon.videomerger.douyin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 抖音去水印 UI 状态。
 */
data class DouyinUiState(
    val input: String = "",
    val isProcessing: Boolean = false,
    val stage: String = "",          // 当前阶段文案（解析链接中/下载视频中）
    val progress: Float = -1f,       // 下载进度 0~1；-1 表示无进度（解析阶段）
    val title: String = "",
    val author: String = "",
    val durationMs: Long = 0,
    val resultFile: File? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

class DouyinViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(DouyinUiState())
    val uiState: StateFlow<DouyinUiState> = _uiState.asStateFlow()

    fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(input = text)
    }

    /** 解析分享文案 → 获取无水印地址 → 下载到缓存 */
    fun parseAndDownload() {
        val state = _uiState.value
        if (state.isProcessing) return
        if (state.input.isBlank()) {
            _uiState.value = state.copy(errorMessage = "请先粘贴分享文案或链接")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(
                isProcessing = true,
                stage = "解析链接中...",
                progress = -1f,
                title = "",
                author = "",
                durationMs = 0,
                resultFile = null,
                isSaved = false,
                errorMessage = null
            )
            try {
                val info = withContext(Dispatchers.IO) {
                    val url = DouyinParser.extractShareUrl(state.input)
                        ?: throw IllegalStateException("未在文案中找到视频链接，请重新复制分享文案")
                    val pageUrl = DouyinParser.resolveRedirect(url)
                    val videoId = DouyinParser.extractVideoId(pageUrl)
                        ?: throw IllegalStateException("无法从链接中提取视频 ID，请确认是作品分享链接")
                    DouyinParser.fetchVideoInfo(videoId)
                }

                _uiState.value = _uiState.value.copy(
                    title = info.title,
                    author = info.author,
                    durationMs = info.durationMs,
                    stage = "下载视频中..."
                )

                val dest = File(
                    getApplication<Application>().cacheDir,
                    "douyin_${System.currentTimeMillis()}.mp4"
                )
                withContext(Dispatchers.IO) {
                    DouyinParser.downloadVideo(info.playUrls, dest) { p ->
                        val v = p ?: -1f
                        val cur = _uiState.value.progress
                        // 按整数百分比过滤，减少高频回调触发的重组
                        if (v < 0 || (cur * 100).toInt() != (v * 100).toInt()) {
                            _uiState.value = _uiState.value.copy(progress = v)
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    progress = 1f,
                    stage = "",
                    resultFile = dest
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    stage = "",
                    errorMessage = e.message ?: "解析失败，请重试"
                )
            }
        }
    }

    /** 保存到相册 Movies/VideoMerger/ */
    fun saveResult() {
        val file = _uiState.value.resultFile ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val ok = withContext(Dispatchers.IO) {
                MediaUtils.saveToGallery(
                    getApplication(),
                    file,
                    "douyin_${System.currentTimeMillis()}.mp4"
                ) != null
            }
            _uiState.value = if (ok) {
                _uiState.value.copy(isSaving = false, isSaved = true)
            } else {
                _uiState.value.copy(isSaving = false, errorMessage = "保存到相册失败")
            }
        }
    }

    /** 清除结果，回到输入态 */
    fun clearResult() {
        _uiState.value.resultFile?.delete()
        _uiState.value = DouyinUiState(input = _uiState.value.input)
    }

    /** 离开页面时清空整个会话：输入、结果与缓存文件 */
    fun resetAll() {
        _uiState.value.resultFile?.delete()
        _uiState.value = DouyinUiState()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        resetAll()
    }
}
