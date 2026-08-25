package com.moon.videomerger.editor.engine

import com.moon.videomerger.editor.data.Clip
import com.moon.videomerger.editor.data.EditorProject
import com.moon.videomerger.editor.data.Track
import com.moon.videomerger.editor.data.TrackType
import com.moon.videomerger.editor.data.WatermarkMode
import com.moon.videomerger.editor.data.WatermarkRegion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 去水印导出命令集成自测：
 * 用本机 ffmpeg 真实执行 FilterBuilder 生成的导出命令，
 * 验证水印滤镜图语法/标签/坐标全部合法（编码器替换为 libx264）。
 * 本机无 ffmpeg 时自动跳过。
 */
class ExportCommandWatermarkTest {

    private lateinit var dir: File
    private var ffmpegOk = false

    @Before
    fun setup() {
        ffmpegOk = try {
            val p = ProcessBuilder("ffmpeg", "-version").start()
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
        Assume.assumeTrue("本机无 ffmpeg，跳过集成测试", ffmpegOk)

        dir = createTempDir(prefix = "wm_test")
        // 生成 2s 720x1280 测试视频（含音频轨）
        val input = File(dir, "in.mp4")
        val code = exec(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30:duration=2",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            input.absolutePath
        )
        assertEquals("测试视频生成失败", 0, code)
    }

    @After
    fun teardown() {
        dir.deleteRecursively()
    }

    private fun clip(vararg regions: WatermarkRegion) = Clip(
        mediaPath = File(dir, "in.mp4").absolutePath,
        mediaName = "in.mp4",
        mediaDuration = 2.0,
        width = 720,
        height = 1280,
        hasAudio = true,
        trimEnd = 2.0,
        watermarkRegions = regions.toList(),
    )

    private fun project(clip: Clip) = EditorProject(
        tracks = mutableListOf(Track(type = TrackType.MAIN, clips = mutableListOf(clip)))
    )

    private fun runExport(project: EditorProject): Triple<Int, String, String> {
        val out = File(dir, "out.mp4").absolutePath
        var cmd = FilterBuilder().buildExportCommand(project, out, null)
        Assume.assumeTrue(cmd.isNotEmpty())
        // mediacodec 仅 Android 可用，本机用 libx264 验证滤镜图本身
        cmd = cmd.replace("h264_mediacodec", "libx264")
        println("=== EXPORT CMD ===\n$cmd\n=== END ===")
        val p = ProcessBuilder("ffmpeg", *tokenize(cmd).toTypedArray()).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return Triple(code, out, log)
    }

    @Test
    fun 单个模糊区域导出成功() {
        val (code, out, log) = runExport(
            project(clip(WatermarkRegion(0.6, 0.8, 0.3, 0.15)))
        )
        assertEquals("ffmpeg 执行失败:\n${log.takeLast(1500)}", 0, code)
        assertTrue("输出文件为空", File(out).length() > 0)
    }

    @Test
    fun 多区域混合模式导出成功() {
        val (code, out, log) = runExport(
            project(
                clip(
                    WatermarkRegion(0.6, 0.8, 0.3, 0.15, WatermarkMode.BLUR, strength = 14),
                    WatermarkRegion(0.05, 0.05, 0.25, 0.10, WatermarkMode.MOSAIC, strength = 12),
                )
            )
        )
        assertEquals("ffmpeg 执行失败:\n${log.takeLast(1500)}", 0, code)
        assertTrue(File(out).length() > 0)
    }

    @Test
    fun 极端小区域与高强度不崩溃() {
        val (code, out, log) = runExport(
            project(clip(WatermarkRegion(0.9, 0.9, 0.02, 0.02, WatermarkMode.BLUR, strength = 40)))
        )
        assertEquals("ffmpeg 执行失败:\n${log.takeLast(1500)}", 0, code)
        assertTrue(File(out).length() > 0)
    }

    @Test
    fun 输出时长与分辨率正确() {
        val (code, out, log) = runExport(
            project(clip(WatermarkRegion(0.6, 0.8, 0.3, 0.15)))
        )
        assertEquals("ffmpeg 执行失败:\n${log.takeLast(1500)}", 0, code)
        // 用 ffprobe 验证输出时长 ≈ 2s、分辨率 = 画布（防止区域 crop 误当输出）
        val probe = execCapture("ffprobe", "-v", "error", "-show_entries",
            "format=duration:stream=width,height", "-of", "csv=p=0", out)
        val duration = probe.lineSequence()
            .firstOrNull { it.trim().toDoubleOrNull() != null }?.trim()?.toDoubleOrNull() ?: 0.0
        assertTrue("输出时长异常: $duration\n$probe", duration in 1.8..2.2)
        assertTrue("输出分辨率异常（区域被误当整帧输出）:\n$probe", probe.contains("1088,1920"))
    }

    @Test
    fun 带时间段的水印区域导出成功() {
        val (code, out, log) = runExport(
            project(clip(WatermarkRegion(0.6, 0.8, 0.3, 0.15, startTime = 0.5, endTime = 1.5)))
        )
        assertEquals("ffmpeg 执行失败:\n${log.takeLast(1500)}", 0, code)
        assertTrue(File(out).length() > 0)
    }

    // ── 工具 ──

    private fun exec(vararg args: String): Int =
        ProcessBuilder(*args).redirectErrorStream(true).start().waitFor()

    private fun execCapture(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    /** 按双引号切分命令行参数 */
    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in s) {
            when {
                c == '"' -> inQuote = !inQuote
                c == ' ' && !inQuote -> {
                    if (sb.isNotEmpty()) out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
