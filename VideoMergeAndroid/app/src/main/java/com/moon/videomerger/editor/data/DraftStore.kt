package com.moon.videomerger.editor.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 编辑器草稿存储 —— 单槽位自动保存。
 *
 * 文件：filesDir/editor_draft/current.json（项目 JSON，素材本体在 filesDir/editor_media）。
 * 返回首页不丢内容；首页提供「继续编辑」入口与删除操作。
 */
object DraftStore {

    private const val DIR = "editor_draft"
    private const val FILE = "current.json"

    /** 首页「继续编辑」卡片所需的轻量信息 */
    data class DraftInfo(
        val name: String,
        val clipCount: Int,
        val subtitleCount: Int,
        val updatedAt: Long,
    )

    private fun draftFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** 项目 → JSON（纯函数，便于单测锁定草稿格式） */
    internal fun encodeProject(project: EditorProject): String =
        json.encodeToString(EditorProject.serializer(), project)

    /** JSON → 项目；格式损坏返回 null */
    internal fun decodeProject(text: String): EditorProject? = try {
        json.decodeFromString(EditorProject.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun save(context: Context, project: EditorProject) {
        try {
            draftFile(context).writeText(encodeProject(project))
        } catch (_: Exception) {
            // 草稿保存失败不影响编辑流程
        }
    }

    fun load(context: Context): EditorProject? = try {
        val file = draftFile(context)
        if (file.exists()) decodeProject(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            draftFile(context).delete()
        } catch (_: Exception) {
        }
    }

    /** 首页展示用轻量信息（不完整反序列化 UI 状态）；无有效草稿返回 null */
    fun peek(context: Context): DraftInfo? {
        val project = load(context) ?: return null
        val clipCount = project.mainTrack?.clips?.size ?: 0
        if (clipCount == 0 && project.subtitles.isEmpty()) return null
        return DraftInfo(
            name = project.name,
            clipCount = clipCount,
            subtitleCount = project.subtitles.size,
            updatedAt = project.updatedAt,
        )
    }
}
