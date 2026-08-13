package com.moon.videomerger.editor.data

/**
 * 工具面板类型（底部工具栏当前显示的面板）
 */
enum class ToolPanel {
    NONE,           // 显示一级工具栏
    TRIM,           // 裁剪面板
    SPEED,          // 变速面板
    FILTER,         // 滤镜面板
    TEXT,           // 文字面板
    IMAGE_WATERMARK,// 图片水印
    AUDIO,          // 音频面板
    BLUR_BG,        // 模糊背景面板
    TRANSITION,     // 转场面板
    EXPORT,         // 导出面板
}

/**
 * 编辑器 UI 状态
 */
data class EditorUiState(
    val project: EditorProject = EditorProject(),
    val selectedClipId: String? = null,
    val selectedTrackId: String? = null,
    val currentPanel: ToolPanel = ToolPanel.NONE,
    val isPlaying: Boolean = false,
    val currentPosition: Double = 0.0,       // 播放头位置（秒）
    val inPoint: Double? = null,             // 区间删除：入点（时间轴秒）
    val outPoint: Double? = null,            // 区间删除：出点（时间轴秒）
    val timelineScale: Float = 1f,           // 时间轴缩放
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportMessage: String = "",
    val outputPath: String? = null,
    val errorMessage: String? = null,
    val isDetectingLogo: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    /** 当前选中的 Clip */
    val selectedClip: Clip?
        get() = project.tracks
            .flatMap { it.clips }
            .find { it.id == selectedClipId }

    /** 当前选中的 Track */
    val selectedTrack: Track?
        get() = project.tracks.find { it.id == selectedTrackId }

    /** 当前选中片段后面是否还有片段（转场需要前后两个片段） */
    val selectedClipCanTransition: Boolean
        get() {
            val clip = selectedClip ?: return false
            val sorted = project.mainTrack?.clips?.sortedBy { it.timelineStart } ?: return false
            val idx = sorted.indexOfFirst { it.id == clip.id }
            return idx >= 0 && idx < sorted.lastIndex
        }
}
