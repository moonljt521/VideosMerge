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
 * 画中画叠加层形状
 */
enum class PipShape(val displayName: String) {
    RECT("矩形"),
    ROUNDED("圆角"),
    CIRCLE("圆形"),
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
 * 画中画位置关键帧。
 * @param time 项目时间轴上的绝对时间（秒）
 * @param x 归一化水平位置 0~1（0=左，1=右）
 * @param y 归一化垂直位置 0~1（0=上，1=下）
 */
data class PipKeyframe(
    val time: Double,
    val x: Double,
    val y: Double,
)

/**
 * 在时间 t 处对关键帧做线性插值，返回归一化位置 (x, y)。
 * 无关键帧时返回 fallback（即片段静态位置）。
 */
fun interpolatePipPosition(
    keyframes: List<PipKeyframe>,
    t: Double,
    fallbackX: Double,
    fallbackY: Double
): Pair<Double, Double> {
    if (keyframes.isEmpty()) return fallbackX to fallbackY
    val sorted = keyframes.sortedBy { it.time }
    if (t <= sorted.first().time) return sorted.first().x to sorted.first().y
    if (t >= sorted.last().time) return sorted.last().x to sorted.last().y
    for (i in 0 until sorted.size - 1) {
        val a = sorted[i]
        val b = sorted[i + 1]
        if (t in a.time..b.time) {
            val f = if (b.time == a.time) 0.0 else (t - a.time) / (b.time - a.time)
            return (a.x + (b.x - a.x) * f) to (a.y + (b.y - a.y) * f)
        }
    }
    return sorted.last().x to sorted.last().y
}

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

    // 倒放（reverse/areverse 滤镜，对应 reverse_video.py）
    val reversed: Boolean = false,

    // 音频
    val volume: Double = 1.0,       // 1.0 = 原始音量
    val audioFadeIn: Double = 0.0,  // 音频淡入时长（秒），对应 audio_fade.py
    val audioFadeOut: Double = 0.0, // 音频淡出时长（秒）
    val pitchShift: Double = 1.0,   // 变声音高倍率（>1 高音，<1 低音，1=不变）
    val noiseReduction: Boolean = false, // 是否降噪

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

    // 图片水印
    val imageWatermarkPath: String? = null, // 水印图片路径
    val imageWatermarkScale: Double = 0.2,  // 相对画布宽度的缩放比例
    val imageWatermarkOpacity: Double = 1.0,// 透明度 0~1
    val imageWatermarkPosition: String = "bottom-right",

    // 模糊背景（竖屏→横屏 或 横屏→竖屏 时，用模糊画面填充背景）
    val blurBgEnabled: Boolean = false,  // 是否启用模糊背景
    val blurStrength: Int = 12,          // 模糊强度（avgblur 半径）

    // 转场（该片段到下一个片段的转场）
    val transition: TransitionEffect = TransitionEffect.NONE,
    val transitionDuration: Double = 0.8, // 转场时长（秒）

    // 截断（抖音尾部 logo）
    val logoCutTime: Double? = null,    // logo 截断时间点

    // 画中画 / 叠加层（PICTURE 轨片段使用，主轨忽略）
    val pipEnabled: Boolean = false,   // 是否作为画中画叠加层
    val isImage: Boolean = false,      // 叠加源是否为静态图片（图片无音轨、需 loop 填充时长）
    val pipX: Double = 0.0,            // 叠加层左上角 x（占画布宽比例 0~1）
    val pipY: Double = 0.0,            // 叠加层左上角 y（占画布高比例 0~1）
    val pipWidth: Double = 0.3,        // 叠加层宽度（占画布宽比例 0.05~1）
    val pipOpacity: Double = 1.0,      // 透明度 0~1
    val pipShape: PipShape = PipShape.RECT, // 叠加层形状
    val pipCornerRadius: Double = 0.15,     // 圆角半径（占画中画宽度比例 0~0.5，ROUNDED 用）
    val pipBorder: Boolean = false,         // 是否描边
    val pipBorderWidth: Double = 0.02,      // 描边宽度（占画中画宽度比例 0~0.2）

    // 画中画位置关键帧（time 为项目时间轴绝对秒，x/y 为归一化位置 0~1）
    val pipKeyframes: List<PipKeyframe> = emptyList(),

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

    /**
     * 时间轴位置 → 源视频时间。
     * 正向播放：timelineStart 处对应 trimStart，随时间轴递增；
     * 倒放：timelineStart 处对应 trimEnd，随时间轴递减。
     */
    fun sourceTimeAt(timelinePos: Double): Double {
        val offset = timelinePos - timelineStart
        val end = if (trimEnd > 0) trimEnd else mediaDuration
        return if (reversed) end - offset * speed else trimStart + offset * speed
    }

    /** 源视频时间 → 时间轴位置（sourceTimeAt 的逆运算） */
    fun timelinePosOf(sourceTime: Double): Double {
        val end = if (trimEnd > 0) trimEnd else mediaDuration
        return if (reversed) {
            timelineStart + (end - sourceTime) / speed
        } else {
            timelineStart + (sourceTime - trimStart) / speed
        }
    }
}

/**
 * 字幕条目（时间轴绝对秒）
 */
data class Subtitle(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTime: Double,
    val endTime: Double,
)

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
    val subtitles: List<Subtitle> = emptyList(),
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
