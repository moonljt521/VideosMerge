package com.moon.videomerger.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/**
 * 文字水印工具 —— 用 Android Canvas 生成透明背景的文字 PNG，供 FFmpeg overlay 使用。
 *
 * 对应 Python 脚本：text_watermark.py（用 Pillow 生成 PNG + ffmpeg overlay）。
 * FFmpegKit min 版本不含 drawtext 滤镜，所以采用相同的 PNG overlay 方案。
 */
object TextWatermarkRenderer {

    /**
     * 生成文字水印 PNG 文件。
     *
     * @param text 文字内容
     * @param fontSize 字号（px）
     * @param colorStr 颜色（white/red/yellow/cyan/#RRGGBB）
     * @param opacity 透明度 0~1
     * @param border 是否描边（黑色，增强可读性）
     * @return PNG 文件，和实际文字图像的宽高
     */
    fun renderToPng(
        context: Context,
        text: String,
        fontSize: Int,
        colorStr: String,
        opacity: Float,
        border: Boolean
    ): TextPngResult {
        val textColor = parseColor(colorStr)
        val alpha = (255 * opacity.coerceIn(0f, 1f)).toInt()

        // 测量文字尺寸
        val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize.toFloat()
            typeface = Typeface.DEFAULT
        }
        val textBounds = Rect()
        measurePaint.getTextBounds(text, 0, text.length, textBounds)
        val textW = textBounds.width()
        val textH = textBounds.height()

        // 描边余量
        val pad = if (border) (fontSize / 8).coerceAtLeast(2) else 4
        val imgW = textW + pad * 4
        val imgH = textH + pad * 4

        // 创建透明 Bitmap
        val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize.toFloat()
            typeface = Typeface.DEFAULT
            color = applyAlpha(textColor, alpha)
            textAlign = Paint.Align.LEFT
        }

        // 文字基线位置（让文字居中）
        val baseline = pad * 2 + textH - textBounds.bottom.toFloat()

        // 绘制描边（黑色，多次偏移绘制模拟描边）
        if (border) {
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSize.toFloat()
                typeface = Typeface.DEFAULT
                color = applyAlpha(Color.BLACK, alpha)
                textAlign = Paint.Align.LEFT
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = (fontSize / 6).toFloat().coerceAtLeast(2f)
            }
            canvas.drawText(text, pad * 2 - textBounds.left.toFloat(), baseline, strokePaint)
        }

        // 绘制文字
        canvas.drawText(text, pad * 2 - textBounds.left.toFloat(), baseline, textPaint)

        // 保存为 PNG
        val pngFile = File(context.cacheDir, "text_watermark_${System.currentTimeMillis()}.png")
        FileOutputStream(pngFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        return TextPngResult(
            file = pngFile,
            width = imgW,
            height = imgH
        )
    }

    /** 计算文字在视频上的 overlay 位置（9 宫格） */
    fun calcOverlayPosition(
        posKey: String,
        textW: Int,
        textH: Int,
        videoW: Int,
        videoH: Int,
        margin: Int = 20
    ): Pair<Int, Int> {
        return when (posKey) {
            "top-left" -> margin to margin
            "top-right" -> (videoW - textW - margin) to margin
            "bottom-left" -> margin to (videoH - textH - margin)
            "bottom-right" -> (videoW - textW - margin) to (videoH - textH - margin)
            "center" -> ((videoW - textW) / 2) to ((videoH - textH) / 2)
            else -> (videoW - textW - margin) to (videoH - textH - margin)
        }
    }

    private fun parseColor(str: String): Int {
        return when (str.lowercase()) {
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            "red" -> Color.RED
            "green" -> Color.GREEN
            "blue" -> Color.BLUE
            "yellow" -> Color.YELLOW
            "cyan" -> Color.CYAN
            "magenta" -> Color.MAGENTA
            "gray", "grey" -> Color.GRAY
            else -> {
                if (str.startsWith("#") && str.length == 7) {
                    try {
                        Color.parseColor(str)
                    } catch (_: Exception) { Color.WHITE }
                } else {
                    Color.WHITE
                }
            }
        }
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }
}

/** 文字 PNG 生成结果 */
data class TextPngResult(
    val file: File,
    val width: Int,
    val height: Int
)
