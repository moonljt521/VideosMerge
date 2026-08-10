package com.moon.videomerger.merger

import java.util.Locale

/**
 * 合并类型与对应选项。
 */
enum class MergeType {
    /** 均匀网格布局 */
    GRID,
    /** 一个主窗口 + 若干副窗口 */
    COLLAGE,
    /** 照片墙风格（大小错落、边框阴影） */
    PHOTO_WALL
}

/**
 * 合并选项。
 */
data class MergeOptions(
    // 通用
    val canvasWidth: Int = 1920,     // 输出画布宽度
    val canvasHeight: Int = 1080,    // 输出画布高度

    // Grid 选项
    val gridCellSize: Int = 720,     // 单元格较长边像素

    // Collage 选项
    val collageMainIndex: Int = 0,   // 主窗口视频索引
    val collageMainRatio: Double = 0.62, // 主窗口占比 (0~1)
    val collageOrient: String = "left", // 主窗位置: left/right/top/bottom
    val collageFit: String = "cover",   // cover=裁剪填充, contain=缩放留边

    // Photo Wall 选项
    val photoWallSeed: Int? = null,  // 随机种子，固定布局可复现

    // 通用选项
    val gap: Int = 0,                // 窗口间距（像素）
)

/**
 * 辅助：格式化浮点数为字符串（用小数点，不受地区设置影响）。
 */
fun Double.fmt(decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", this)
fun Int.toEven(): Int = this - this % 2

/** 向下对齐到 16 的倍数（硬件编码器要求 macroblock 对齐）。 */
fun Int.toAligned16(): Int = this / 16 * 16

/**
 * 辅助：过滤字符串中的换行、多余空格。
 */
fun String.clean(): String = this.replace("\n", " ").replace("  ", " ").trim()