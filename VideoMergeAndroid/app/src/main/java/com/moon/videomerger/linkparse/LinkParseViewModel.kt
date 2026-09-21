package com.moon.videomerger.linkparse

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
 * 去水印 UI 状态。与具体平台无关——平台由 [LinkParserRegistry] 从分享文案里识别。
 */
data class LinkParseUiState(
    val input: String = "",
    val platformId: String = "",    // 命中的解析平台 id；空 = 尚未识别出平台
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

class LinkParseViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(LinkParseUiState())
    val uiState: StateFlow<LinkParseUiState> = _uiState.asStateFlow()

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
        val routing = LinkParserRegistry.detect(state.input)
        if (routing == null) {
            _uiState.value = state.copy(errorMessage = "未在文案中找到视频链接，请重新复制分享文案")
            return
        }
        val parser = routing.parser

        viewModelScope.launch {
            _uiState.value = state.copy(
                platformId = parser.id,
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
                val info = withContext(Dispatchers.IO) { parser.parse(routing.shareUrl) }

                // 体积闸门：超限就不发起下载，否则会留下一个永远走不完的进度
                val bytes = info.sizeBytes
                if (bytes != null && bytes > LinkParsePolicy.MAX_DOWNLOAD_BYTES) {
                    throw LinkParseException(
                        "视频约 ${LinkParsePolicy.human(bytes)}，" +
                                "超过单次下载上限 ${LinkParsePolicy.human(LinkParsePolicy.MAX_DOWNLOAD_BYTES)}，已停止下载"
                    )
                }

                _uiState.value = _uiState.value.copy(
                    title = info.title,
                    author = info.author,
                    durationMs = info.durationMs,
                    stage = "下载视频中..."
                )

                val dest = File(
                    getApplication<Application>().cacheDir,
                    "${parser.id}_${System.currentTimeMillis()}.mp4"
                )
                withContext(Dispatchers.IO) {
                    parser.download(info.playUrls, dest) { p ->
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
        val platform = _uiState.value.platformId.ifBlank { "video" }
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                MediaUtils.saveToGallery(
                    getApplication(),
                    file,
                    "${platform}_${System.currentTimeMillis()}.mp4"
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
        _uiState.value = LinkParseUiState(input = _uiState.value.input)
    }

    /** 离开页面时清空整个会话：输入、结果与缓存文件 */
    fun resetAll() {
        _uiState.value.resultFile?.delete()
        _uiState.value = LinkParseUiState()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        resetAll()
    }
}
