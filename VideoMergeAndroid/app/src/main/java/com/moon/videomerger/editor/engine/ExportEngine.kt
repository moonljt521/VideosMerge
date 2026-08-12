package com.moon.videomerger.editor.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.moon.videomerger.editor.data.EditorProject
import com.moon.videomerger.editor.data.Track
import com.moon.videomerger.editor.data.TrackType
import com.moon.videomerger.editor.data.Clip
import com.moon.videomerger.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 导出引擎 —— 将时间轴项目渲染为视频文件。
 *
 * 流程：
 * 1. 收集所有片段的源文件路径
 * 2. 用 FilterBuilder 构建 ffmpeg filter_complex
 * 3. 用 FFmpegRunner 异步执行
 * 4. 保存到相册 + 历史记录
 */
class ExportEngine(private val context: Context) {

    companion object { private const val TAG = "ExportEngine" }

    private val filterBuilder = FilterBuilder()

    /**
     * 从项目导出视频。
     */
    suspend fun export(
        project: EditorProject,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val mainTrack = project.mainTrack
            if (mainTrack == null || mainTrack.clips.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("没有视频片段"))
            }

            // 输出文件
            val outputFile = File(context.cacheDir, "editor_export_${System.currentTimeMillis()}.mp4")
            outputFile.delete()

            // 构建命令（传递 context 用于生成文字水印 PNG）
            val command = filterBuilder.buildExportCommand(project, outputFile.absolutePath, context)
            onLog("ffmpeg 命令:\n$command\n")

            // 执行
            val totalDuration = project.totalDuration
            val success = executeFFmpeg(command, totalDuration, onProgress, onLog)

            if (!success || !outputFile.exists() || outputFile.length() == 0L) {
                return@withContext Result.failure(RuntimeException("导出失败"))
            }

            onLog("导出完成: ${outputFile.absolutePath}\n")
            Result.success(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            Result.failure(e)
        }
    }

    /**
     * 保存导出文件到相册。
     */
    suspend fun saveToGallery(file: File, projectName: String): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val displayName = "${projectName}_${System.currentTimeMillis()}.mp4"
            val uri = MediaUtils.saveToGallery(context, file, displayName)
                ?: return@withContext Result.failure(RuntimeException("保存到相册失败"))
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            Result.failure(e)
        }
    }

    /**
     * 异步执行 ffmpeg。
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
                onLog("\n$message\n")
                if (cont.isActive) cont.resume(success)
            }
        )
        runner.execute()
        cont.invokeOnCancellation { runner.cancel() }
    }
}
