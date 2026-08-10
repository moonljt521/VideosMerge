package com.moon.videomerger.merger

import android.content.Context
import android.net.Uri
import android.util.Log
import com.moon.videomerger.util.MediaUtils
import com.moon.videomerger.util.VideoHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 合并结果 —— 包含输出文件和元数据信息。
 */
data class MergeResult(
    val outputFile: File,
    val thumbnailFile: File,
    val mergeType: String,
    val duration: Double,
    val width: Int,
    val height: Int
)

/**
 * 合并引擎 —— 协调整个流程：
 * 1. 将选中的视频 URI 复制到临时文件
 * 2. 用 FFprobe 提取每个视频的元数据
 * 3. 根据合并类型构建 ffmpeg 命令
 * 4. 异步执行 ffmpeg 合并（不自动保存到相册）
 * 5. 生成缩略图
 * 6. 返回结果，由 UI 层决定是否保存
 */
class MergeEngine(private val context: Context) {

    companion object { private const val TAG = "MergeEngine" }

    /**
     * 执行合并流程，返回结果文件（不保存到相册）。
     */
    suspend fun merge(
        videoUris: List<Uri>,
        mergeType: MergeType,
        options: MergeOptions,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Result<MergeResult> = withContext(Dispatchers.IO) {

        if (videoUris.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("未选择视频"))
        }

        try {
            // 1. 复制到临时文件
            onProgress(0.0f)
            onLog("开始复制 ${videoUris.size} 个视频到临时文件...\n")
            val tempFiles = videoUris.mapIndexed { index, uri ->
                MediaUtils.copyUriToTempFile(context, uri, index)
            }
            val inputPaths = tempFiles.map { it.absolutePath }

            // 2. 探测元数据
            onLog("探测视频元数据...\n")
            val metas = inputPaths.map { MediaUtils.getVideoMeta(it) }
            val durations = metas.map { it.duration }

            metas.forEachIndexed { i, meta ->
                onLog("  [$i] ${tempFiles[i].name}  ${meta.width}x${meta.height}  ${meta.duration}s  audio=${meta.hasAudio}\n")
            }

            // 3. 构建命令
            val outputFile = File(context.cacheDir, "merge_output.mp4")
            outputFile.delete()

            val command = when (mergeType) {
                MergeType.GRID -> GridMerger().buildCommand(
                    inputPaths, durations, metas, outputFile.absolutePath, options
                )
                MergeType.COLLAGE -> CollageMerger().buildCommand(
                    inputPaths, durations, metas, outputFile.absolutePath, options
                )
                MergeType.PHOTO_WALL -> PhotoWallMerger().buildCommand(
                    inputPaths, durations, metas, outputFile.absolutePath, options
                )
            }

            onLog("ffmpeg 命令:\n$command\n\n")

            // 4. 执行 ffmpeg
            val maxDur = durations.maxOrNull() ?: 0.0
            val success = executeFFmpeg(command, maxDur, onProgress, onLog)

            if (!success) {
                return@withContext Result.failure(RuntimeException("FFmpeg 合并失败"))
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                return@withContext Result.failure(RuntimeException("输出文件不存在或为空"))
            }

            // 5. 生成缩略图
            onProgress(0.98f)
            val thumbFile = File(context.cacheDir, "merge_thumb.jpg")
            val thumb = MediaUtils.loadThumbnailFromFile(outputFile.absolutePath)
            if (thumb != null) {
                MediaUtils.saveBitmapAsJpeg(thumb, thumbFile)
            }

            // 6. 获取输出视频元数据
            val outMeta = MediaUtils.getVideoMeta(outputFile.absolutePath)

            onLog("合并完成！输出: ${outputFile.absolutePath}\n")
            onLog("  尺寸: ${outMeta.width}x${outMeta.height}  时长: ${outMeta.duration}s\n")

            // 清理临时输入文件（保留输出文件供预览/保存）
            MediaUtils.cleanupInputFiles(context)

            Result.success(
                MergeResult(
                    outputFile = outputFile,
                    thumbnailFile = thumbFile,
                    mergeType = mergeType.name,
                    duration = outMeta.duration,
                    width = outMeta.width,
                    height = outMeta.height
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "合并失败", e)
            MediaUtils.cleanupInputFiles(context)
            Result.failure(e)
        }
    }

    /**
     * 将合并结果保存到系统相册 + 本地历史。
     */
    suspend fun saveResult(result: MergeResult): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val displayName = "merged_${result.mergeType.lowercase()}_$timestamp.mp4"

            // 保存到相册
            val galleryUri = MediaUtils.saveToGallery(context, result.outputFile, displayName)
                ?: return@withContext Result.failure(RuntimeException("保存到相册失败"))

            // 保存到本地历史
            VideoHistoryStore.addToHistory(
                context = context,
                videoFile = result.outputFile,
                thumbnailFile = result.thumbnailFile,
                mergeType = result.mergeType,
                duration = result.duration,
                width = result.width,
                height = result.height
            )

            Result.success(galleryUri)
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            Result.failure(e)
        }
    }

    /**
     * 加载本地历史记录。
     */
    fun loadHistory(): List<VideoHistoryStore.HistoryEntry> {
        return VideoHistoryStore.loadHistory(context)
    }

    /**
     * 删除历史记录。
     */
    fun deleteHistoryEntry(entry: VideoHistoryStore.HistoryEntry) {
        VideoHistoryStore.deleteEntry(context, entry)
    }

    /**
     * 异步执行 ffmpeg 命令，挂起等待结果。
     */
    private suspend fun executeFFmpeg(
        command: String,
        totalDuration: Double,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Boolean = suspendCancellableCoroutine { cont ->

        val runner = FFmpegRunner(
            command = command,
            totalDuration = totalDuration,
            onProgress = onProgress,
            onLog = onLog,
            onComplete = { success, message ->
                Log.d(TAG, "FFmpeg 完成: success=$success, msg=$message")
                onLog("\n$message\n")
                if (cont.isActive) {
                    cont.resume(success)
                }
            }
        )

        runner.execute()

        cont.invokeOnCancellation {
            runner.cancel()
        }
    }
}
