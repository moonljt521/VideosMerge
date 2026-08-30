package com.moon.videomerger.douyin

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抖音分享链接解析 —— 提取无水印视频。
 *
 * 流程（2026-08 实测有效）：
 * 1. 从分享文案提取短链 https://v.douyin.com/xxx/
 * 2. 跟随 302 落到 www.iesdouyin.com/share/video/{视频ID}/，提取视频 ID
 * 3. 请求移动端 feed 接口（aweme.snssdk.com/aweme/v1/feed/）拿完整视频数据。
 *    注意：分享页 HTML 自 2024 起不再内嵌 _ROUTER_DATA 视频数据，老的解析方案已失效；
 *    www.douyin.com 的 web 接口需要 a_bogus 签名，App 内无法生成。
 * 4. play_addr.url_list 即无水印地址（play 而非 playwm），下载必须带抖音 App UA，
 *    否则重定向后拿不到数据（实测空 UA 返回 302 空响应）。
 */
object DouyinParser {

    /** 抖音 App UA —— feed 接口与视频下载都必须携带 */
    private const val APP_UA =
        "com.ss.android.ugc.aweme/110101 (Linux; U; Android 12; zh_CN; Pixel 6; " +
                "Build/SQ3A.220705.004; Cronet/TTNetVersion:62945871-3d9d5d8d-2024-01-23;nowak)"

    /**
     * 视频信息。
     * @param playUrls 候选无水印地址：稳定的 video_id 形式（无时效）在前，带时间戳的 CDN 直链在后
     */
    data class VideoInfo(
        val videoId: String,
        val title: String,
        val author: String,
        val durationMs: Long,
        val playUrls: List<String>
    )

    /** 从分享文案中提取抖音链接（短链优先；短码含下划线/连字符，勿漏） */
    fun extractShareUrl(text: String): String? {
        val short = Regex("https?://v\\.douyin\\.com/[A-Za-z0-9_-]+/?").find(text)?.value
        if (short != null) return short
        return Regex("https?://[A-Za-z0-9.-]*douyin\\.com/[A-Za-z0-9._/?=&%#:-]*")
            .find(text)?.value
    }

    /** 跟随重定向，返回最终落地页 URL */
    fun resolveRedirect(url: String): String {
        var current = url
        repeat(6) {
            val conn = open(current, followRedirects = false)
            try {
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    current = URL(URL(current), loc).toString()
                } else {
                    return current
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    /** 从落地页 URL 提取视频 ID（兼容 video/note/modal_id 形式） */
    fun extractVideoId(pageUrl: String): String? {
        return Regex("/video/(\\d+)").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("/note/(\\d+)").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("[?&](?:modal_id|item_ids|aweme_id)=(\\d+)").find(pageUrl)?.groupValues?.get(1)
    }

    /** 请求移动端 feed 接口，解析出视频信息与无水印候选地址 */
    fun fetchVideoInfo(videoId: String): VideoInfo {
        val api = "https://aweme.snssdk.com/aweme/v1/feed/?aweme_id=$videoId" +
                "&version_code=9.7.0&app_name=aweme&channel=App+Store" +
                "&device_platform=iphone&device_type=iPhone10,1&os_version=13.5&aid=1128"

        val body = httpGetString(api)
        val list = try {
            JSONObject(body).optJSONArray("aweme_list")
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("解析失败：接口返回异常，请稍后重试")

        var item: JSONObject? = null
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i)
            if (obj != null) { item = obj; break }
        }
        item ?: throw IllegalStateException("解析失败：视频不存在或已删除（私密视频无法解析）")

        val video = item.optJSONObject("video")
            ?: throw IllegalStateException("该链接是图集/图文作品，暂不支持")

        val urls = video.optJSONObject("play_addr")?.optJSONArray("url_list")
            ?: throw IllegalStateException("解析失败：未找到播放地址")

        val stable = mutableListOf<String>()
        val cdn = mutableListOf<String>()
        for (i in 0 until urls.length()) {
            val raw = urls.optString(i)
            if (raw.isBlank()) continue
            val fixed = if (raw.startsWith("//")) "https:$raw" else raw
            (if (fixed.contains("video_id=")) stable else cdn).add(fixed)
        }
        val candidates = stable + cdn
        if (candidates.isEmpty()) throw IllegalStateException("解析失败：播放地址为空")

        return VideoInfo(
            videoId = videoId,
            title = item.optString("desc"),
            author = item.optJSONObject("author")?.optString("nickname") ?: "",
            durationMs = video.optLong("duration"),
            playUrls = candidates
        )
    }

    /** 依次尝试候选地址下载到 dest，回调进度 0~1（null = 总长未知） */
    fun downloadVideo(playUrls: List<String>, dest: File, onProgress: (Float?) -> Unit) {
        dest.parentFile?.mkdirs()
        var lastError: Exception? = null
        for (url in playUrls) {
            try {
                downloadOne(url, dest, onProgress)
                return
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw IllegalStateException("视频下载失败：${lastError?.message ?: "所有地址均不可用"}")
    }

    private fun downloadOne(url: String, dest: File, onProgress: (Float?) -> Unit) {
        val conn = open(url, followRedirects = true)
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 }
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(total?.let { (done.toDouble() / it).toFloat() })
                    }
                }
            }
            if (dest.length() == 0L) throw IOException("下载内容为空")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetString(url: String): String {
        val conn = open(url, followRedirects = true)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, followRedirects: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = followRedirects
        conn.setRequestProperty("User-Agent", APP_UA)
        return conn
    }
}
