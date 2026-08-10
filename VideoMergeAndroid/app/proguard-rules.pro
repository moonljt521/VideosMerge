# FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }

# Kotlin Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Compose
-dontwarn androidx.compose.**

# 保持数据类不被混淆
-keep class com.moon.videomerger.util.VideoHistoryStore$HistoryEntry { *; }
-keep class com.moon.videomerger.merger.MergeResult { *; }
