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
        // ★ 用时间戳 + index 生成唯一文件名，避免覆盖旧文件
        val timestamp = System.currentTimeMillis()
        val tempFile = File(tempDir, "input_${timestamp}_${index}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取视频: $uri")
        return tempFile
    }

    /**
     * 编辑器素材目录（filesDir/editor_media）。
     *
     * ★ 与 cacheDir 不同：filesDir 不会被系统在磁盘紧张时清理，
     *   编辑器导入的素材与缩略图放这里，草稿才能长期引用。
     */
    fun editorMediaDir(context: Context): File =
        File(context.filesDir, "editor_media").apply { mkdirs() }

    /**
     * 将 content URI 对应的媒体复制到编辑器素材目录（持久化，供草稿引用）。
     */
    fun copyUriToEditorMedia(context: Context, uri: Uri, index: Long): File {
        val ext = guessExtension(uri, context)
        val timestamp = System.currentTimeMillis()
        val dest = File(editorMediaDir(context), "input_${timestamp}_${index}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取媒体: $uri")
        return dest
    }

    /**
     * 清理 cacheDir 中的过期临时文件（安全：历史记录与导出结果均有独立拷贝）：
     * - editor_export_*.mp4：导出已完成（已存相册+历史），24h 后删除；
     * - thumb_* / editor_history_thumb_*：缩略图，7 天后删除；
     * - merge_inputs/：合并模块临时输入，7 天后删除。
     */
    fun cleanupStaleCache(context: Context) {
        val now = System.currentTimeMillis()
        val day = 24 * 3600_000L
        context.cacheDir.listFiles()?.forEach { f ->
            val age = now - f.lastModified()
            if (f.isFile) {
                val stale = when {
                    f.name.startsWith("editor_export_") -> age > day
                    f.name.startsWith("thumb_") || f.name.startsWith("editor_history_thumb_") -> age > 7 * day
                    else -> false
                }
                if (stale) f.delete()
            }
        }
        File(context.cacheDir, "merge_inputs").listFiles()?.forEach {
            if (now - it.lastModified() > 7 * day) it.delete()
        }
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

    /**
     * 判断文件是否为图片（按扩展名）。
     */
    fun isImageFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }

    /**
     * 读取图片宽高（不解码全图，避免 OOM）。
     */
    fun getImageDimensions(path: String): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (e: Exception) {
            null
        }
    }

    private fun guessExtension(uri: Uri, context: Context): String {
        // 先看 URI 最后一段
        val name = uri.lastPathSegment ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp4", "mov", "mkv", "avi", "m4v", "webm", "flv", "ts")) return ext
        // 再看 content resolver 的 MIME type
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("png") -> "png"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("bmp") -> "bmp"
            mime.contains("mp4") -> "mp4"
            mime.contains("quicktime") -> "mov"
            mime.contains("matroska") -> "mkv"
            mime.contains("avi") -> "avi"
            mime.contains("webm") -> "webm"
            else -> "mp4"
        }
    }
}
