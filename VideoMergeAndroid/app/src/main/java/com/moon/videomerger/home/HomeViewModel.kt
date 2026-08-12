package com.moon.videomerger.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moon.videomerger.util.VideoHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页 UI 状态
 *
 * @param history 最近编辑/导出的视频历史记录列表
 */
data class HomeUiState(
    val history: List<VideoHistoryStore.HistoryEntry> = emptyList()
)

/**
 * 首页 ViewModel
 *
 * 负责加载并刷新「最近编辑」历史记录，供 HomeScreen 展示。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // 首次进入首页时加载历史记录
        refreshHistory()
    }

    /**
     * 从本地存储重新加载历史记录。
     * 在首页可见、或从编辑器返回后调用，以展示最新数据。
     */
    fun refreshHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                history = VideoHistoryStore.loadHistory(getApplication())
            )
        }
    }
}
