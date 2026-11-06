package com.moon.videomerger.inpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class InpaintException(message: String) : Exception(message)

data class InpaintVideoMeta(
    val displayWidth: Int,
    val displayHeight: Int,
    val duration: Double,
    val fps: Double,
    val frameCount: Int,
    val hasAudio: Boolean,
    val isVFR: Boolean,
)

data class InpaintPrepared(
    val meta: InpaintVideoMeta,
    val probeTime: Double,
    val probeBitmap: Bitmap,
    /** ROI 大小的修复结果与遮罩（按 roi 位置叠在整帧上，避免 4K 多张全帧位图） */
    val afterROI: Bitmap,
    val maskROI: Bitmap,
    val mask: InpaintMask,
) {
    val roi: RectPx get() = mask.roi
}

/**
 * 视频去水印（内容修复）引擎 —— 与 iOS InpaintEngine.swift 同构。
 *
 * 探测帧 → 静态遮罩 → ROI 分块逐帧修复 → overlay 回原片 → 拼接 + 原音轨/元数据。
 * - 只重编码画面，音频 copy；AIGC 等元数据标签原样回写
 * - 全链路使用「显示方向」坐标（ffmpeg 默认 autorotate）
 * - 分块控制临时盘：一段 raw → 编码无音轨分段 → concat → mux 原音轨
 */
class InpaintEngine(private val context: Context) {

    private val cache: File get() = context.cacheDir

    // ---------------------------------------------------------------- 元数据

    fun probeMeta(path: String, displayWidth: Int, displayHeight: Int): InpaintVideoMeta? {
        val cmd = "-v error -select_streams v:0 " +
            "-show_entries stream=width,height,r_frame_rate,avg_frame_rate,nb_frames " +
            "-show_entries format=duration -of json \"$path\""
        val out = FFprobeKit.execute(cmd)?.output ?: return null
        val json = try { JSONObject(out) } catch (e: Exception) { return null }
        val st = json.optJSONArray("streams")?.optJSONObject(0) ?: return null
        val format = json.optJSONObject("format")

        val rFps = parseRate(st.optString("r_frame_rate"))
        val aFps = parseRate(st.optString("avg_frame_rate"))
        val fps = if (rFps > 0) rFps else if (aFps > 0) aFps else 30.0
        val duration = format?.optString("duration")?.toDoubleOrNull() ?: 0.0
        var frames = st.optString("nb_frames").toIntOrNull() ?: 0
        if (frames <= 0) frames = max((duration * fps).toInt(), 1)
        val vfr = rFps > 0 && aFps > 0 && abs(rFps - aFps) / rFps > 0.01

        val audioOut = FFprobeKit.execute(
            "-v error -select_streams a -show_entries stream=codec_type " +
                "-of default=noprint_wrappers=1:nokey=1 \"$path\""
        )?.output ?: ""
        if (duration <= 0 || displayWidth <= 0 || displayHeight <= 0) return null
        return InpaintVideoMeta(displayWidth, displayHeight, duration, fps, frames,
            audioOut.contains("audio"), vfr)
    }

    private fun parseRate(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        val p = s.split("/")
        return if (p.size == 2) {
            val n = p[0].toDoubleOrNull() ?: 0.0
            val d = p[1].toDoubleOrNull() ?: 0.0
            if (d > 0) n / d else 0.0
        } else s.toDoubleOrNull() ?: 0.0
    }

    // ---------------------------------------------------------------- 探测帧

    fun extractProbeFrame(path: String, time: Double, out: File): Bitmap? {
        out.delete()
        val cmd = "-v error -ss ${fmt(max(time, 0.0))} -i \"$path\" -frames:v 1 -update 1 \"${out.absolutePath}\" -y"
        if (!runFFmpeg(cmd)) return null
        return BitmapFactory.decodeFile(out.absolutePath)
    }

    /** 抽样缩略图，选水印框外围梯度最低（背景最干净）的一帧 */
    fun autoProbeTime(path: String, duration: Double, bboxNorm: RectF): Double {
        if (duration <= 0.5) return 0.0
        val dir = File(cache, "inpaint_probe_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val n = if (duration < 2) 4 else 8
            val rate = n / duration
            val cmd = "-v error -i \"$path\" -vf \"fps=${fmt(rate, 6)},scale=240:-2\" " +
                "-frames:v $n -q:v 3 \"${dir.absolutePath}/t_%02d.jpg\" -y"
            if (!runFFmpeg(cmd)) return duration * 0.3
            val files = dir.listFiles()?.filter { it.name.startsWith("t_") }?.sortedBy { it.name } ?: return duration * 0.3
            if (files.isEmpty()) return duration * 0.3

            var bestIdx = 0
            var best = Double.MAX_VALUE
            files.forEachIndexed { i, f ->
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEachIndexed
                val score = ringGradientEnergy(bmp, bboxNorm)
                bmp.recycle()
                if (score < best) { best = score; bestIdx = i }
            }
            return min(max((bestIdx + 0.5) * duration / files.size, 0.0), max(duration - 0.05, 0.0))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ringGradientEnergy(bmp: Bitmap, bbox: RectF): Double {
        val w = bmp.width; val h = bmp.height
        if (w < 4 || h < 4) return Double.MAX_VALUE
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h) { i ->
            val p = px[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val ix0 = (bbox.left * w).toInt(); val ix1 = (bbox.right * w).toInt()
        val iy0 = (bbox.top * h).toInt(); val iy1 = (bbox.bottom * h).toInt()
        val x0 = (ix0 - 8).coerceIn(0, w - 2); val x1 = (ix1 + 8).coerceIn(0, w - 2)
        val y0 = (iy0 - 8).coerceIn(0, h - 2); val y1 = (iy1 + 8).coerceIn(0, h - 2)
        if (x1 <= x0 + 1 || y1 <= y0 + 1) return Double.MAX_VALUE
        var sum = 0.0; var count = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (x in ix0..ix1 && y in iy0..iy1) continue
            val i = y * w + x
            sum += abs(luma[i + 1] - luma[i]) + abs(luma[i + w] - luma[i])
            count++
        }
        return if (count > 0) sum / count else Double.MAX_VALUE
    }

    // ---------------------------------------------------------------- 遮罩与预览

    fun prepare(
        sourcePath: String,
        meta: InpaintVideoMeta,
        probeTime: Double,
        bboxPx: RectPx,
        dilate: Int,
        probeCache: File,
    ): Result<InpaintPrepared> {
        return try {
            val bmp = extractProbeFrame(sourcePath, probeTime, probeCache)
                ?: throw InpaintException("无法读取视频画面，请换一个视频试试")
            val rgb = bitmapToRgb(bmp)
            var params = MaskParams()
            params = params.copy(dilateKernel = dilate.coerceIn(5, 25))
            val res = InpaintMaskBuilder.build(rgb, bmp.width, bmp.height, bboxPx, params)
            val mask = res.mask ?: throw InpaintException(res.message ?: "没有找到可修复的水印像素")
            val roi = mask.roi
            val roiRGB = ByteArray(roi.w * roi.h * 3)
            for (y in 0 until roi.h) {
                System.arraycopy(rgb, ((roi.y + y) * bmp.width + roi.x) * 3, roiRGB, y * roi.w * 3, roi.w * 3)
            }
            val repaired = roiRGB.copyOf()
            TeleaInpainter(roi.w, roi.h, mask.mask).inpaint(repaired)

            val after = rgbToBitmap(repaired, roi.w, roi.h)
            val overlay = IntArray(roi.w * roi.h) { i ->
                if (mask.mask[i]) android.graphics.Color.argb(115, 255, 70, 70) else 0
            }
            val maskBitmap = Bitmap.createBitmap(overlay, roi.w, roi.h, Bitmap.Config.ARGB_8888)

            val fixedMeta = if (bmp.width == meta.displayWidth && bmp.height == meta.displayHeight) meta
                else meta.copy(displayWidth = bmp.width, displayHeight = bmp.height)
            Result.success(InpaintPrepared(fixedMeta, probeTime, bmp, after, maskBitmap, mask))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------- 修复

    fun repair(
        sourcePath: String,
        prepared: InpaintPrepared,
        outputPath: String,
        onProgress: (Double, String) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<File> {
        val meta = prepared.meta
        val mask = prepared.mask
        val roi = mask.roi
        val frameBytes = roi.w * roi.h * 3
        if (frameBytes <= 0) return Result.failure(InpaintException("水印遮罩为空"))

        val work = File(cache, "inpaint_work_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val totalFrames = max(meta.frameCount, 1)
            val fps = max(meta.fps, 1.0)
            val perByBytes = max(96 * 1024 * 1024 / frameBytes, 1)
            val per = max(min((fps * 20).toInt(), perByBytes), min(fps.toInt().coerceAtLeast(1), 24))

            val inpainter = TeleaInpainter(roi.w, roi.h, mask.mask)
            val segments = mutableListOf<File>()
            var processed = 0
            // ★ 按「实际解码帧数」推进，而不是按秒数切段：-ss/-t 的取整会在分块边界丢帧
            var nextFrame = 0

            onProgress(0.02, "分析画面")
            while (nextFrame < totalFrames) {
                if (isCancelled()) throw InpaintException("已取消")
                val want = min(totalFrames - nextFrame, per)
                val t0 = nextFrame / fps
                val tag = "%06d".format(nextFrame)

                val rawIn = File(work, "in_$tag.rgb")
                val rawOut = File(work, "out_$tag.rgb")
                rawIn.delete(); rawOut.delete()

                val decode = "-v error -ss ${fmt(t0, 6)} -i \"$sourcePath\" " +
                    "-vf \"crop=${roi.w}:${roi.h}:${roi.x}:${roi.y}\" -an " +
                    "-fps_mode cfr -r ${fmt(fps, 6)} -frames:v $want " +
                    "-f rawvideo -pix_fmt rgb24 \"${rawIn.absolutePath}\" -y"
                if (!runFFmpeg(decode) || !rawIn.exists()) throw InpaintException("视频解码失败")

                RandomAccessFile(rawIn, "r").use { rafIn ->
                    RandomAccessFile(rawOut, "rw").use { rafOut ->
                        rafOut.setLength(0)
                        val frame = ByteArray(frameBytes)
                        while (true) {
                            if (isCancelled()) throw InpaintException("已取消")
                            try { rafIn.readFully(frame) } catch (e: EOFException) { break }
                            inpainter.inpaint(frame)
                            rafOut.write(frame)
                            processed++
                            if (processed % 6 == 0) {
                                onProgress(0.03 + 0.9 * min(processed.toDouble() / totalFrames, 1.0), "逐帧修复")
                            }
                        }
                    }
                }

                val chunkFrames = (rawOut.length() / frameBytes).toInt()
                if (chunkFrames <= 0) {
                    // nb_frames 可能比实际略多：已经处理过内容则视为到达片尾
                    if (processed > 0) break
                    throw InpaintException("视频解码失败")
                }

                val seg = File(work, "seg_$tag.mp4")
                val encode = "-v error -ss ${fmt(t0, 6)} -i \"$sourcePath\" " +
                    "-f rawvideo -pix_fmt rgb24 -s ${roi.w}x${roi.h} -framerate ${fmt(fps, 6)} " +
                    "-i \"${rawOut.absolutePath}\" " +
                    "-filter_complex \"[0:v][1:v]overlay=${roi.x}:${roi.y}:shortest=1,format=yuv420p[v]\" " +
                    "-map \"[v]\" -an -frames:v $chunkFrames -c:v h264_mediacodec -b:v 8M " +
                    "-fps_mode passthrough \"${seg.absolutePath}\" -y"
                if (!runFFmpeg(encode) || !seg.exists() || seg.length() <= 0) throw InpaintException("视频编码失败")
                segments.add(seg)
                rawIn.delete(); rawOut.delete()
                nextFrame += chunkFrames
                onProgress(0.03 + 0.9 * min(processed.toDouble() / totalFrames, 1.0), "逐帧修复")
            }

            if (segments.isEmpty()) throw InpaintException("视频编码失败")
            if (isCancelled()) throw InpaintException("已取消")

            onProgress(0.95, "合成音轨")
            val list = File(work, "concat.txt")
            list.writeText(segments.joinToString("") { "file '${it.absolutePath}'\n" })
            File(outputPath).delete()
            val mux = "-v error -f concat -safe 0 -i \"${list.absolutePath}\" -i \"$sourcePath\" " +
                "-map 0:v:0 -map 1:a? -c copy -map_metadata 1 " +
                "-movflags +faststart+use_metadata_tags \"$outputPath\" -y"
            val out = File(outputPath)
            if (!runFFmpeg(mux) || !out.exists() || out.length() <= 0) throw InpaintException("输出文件为空，修复失败")
            onProgress(1.0, "完成")
            return Result.success(out)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun runFFmpeg(cmd: String): Boolean {
        val session = FFmpegKit.execute(cmd) ?: return false
        val rc = session.returnCode ?: return false
        if (ReturnCode.isSuccess(rc)) return true
        android.util.Log.e("InpaintEngine", "ffmpeg failed: $cmd\n${session.allLogsAsString?.takeLast(800)}")
        return false
    }

    private fun fmt(v: Double, digits: Int = 3): String =
        String.format(java.util.Locale.US, "%.${digits}f", v)

    companion object {
        fun bitmapToRgb(bmp: Bitmap): ByteArray {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val out = ByteArray(w * h * 3)
            for (i in px.indices) {
                val p = px[i]
                out[i * 3] = ((p shr 16) and 0xFF).toByte()
                out[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                out[i * 3 + 2] = (p and 0xFF).toByte()
            }
            return out
        }

        fun rgbToBitmap(rgb: ByteArray, w: Int, h: Int): Bitmap {
            val px = IntArray(w * h)
            for (i in 0 until w * h) {
                val r = rgb[i * 3].toInt() and 0xFF
                val g = rgb[i * 3 + 1].toInt() and 0xFF
                val b = rgb[i * 3 + 2].toInt() and 0xFF
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
