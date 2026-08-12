package com.moon.videomerger.editor.data

import java.util.UUID

/**
 * 轨道类型
 */
enum class TrackType {
    /** 主视频轨 */
    MAIN,
    /** 画中画轨 */
    PICTURE,
    /** 文字轨 */
    TEXT,
    /** 音频轨 */
    AUDIO
}

/**
 * 效果类型（对应 29 个 Python 脚本）
 */
enum class EffectType {
    // 基础剪辑
    TRIM,        // 时间段裁剪
    CROP,        // 画面空间裁剪
    ROTATE,      // 旋转/翻转
    SCALE,       // 缩放
    REVERSE,     // 倒放
    SPEED,       // 变速
    // 视觉特效
    COLOR_FILTER,   // 滤镜调色
    TEXT_WATERMARK,  // 文字水印
    IMAGE_WATERMARK, // 图片水印
    BLUR_BG,        // 模糊背景
    TRANSITION,     // 转场
    LETTERBOX,       // 电影黑边
    FILM_GRAIN,      // 胶片颗粒
    MOSAIC,          // 马赛克
    LUT,             // LUT 调色
    FREEZE_FRAME,    // 画面定格
    // 音频
    VOLUME,          // 音量调整
    AUDIO_FADE,      // 音频淡入淡出
    MIX_BGM,         // 背景音乐混合
    EXTRACT_AUDIO,   // 音频提取
    REPLACE_AUDIO,   // 音频替换
    // 导出
    CONVERT_FORMAT,  // 格式转换
    COMPRESS,        // 压缩
    GIF_EXPORT,      // GIF 导出
    SCREENSHOT,      // 截图
    INTRO_OUTRO,     // 片头片尾
    STABILIZE,       // 防抖
    BATCH,           // 批量处理
    CONCAT,          // 视频拼接
}

/**
 * 滤镜预设
 */
enum class FilterPreset(val displayName: String, val brightness: Double, val contrast: Double, val saturation: Double, val hue: Double, val gamma: Double) {
    NONE("原图", 0.0, 0.0, 0.0, 0.0, 1.0),
    VINTAGE("复古", 0.08, -0.15, -0.3, 15.0, 1.1),
    WARM("暖色", 0.05, 0.05, 0.2, 10.0, 1.0),
    COOL("冷色", -0.03, 0.1, -0.1, -15.0, 1.0),
    VIVID("鲜艳", 0.02, 0.2, 0.5, 0.0, 1.0),
    BW("黑白", 0.0, 0.1, -1.0, 0.0, 1.0),
    BRIGHT("明亮", 0.15, -0.05, 0.1, 0.0, 0.9),
    DARK("暗调", -0.1, 0.25, -0.15, 0.0, 1.2),
}

/**
 * 旋转角度
 */
enum class RotationMode(val displayName: String, val value: Int) {
    NONE("不旋转", 0),
    CW_90("顺时针90°", 90),
    CCW_90("逆时针90°", -90),
    R_180("180°", 180),
}

/**
 * 转场效果（xfade 滤镜支持的所有效果）
 */
enum class TransitionEffect(val key: String, val displayName: String) {
    NONE("none", "无转场"),
    FADE("fade", "淡入淡出"),
    WIPELEFT("wipeleft", "向左擦除"),
    WIPERIGHT("wiperight", "向右擦除"),
    WIPEUP("wipeup", "向上擦除"),
    WIPEDOWN("wipedown", "向下擦除"),
    SLIDELEFT("slideleft", "向左滑动"),
    SLIDERIGHT("slideright", "向右滑动"),
    SLIDEUP("slideup", "向上滑动"),
    SLIDEDOWN("slidedown", "向下滑动"),
    CIRCLEOPEN("circleopen", "圆形展开"),
    CIRCLECLOSE("circleclose", "圆形关闭"),
    DISSOLVE("dissolve", "溶解"),
    PIXELIZE("pixelize", "像素化"),
    FADEWHITE("fadewhite", "淡入白色"),
    FADEBLACK("fadeblack", "淡入黑色"),
    ZOOMIN("zoomin", "放大"),
    HBLUR("hblur", "水平模糊"),
    RADIAL("radial", "径向"),
    SMOOTHLEFT("smoothleft", "平滑左滑"),
    SMOOTHRIGHT("smoothright", "平滑右滑"),
}

/**
 * 效果（应用到片段上的处理）
 */
data class Effect(
    val id: String = UUID.randomUUID().toString(),
    val type: EffectType,
    val params: Map<String, Any> = emptyMap()
)

/**
 * 视频片段（时间轴上的一个片段）
 */
data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val mediaPath: String,          // 源文件路径
    val mediaName: String,          // 源文件名
    val mediaDuration: Double,      // 源视频总时长
    val width: Int,                 // 源视频宽
    val height: Int,                // 源视频高
    val hasAudio: Boolean,          // 源视频是否有音频

    // 时间裁剪
    val trimStart: Double = 0.0,    // 入点（秒）
    val trimEnd: Double = 0.0,      // 出点（秒），0 表示到结尾

    // 时间轴位置
    var timelineStart: Double = 0.0, // 在时间轴上的起始位置

    // 变速
    val speed: Double = 1.0,        // 1.0 = 正常速度

    // 音频
    val volume: Double = 1.0,       // 1.0 = 原始音量

    // 旋转/翻转
    val rotation: Int = 0,          // 旋转角度 (0/90/180/-90)
    val hflip: Boolean = false,     // 水平镜像
    val vflip: Boolean = false,     // 垂直镜像

    // 滤镜
    val filterPreset: FilterPreset = FilterPreset.NONE,
    val brightness: Double = 0.0,  // 手动亮度微调
    val contrast: Double = 0.0,     // 手动对比度微调
    val saturation: Double = 0.0,   // 手动饱和度微调

    // 文字水印
    val textOverlay: String? = null,    // 文字内容
    val textSize: Int = 28,              // 字号
    val textColor: String = "white",     // 颜色
    val textPosition: String = "bottom-right", // 位置
    val textOpacity: Float = 1.0f,       // 透明度 0~1
    val textBorder: Boolean = true,      // 是否描边（黑色描边增强可读性）

    // 模糊背景（竖屏→横屏 或 横屏→竖屏 时，用模糊画面填充背景）
    val blurBgEnabled: Boolean = false,  // 是否启用模糊背景
    val blurStrength: Int = 12,          // 模糊强度（avgblur 半径）

    // 转场（该片段到下一个片段的转场）
    val transition: TransitionEffect = TransitionEffect.NONE,
    val transitionDuration: Double = 0.8, // 转场时长（秒）

    // 截断（抖音尾部 logo）
    val logoCutTime: Double? = null,    // logo 截断时间点

    // 缩略图路径
    val thumbnailPath: String? = null,
) {
    /** 有效时长（裁剪后） */
    val effectiveDuration: Double
        get() = (if (trimEnd > 0) trimEnd else mediaDuration) - trimStart

    /** 在时间轴上的有效时长（变速后） */
    val timelineDuration: Double
        get() = effectiveDuration / speed

    /** 在时间轴上的结束位置 */
    val timelineEnd: Double
        get() = timelineStart + timelineDuration
}

/**
 * 轨道
 */
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val type: TrackType,
    val clips: MutableList<Clip> = mutableListOf(),
    val muted: Boolean = false,
    val hidden: Boolean = false,
) {
    /** 轨道总时长 */
    val duration: Double
        get() = clips.maxOfOrNull { it.timelineEnd } ?: 0.0
}

/**
 * 编辑项目
 *
 * 注意：canvasWidth / canvasHeight 必须是 16 的倍数（h264_mediacodec 硬件编码器要求 macroblock 对齐）。
 */
data class EditorProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "未命名项目",
    val canvasWidth: Int = 1088,    // 1088 = 68*16（接近 1080，满足 mediacodec 对齐）
    val canvasHeight: Int = 1920,   // 1920 = 120*16
    val fps: Int = 30,
    val tracks: MutableList<Track> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 项目总时长 */
    val totalDuration: Double
        get() = tracks.maxOfOrNull { it.duration } ?: 0.0

    /** 主视频轨 */
    val mainTrack: Track?
        get() = tracks.find { it.type == TrackType.MAIN }
}
