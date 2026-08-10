package com.moon.videomerger.merger

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * FFmpegKit 封装 —— 异步执行 ffmpeg 命令，提供进度和日志回调。
 */
class FFmpegRunner(
    private val command: String,
    private val totalDuration: Double,
    private val onProgress: (Float) -> Unit,
    private val onLog: (String) -> Unit,
    private val onComplete: (success: Boolean, message: String) -> Unit
) {
    companion object { private const val TAG = "FFmpegRunner" }

    fun execute() {
        FFmpegKit.executeAsync(command, { session ->
            handleComplete(session)
        }, { log ->
            val msg = log.message
            Log.d(TAG, msg)
            onLog(msg)
        }, { statistics ->
            handleStatistics(statistics)
        })
    }

    private fun handleComplete(session: FFmpegSession) {
        val rc = session.returnCode
        when {
            rc == null -> onComplete(false, "返回码为空")
            ReturnCode.isSuccess(rc) -> onComplete(true, "合并完成")
            ReturnCode.isCancel(rc) -> onComplete(false, "已取消")
            else -> {
                val logs = session.allLogsAsString ?: ""
                Log.e(TAG, "FFmpeg 失败: returnCode=${rc.value}\n$logs")
                onComplete(false, "合并失败 (code=${rc.value})")
            }
        }
    }

    private fun handleStatistics(statistics: Statistics) {
        if (totalDuration <= 0) return
        val currentSec = statistics.time / 1000.0
        val progress = (currentSec / totalDuration).toFloat().coerceIn(0f, 1f)
        onProgress(progress)
    }

    fun cancel() {
        FFmpegKit.cancel()
    }
}
