package com.moon.videomerger.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

/**
 * 贴纸渲染器 —— 把 emoji 渲染成透明背景 PNG，供画中画 overlay 叠加。
 * 用固定尺寸画布居中绘制，避免 emoji（彩色字形）测量不准导致裁切。
 */
object StickerRenderer {

    fun renderToPng(context: Context, emoji: String, size: Int = 256): File {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (size * 0.7f)
            textAlign = Paint.Align.CENTER
        }
        // 垂直居中（按字体度量计算基线）
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(emoji, size / 2f, baseline, paint)

        val file = File(context.cacheDir, "sticker_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return file
    }
}
