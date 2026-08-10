package com.moon.videomerger

import android.app.Application
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level

class VideoMergerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
        Log.d("VideoMergerApp", "FFmpegKit initialized: ${FFmpegKitConfig.getFFmpegVersion()}")
    }
}
