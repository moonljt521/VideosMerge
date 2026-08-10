package com.moon.videomerger.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import java.io.FileOutputStream

/**
 * 视频元数据
 */
data class VideoMeta(
    val width: Int,
    val height: Int,
    val duration: Double,   // 秒
    val hasAudio: Boolean
)

object MediaUtils {

    /**
     * 将 content URI 对应的视频复制到本地临时文件（ffmpeg 需要文件路径）。
     */
    fun copyUriToTempFile(context: Context, uri: Uri, index: Int): File {
        val tempDir = File(context.cacheDir, "merge_inputs").apply { mkdirs() }
        val ext = guessExtension(uri, context)
        val tempFile = File(tempDir, "input_$index.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取视频: $uri")
        return tempFile
    }

    /**
     * 用 FFprobeKit 提取视频元数据（宽高、时长、是否有音频）。
     */
    fun getVideoMeta(path: String): VideoMeta {
        // 宽高
        val dimSession = FFprobeKit.execute(
            "-v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x \"$path\""
        )
        val (w, h) = dimSession.output.trim().split("x").let { parts ->
            parts[0].toInt() to parts[1].toInt()
        }

        // 时长
        val durSession = FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"$path\""
        )
        val duration = durSession.output.trim().toDoubleOrNull() ?: 0.0

        // 音频
        val audioSession = FFprobeKit.execute(
            "-v error -select_streams a -show_entries stream=codec_type -of default=noprint_wrappers=1:nokey=1 \"$path\""
        )
        val hasAudio = audioSession.output.trim().contains("audio")

        return VideoMeta(w, h, duration, hasAudio)
    }

    /**
     * 将合并后的视频文件保存到系统相册（Movies/VideoMerger/）。
     * 返回相册中文件的 URI。
     */
    fun saveToGallery(context: Context, file: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoMerger")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }

    /**
     * 从 content URI 加载视频缩略图（第一帧）。
     */
    fun loadThumbnailFromUri(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 从文件路径加载视频缩略图（第一帧）。
     */
    fun loadThumbnailFromFile(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 从 JPEG/PNG 图片文件加载 Bitmap。
     */
    fun loadImageFromFile(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将 Bitmap 保存为 JPEG 文件。
     */
    fun saveBitmapAsJpeg(bitmap: Bitmap, file: File, quality: Int = 85): Boolean {
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测视频末尾静止 logo 片段（抖音结尾），返回截断时间戳（秒）。
     * 检测不到返回 null。
     *
     * 原理：用 freezedetect 滤镜检测静止帧，取最后一段持续到结尾的 freeze_start。
     */
    fun detectLogoCut(path: String, noise: Double = 0.01, minDur: Double = 0.5): Double? {
        val session = FFmpegKit.execute(
            "-hide_banner -i \"$path\" -filter:v freezedetect=n=$noise:d=$minDur -an -f null -"
        )
        val logs = session.allLogsAsString ?: return null

        val startPattern = Regex("freeze_start:\\s*([\\d.]+)")
        val endPattern = Regex("freeze_end:\\s*([\\d.]+)")

        val starts = startPattern.findAll(logs).map { it.groupValues[1].toDouble() }.toList()
        val ends = endPattern.findAll(logs).map { it.groupValues[1].toDouble() }.toList()

        if (starts.isEmpty()) return null

        // 取"持续到结尾"的那一段：freeze_start 之后没有对应的 freeze_end
        val lastEnd = if (ends.isNotEmpty()) ends.last() else -1.0
        val tail = starts.filter { it > lastEnd }
        val cutStart = if (tail.isNotEmpty()) tail[0] else starts.last()

        val endMargin = 0.05
        val cut = cutStart - endMargin
        return if (cut > 0) cut else null
    }

    /**
     * 清理临时输入文件（不删除输出文件）。
     */
    fun cleanupInputFiles(context: Context) {
        File(context.cacheDir, "merge_inputs").deleteRecursively()
    }

    /**
     * 清理输出文件和缩略图。
     */
    fun cleanupOutputFiles(context: Context) {
        File(context.cacheDir, "merge_output.mp4").delete()
        File(context.cacheDir, "merge_thumb.jpg").delete()
    }

    private fun guessExtension(uri: Uri, context: Context): String {
        // 先看 URI 最后一段
        val name = uri.lastPathSegment ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp4", "mov", "mkv", "avi", "m4v", "webm", "flv", "ts")) return ext
        // 再看 content resolver 的 MIME type
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("mp4") -> "mp4"
            mime.contains("quicktime") -> "mov"
            mime.contains("matroska") -> "mkv"
            mime.contains("avi") -> "avi"
            mime.contains("webm") -> "webm"
            else -> "mp4"
        }
    }
}
