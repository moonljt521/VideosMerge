package com.moon.videomerger.editor.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 轨道类型
 */
@Serializable
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
 * 水印区域处理方式
 */
@Serializable
enum class WatermarkMode(val displayName: String) {
    BLUR("模糊"),
    MOSAIC("马赛克"),
}

/**
 * 去水印区域 —— 片段画面上的一个矩形（归一化坐标 0~1，相对源帧宽高）+ 生效时间段。
 *
 * 导出时对该区域做模糊/马赛克覆盖；预览中红框标识（仅时间段内显示）。
 * 时间为【源视频秒】（与检测抽帧一致），导出/预览各自换算。
 *
 * @param startTime 生效开始（源秒）；endTime <= startTime 表示整个片段生效
 * @param strength BLUR=模糊半径（相对源宽 720 的像素数）；MOSAIC=马赛克块尺寸（px）
 */
@Serializable
data class WatermarkRegion(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val mode: WatermarkMode = WatermarkMode.BLUR,
    val strength: Int = 14,
    val startTime: Double = 0.0,
    val endTime: Double = -1.0,
) {
    /** 某源时间点是否处于生效期 */
    fun activeAt(sourceTime: Double): Boolean =
        endTime <= startTime || (sourceTime >= startTime && sourceTime <= endTime)

    /** 是否与另一区域大面积重叠（用于自动检测结果去重，忽略时间维） */
    fun overlaps(other: WatermarkRegion, iouThreshold: Double = 0.3): Boolean {
        val ix = (x + w).coerceAtMost(other.x + other.w) - x.coerceAtLeast(other.x)
        val iy = (y + h).coerceAtMost(other.y + other.h) - y.coerceAtLeast(other.y)
        if (ix <= 0 || iy <= 0) return false
        val inter = ix * iy
        val union = w * h + other.w * other.h - inter
        return inter / union > iouThreshold
    }
}

/**
 * 滤镜预设
 */
@Serializable
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
@Serializable
enum class RotationMode(val displayName: String, val value: Int) {
    NONE("不旋转", 0),
    CW_90("顺时针90°", 90),
    CCW_90("逆时针90°", -90),
    R_180("180°", 180),
}

/**
 * 画中画叠加层形状
 */
@Serializable
enum class PipShape(val displayName: String) {
    RECT("矩形"),
    ROUNDED("圆角"),
    CIRCLE("圆形"),
}

/**
 * NONE 转场的等效重叠时长（秒）。
 * 导出时 xfade 链不能中断，NONE 也用 0.01s 的极短淡入淡出衔接，
 * 时间轴布局必须使用同一数值，否则累计漂移。
 */
const val TRANSITION_NONE_OVERLAP = 0.01

/**
 * 相邻片段间的有效转场重叠时长（秒）—— 时间轴布局与导出 xfade 的唯一来源。
 *
 * - NONE → 极短重叠（见 TRANSITION_NONE_OVERLAP），保持导出链连续；
 * - 非 NONE → 用户设定值，但不超过较短片段时长的 80%（否则 xfade offset 非法）。
 *
 * 时间轴语义：next.timelineStart = prev.timelineEnd - 本函数返回值，
 * 即转场期间两个片段的画面在时间轴上是重叠的，与 xfade 的实际行为一致。
 */
fun effectiveTransitionOverlap(prev: Clip, next: Clip): Double {
    if (prev.transition == TransitionEffect.NONE) return TRANSITION_NONE_OVERLAP
    val maxD = minOf(prev.timelineDuration, next.timelineDuration) * 0.8
    return prev.transitionDuration.coerceIn(0.01, maxD.coerceAtLeast(0.01))
}

/**
 * 转场效果（xfade 滤镜支持的所有效果）
 */
@Serializable
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
 * 画中画位置关键帧。
 * @param time 项目时间轴上的绝对时间（秒）
 * @param x 归一化水平位置 0~1（0=左，1=右）
 * @param y 归一化垂直位置 0~1（0=上，1=下）
 */
@Serializable
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
@Serializable
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

    // 时间轴位置（由 relayoutMainTrackClips 等统一重排；不可变，
    // 任何位置调整都必须通过 copy 产生新实例，保证撤销快照隔离）
    val timelineStart: Double = 0.0, // 在时间轴上的起始位置

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

    // 去水印区域（静态水印覆盖：模糊/马赛克，归一化源帧坐标）
    val watermarkRegions: List<WatermarkRegion> = emptyList(),

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
@Serializable
data class Subtitle(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTime: Double,
    val endTime: Double,
)

/**
 * 按列表顺序重排主轨片段的时间轴位置（纯函数，返回全新 Clip 列表）。
 *
 * 布局规则：首片段从 0 开始；后续每个片段在前一个片段结束前
 * 「有效转场重叠时长」处开始（转场 = 重叠，见 effectiveTransitionOverlap）。
 * 这样时间轴总时长与导出成片时长严格一致。
 */
fun relayoutMainTrackClips(clips: List<Clip>): List<Clip> {
    var nextStart = 0.0
    return clips.mapIndexed { i, clip ->
        val placed = clip.copy(timelineStart = nextStart)
        nextStart = if (i < clips.lastIndex) {
            placed.timelineEnd - effectiveTransitionOverlap(placed, clips[i + 1])
        } else {
            placed.timelineEnd
        }
        placed
    }
}

/**
 * 主轨时长变化后，把锚点之后的叠加元素（字幕、画中画等非主轨片段）整体平移 delta，
 * 保证它们与主轨画面的对齐关系不因主轨增删而错位（剪映式联动）。
 *
 * 规则：
 * - 只平移「起点在锚点之后（含锚点附近 ε 容差）」的元素；
 * - 跨越锚点的元素保持绝对时间不变（其内容归属在剪辑语义上不明确，v1 不处理）；
 * - 平移后钳制到 ≥ 0。
 *
 * @param anchor 主轨发生变化的时间轴位置（如被删区间的入点）
 * @param delta  主轨总时长的变化量（删除为负、插入为正），由调用方先算好
 */
fun shiftOverlaysAfter(
    project: EditorProject,
    anchor: Double,
    delta: Double
): EditorProject {
    if (delta == 0.0) return project
    val eps = 0.05

    val newSubtitles = project.subtitles.map { sub ->
        if (sub.startTime >= anchor - eps) {
            sub.copy(
                startTime = (sub.startTime + delta).coerceAtLeast(0.0),
                endTime = (sub.endTime + delta).coerceAtLeast(0.05)
            )
        } else {
            sub
        }
    }

    val newTracks = project.tracks.map { track ->
        if (track.type == TrackType.MAIN) {
            track
        } else {
            track.copy(clips = track.clips.map { c ->
                if (c.timelineStart >= anchor - eps) {
                    c.copy(timelineStart = (c.timelineStart + delta).coerceAtLeast(0.0))
                } else {
                    c
                }
            }.toMutableList())
        }
    }

    return project.copy(subtitles = newSubtitles, tracks = newTracks.toMutableList())
}

/**
 * 相邻片段间的转场重叠区（时间轴上的一段区间）。
 *
 * 区间 = [next.timelineStart, prev.timelineEnd]，与导出 xfade 的作用范围一致：
 * 播放头进入区间即开始转场，progress 0→1 对应第二片段从不可见到完全可见。
 */
data class TransitionZone(val prev: Clip, val next: Clip) {
    val start: Double get() = next.timelineStart
    val end: Double get() = prev.timelineEnd
    val duration: Double get() = (end - start).coerceAtLeast(1e-6)

    /** 播放头在转场内的进度 0~1 */
    fun progress(timelinePos: Double): Double =
        ((timelinePos - start) / duration).coerceIn(0.0, 1.0)
}

/** 小于此重叠时长的转场不做双轨预览（NONE 的 0.01s 视为硬切换） */
const val TRANSITION_PREVIEW_MIN_OVERLAP = 0.05

/**
 * 找到播放头所在的转场重叠区；不在任何转场内返回 null。
 */
fun findTransitionZone(clips: List<Clip>, timelinePos: Double): TransitionZone? {
    for (i in 0 until clips.lastIndex) {
        val a = clips[i]
        val b = clips[i + 1]
        if (a.transition == TransitionEffect.NONE) continue
        if (effectiveTransitionOverlap(a, b) < TRANSITION_PREVIEW_MIN_OVERLAP) continue
        if (timelinePos >= b.timelineStart && timelinePos < a.timelineEnd) {
            return TransitionZone(a, b)
        }
    }
    return null
}

/**
 * 轨道
 */
@Serializable
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
@Serializable
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
