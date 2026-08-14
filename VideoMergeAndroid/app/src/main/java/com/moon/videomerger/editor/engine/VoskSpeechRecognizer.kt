package com.moon.videomerger.editor.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.moon.videomerger.editor.data.Subtitle
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * 语音转字幕 —— 用 Vosk 离线识别（org.vosk）把音频转成带时间戳的字幕。
 *
 * 流程：提取主轨第一个片段的音频（16kHz 单声道 PCM）→ Vosk 识别 → 按词时间戳分组生成字幕。
 * 模型放在外部存储目录 models/vosk-model-small-cn-0.22 下（可用 adb push 或运行时下载）。
 */
object VoskSpeechRecognizer {

    private const val MODEL_DIR = "models/vosk-model-small-cn-0.22"

    data class Word(val text: String, val start: Double, val end: Double)

    /** 模型是否就绪（存在 am/final.mdl 即视为完整） */
    fun isModelReady(context: Context): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        return dir.exists() && File(dir, "am/final.mdl").exists()
    }

    /** 模型目录绝对路径 */
    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR)

    /** 提取主轨音频为 16kHz 单声道 PCM（无头 raw，方便直接喂给 Vosk） */
    fun extractAudio(context: Context, mediaPath: String): File {
        val raw = File(context.cacheDir, "stt_audio_${System.currentTimeMillis()}.raw")
        val cmd = "-y -i \"$mediaPath\" -vn -ac 1 -ar 16000 -c:a pcm_s16le -f s16le \"${raw.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        require(session.returnCode != null && ReturnCode.isSuccess(session.returnCode)) {
            "提取音频失败：${session.allLogsAsString?.takeLast(500)}"
        }
        return raw
    }

    /** 识别 PCM 文件，返回词级时间戳 */
    fun recognize(context: Context, pcmFile: File): List<Word> {
        val modelDir = modelDir(context)
        require(isModelReady(context)) { "语音模型未就绪" }
        Model(modelDir.absolutePath).use { model ->
            val recognizer = Recognizer(model, 16000f)
            // ★ 关键：开启「词级时间戳」，否则 getResult/getFinalResult 只返回 text，没有 result 数组
            recognizer.setWords(true)
            pcmFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    recognizer.acceptWaveForm(buf, n)
                }
            }
            val json = recognizer.finalResult
            android.util.Log.d("VoskSTT", "finalResult: $json")
            val words = parseWords(json)
            if (words.isNotEmpty()) return words
            // 回退：无词级时间戳但有整段文本 → 生成一条覆盖全音频的字幕词
            val fullText = parseFullText(json)
            if (fullText.isNotBlank()) {
                val dur = pcmFile.length() / 2.0 / 16000.0
                return listOf(Word(fullText.replace(" ", "").replace("[unk]", ""), 0.0, dur))
            }
            return emptyList()
        }
    }

    private fun parseFullText(json: String): String {
        return try { JSONObject(json).optString("text", "") } catch (e: Exception) { "" }
    }

    private fun parseWords(json: String): List<Word> {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("result") ?: return emptyList()
            val out = mutableListOf<Word>()
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                val text = w.optString("word")
                val start = w.optDouble("start", -1.0)
                val end = w.optDouble("end", -1.0)
                // 过滤 [unk] 等未知词标记
                if (text.isNotBlank() && !text.contains("[") && start >= 0.0 && end >= start) {
                    out.add(Word(text.trim(), start, end))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 把词列表分组为字幕条目：按累计字符数（默认 14）或停顿（> 0.8s）换行。
     * 中文词直接拼接（不加空格），更符合中文习惯。
     */
    fun groupToSubtitles(words: List<Word>, maxChars: Int = 10): List<Subtitle> {
        if (words.isEmpty()) return emptyList()
        val subs = mutableListOf<Subtitle>()
        val buf = StringBuilder()
        var segStart = words.first().start
        var segEnd = words.first().end
        for (w in words) {
            val shouldBreak = buf.isNotEmpty() &&
                (buf.length + w.text.length > maxChars || w.start - segEnd > 0.8)
            if (shouldBreak) {
                subs.add(Subtitle(text = buf.toString(), startTime = segStart, endTime = segEnd))
                buf.clear()
                segStart = w.start
            }
            buf.append(w.text)
            segEnd = w.end
        }
        if (buf.isNotBlank()) {
            subs.add(Subtitle(text = buf.toString(), startTime = segStart, endTime = segEnd))
        }
        return subs
    }
}
