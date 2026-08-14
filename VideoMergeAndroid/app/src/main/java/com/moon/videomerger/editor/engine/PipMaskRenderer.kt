package com.moon.videomerger.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.moon.videomerger.editor.data.PipShape
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * 画中画形状蒙版渲染器 —— 用 Android Canvas 生成 PNG 蒙版/描边，
 * 供 ffmpeg 通过 alphamerge / overlay 实现圆角/圆形裁剪与描边。
 *
 * 为什么用 PNG 而不是 ffmpeg 的 geq：min/full 版无 drawtext 时已确立
 * Canvas 生成 PNG 的替代方案，这里沿用同一思路，逻辑可读、可单测、可与预览一致。
 */
object PipMaskRenderer {

    /** 生成形状蒙版（白色实心形状，透明背景）。用于 alphamerge 裁剪画中画。 */
    fun renderMask(context: Context, w: Int, h: Int, shape: PipShape, cornerRadius: Float): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        drawShape(canvas, w.toFloat(), h.toFloat(), shape, cornerRadius, paint, 0f)
        return savePng(context, bmp, "pip_mask")
    }

    /** 生成描边框（白色描边，透明内部）。用于 overlay 叠加画中画描边。 */
    fun renderBorder(context: Context, w: Int, h: Int, shape: PipShape, cornerRadius: Float, strokeWidth: Float): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        // 描边居中于形状边缘，向内缩进半个描边宽度避免被画布裁掉
        drawShape(canvas, w.toFloat(), h.toFloat(), shape, cornerRadius, paint, strokeWidth / 2f)
        return savePng(context, bmp, "pip_border")
    }

    private fun drawShape(
        canvas: Canvas,
        w: Float,
        h: Float,
        shape: PipShape,
        cornerRadius: Float,
        paint: Paint,
        inset: Float
    ) {
        when (shape) {
            PipShape.RECT -> canvas.drawRect(RectF(inset, inset, w - inset, h - inset), paint)
            PipShape.ROUNDED -> canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), cornerRadius, cornerRadius, paint)
            PipShape.CIRCLE -> {
                val d = min(w, h) - inset * 2f
                canvas.drawCircle(w / 2f, h / 2f, d / 2f, paint)
            }
        }
    }

    private fun savePng(context: Context, bmp: Bitmap, prefix: String): File {
        val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return file
    }
}
