package com.moon.videomerger.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 已生成视频的本地缓存管理。
 *
 * 文件存放在 filesDir/merged_videos/ 下：
 *   - merged_<timestamp>.mp4  视频文件
 *   - thumb_<timestamp>.jpg   缩略图
 *   - history.json            元数据索引
 */
object VideoHistoryStore {

    data class HistoryEntry(
        val filePath: String,
        val thumbnailPath: String,
        val mergeType: String,
        val timestamp: Long,
        val duration: Double,
        val width: Int,
        val height: Int
    )

    private fun getDir(context: Context): File =
        File(context.filesDir, "merged_videos").apply { mkdirs() }

    private fun getHistoryFile(context: Context): File =
        File(getDir(context), "history.json")

    /**
     * 将一个已生成的视频文件加入历史记录。
     * 调用方需确保 [videoFile] 已存在；本方法会把它复制到内部存储。
     */
    fun addToHistory(
        context: Context,
        videoFile: File,
        thumbnailFile: File,
        mergeType: String,
        duration: Double,
        width: Int,
        height: Int
    ): HistoryEntry {
        val timestamp = System.currentTimeMillis()
        val dir = getDir(context)

        val destVideo = File(dir, "merged_$timestamp.mp4")
        videoFile.copyTo(destVideo, overwrite = true)

        val destThumb = File(dir, "thumb_$timestamp.jpg")
        if (thumbnailFile.exists()) {
            thumbnailFile.copyTo(destThumb, overwrite = true)
        }

        val entry = HistoryEntry(
            filePath = destVideo.absolutePath,
            thumbnailPath = destThumb.absolutePath,
            mergeType = mergeType,
            timestamp = timestamp,
            duration = duration,
            width = width,
            height = height
        )

        val list = loadHistory(context).toMutableList()
        list.add(0, entry)
        saveHistory(context, list)

        return entry
    }

    fun loadHistory(context: Context): List<HistoryEntry> {
        val file = getHistoryFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(file.readText())
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                HistoryEntry(
                    filePath = obj.getString("filePath"),
                    thumbnailPath = obj.getString("thumbnailPath"),
                    mergeType = obj.getString("mergeType"),
                    timestamp = obj.getLong("timestamp"),
                    duration = obj.getDouble("duration"),
                    width = obj.getInt("width"),
                    height = obj.getInt("height")
                )
            }.filter { File(it.filePath).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteEntry(context: Context, entry: HistoryEntry) {
        File(entry.filePath).delete()
        File(entry.thumbnailPath).delete()
        val list = loadHistory(context).filter { it.timestamp != entry.timestamp }
        saveHistory(context, list)
    }

    private fun saveHistory(context: Context, list: List<HistoryEntry>) {
        val jsonArray = JSONArray()
        list.forEach { entry ->
            jsonArray.put(JSONObject().apply {
                put("filePath", entry.filePath)
                put("thumbnailPath", entry.thumbnailPath)
                put("mergeType", entry.mergeType)
                put("timestamp", entry.timestamp)
                put("duration", entry.duration)
                put("width", entry.width)
                put("height", entry.height)
            })
        }
        getHistoryFile(context).writeText(jsonArray.toString())
    }
}
