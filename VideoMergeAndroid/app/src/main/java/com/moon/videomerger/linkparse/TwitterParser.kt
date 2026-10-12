package com.moon.videomerger.linkparse

import kotlin.math.floor
import org.json.JSONObject

/**
 * X（推特）解析器 —— LinkParser 实现之三，对齐 iOS 的 TwitterParser.swift。
 *
 * 双通道：先官方，拿不到再走第三方回退。
 *
 * 【主通道】cdn.syndication.twimg.com/tweet-result?id=&token=
 *   推特自己给嵌入推文用的端点。video.variants 里同时有 m3u8 和逐档 mp4；
 *   清晰度直接写在 URL 路径（/avc1/1280x720/…），变体对象不带码率字段，所以按路径里的像素数挑最高一档。
 *   token 算法取自 JS 的 ((id/1e15)*Math.PI).toString(36)。
 *   ⚠️ 2026-10-12 复测：该端点**会**校验 token —— 不带 token 直接返回空对象 {}。
 *   旧注释「实测并不校验、乱填也返回正常数据」已不成立，别再据此简化。
 *
 * 【回退通道】api.fxtwitter.com/status/{id}
 *   第三方公共接口（FixTweet 项目），无需 Key、无需登录。
 *   为什么需要它：官方通道对「被平台限制查看」的内容（敏感/年龄限制）一律返回
 *   __typename=TweetTombstone，官方 oEmbed 也是 403 not authorized；换 token、换 UA 都没用。
 *   但视频文件本身在 CDN 上仍是 200 可下的，所以只有第三方拿得到地址。
 *   该源用 HTTP 404 + body {"code":404} 表达「不存在」，故取数时要允许读错误响应体。
 *   ⚠️ Android 侧无远程开关基建，回退恒开；iOS 侧可用 RemoteConfig.twitterFallbackEnabled 关闭。
 *
 * 【CDN】video.twimg.com 不校验任何请求头（无 UA / 抖音 UA 都放行），也不需要 Referer。
 *
 * 已知限制：一条推文挂多个视频时只取第一个（与 /video/1 的语义一致），暂不做多段合并；
 *          x.com 在中国大陆不可达，真机验证需要出境网络。
 */
class TwitterLinkParser : LinkParser {

    override val id = "twitter"
    override val requestHeaders = DownloadHeaders(BROWSER_UA)

    override fun extractShareUrl(text: String): String? =
        Regex("https?://(?:www\\.|m\\.)?(?:x|twitter)\\.com/[^/\\s?]+/status/\\d+").find(text)?.value

    override fun parse(shareUrl: String): ParsedVideo {
        val statusId = statusId(shareUrl) ?: throw LinkParseException(MSG_NO_ID)

        // 1. 官方嵌入端点
        val tweet = runCatching { requestJson(syndicationEndpoint(statusId)) }.getOrNull()
        if (tweet != null && tweet.optString("__typename") == "Tweet" && !tweet.has("tombstone")) {
            // 官方明确说这条存在：没解析出视频就是真没有，不必再问回退源
            return fromSyndication(tweet, statusId) ?: throw LinkParseException(MSG_NO_VIDEO)
        }
        // TweetTombstone / 空对象 / 请求失败 —— 官方这条路走不通，落到回退通道
        return resolveViaFallback(statusId)
    }

    // MARK: - 官方通道

    private fun syndicationEndpoint(statusId: String): String =
        "https://cdn.syndication.twimg.com/tweet-result" +
                "?id=$statusId&token=${syndationToken(statusId)}&lang=en"

    private fun fromSyndication(tweet: JSONObject, statusId: String): ParsedVideo? {
        val video = tweet.optJSONObject("video") ?: return null
        val variants = video.optJSONArray("variants") ?: return null
        val candidates = (0 until variants.length()).mapNotNull { i ->
            val v = variants.optJSONObject(i) ?: return@mapNotNull null
            if (v.optString("type") != "video/mp4") return@mapNotNull null
            v.optString("src").takeIf { it.isNotBlank() }?.let { Candidate(it) }
        }
        val ordered = ranked(candidates)
        val best = ordered.firstOrNull() ?: return null
        val user = tweet.optJSONObject("user")
        return ParsedVideo(
            videoId = statusId,
            title = tweet.optString("text"),
            author = user?.optString("name") ?: "",
            durationMs = video.optLong("durationMs", 0L),
            playUrls = ordered.map { it.url },
            // 变体不给体积，单独 HEAD 一次，好让体积闸门对长视频生效
            sizeBytes = probeSize(best.url)
        )
    }

    // MARK: - 第三方回退通道

    /** 三种失败要分开报，才能给出不误导的文案：作品不存在 ≠ 作品存在但被限制查看 */
    private fun resolveViaFallback(statusId: String): ParsedVideo {
        val json = runCatching {
            requestJson("https://api.fxtwitter.com/status/$statusId", allowHttpError = true)
        }.getOrNull() ?: throw LinkParseException(MSG_UNAVAILABLE)   // 回退源不可达

        val code = json.optInt("code", 200)
        if (code == 404) throw LinkParseException(MSG_NOT_FOUND)     // 明确不存在
        if (code != 200) throw LinkParseException(MSG_UNAVAILABLE)   // 429 / 5xx，别误判成不存在

        val tweet = json.optJSONObject("tweet") ?: throw LinkParseException(MSG_UNAVAILABLE)
        val video = tweet.optJSONObject("media")?.optJSONArray("videos")?.optJSONObject(0)
            ?: throw LinkParseException(MSG_NO_VIDEO)
        val variants = video.optJSONArray("variants") ?: throw LinkParseException(MSG_NO_VIDEO)
        val candidates = (0 until variants.length()).mapNotNull { i ->
            val v = variants.optJSONObject(i) ?: return@mapNotNull null
            if (v.optString("content_type") != "video/mp4") return@mapNotNull null
            v.optString("url").takeIf { it.isNotBlank() }
                ?.let { Candidate(it, v.optInt("bitrate", 0)) }
        }
        val ordered = ranked(candidates)
        val best = ordered.firstOrNull() ?: throw LinkParseException(MSG_NO_VIDEO)
        val author = tweet.optJSONObject("author")
        return ParsedVideo(
            videoId = statusId,
            title = tweet.optString("text"),
            author = author?.optString("name") ?: "",
            // 回退源给的是秒（浮点），统一换算成毫秒
            durationMs = (video.optDouble("duration", 0.0) * 1000).toLong(),
            playUrls = ordered.map { it.url },
            sizeBytes = probeSize(best.url)
        )
    }

    // MARK: - 内部

    private fun statusId(url: String): String? =
        Regex("/status/(\\d+)").find(url)?.groupValues?.get(1)

    /** 分辨率优先；路径里读不出分辨率时（第三方源的 URL 不一定带 WxH）退回按码率排 */
    private fun ranked(list: List<Candidate>): List<Candidate> =
        list.sortedWith(compareByDescending<Candidate> { it.pixels }.thenByDescending { it.bitrate })

    private data class Candidate(val url: String, val bitrate: Int = 0) {
        val pixels: Int = pixelsIn(url)
    }

    /** ((id/1e15)*π) 的 36 进制小数部分，再去掉 0/1/i/l/g —— 与嵌入播放器的取法一致 */
    private fun syndationToken(statusId: String): String {
        val value = statusId.toDoubleOrNull() ?: return "1"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var frac = (value / 1e15) * Math.PI
        frac -= floor(frac)
        val out = StringBuilder()
        repeat(12) {
            frac *= 36.0
            val d = floor(frac).toInt()
            out.append(digits[d.coerceAtMost(35)])
            frac -= d
        }
        val trimmed = out.toString().filter { it !in "01ilg" }
        return if (trimmed.isEmpty()) "1" else trimmed
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val MSG_NO_ID = "无法从链接中提取内容编号，请确认是作品分享链接"
        private const val MSG_NOT_FOUND = "解析失败：内容不存在、已删除，或作者设为私密"
        private const val MSG_NO_VIDEO = "该链接没有可下载的视频（纯文字或图片作品）"
        private const val MSG_UNAVAILABLE = "解析失败：该内容被平台限制查看，无法解析"
    }
}

/** 取 URL 路径里的 WxH 换算像素数，用于挑最高清晰度 */
private fun pixelsIn(url: String): Int {
    val m = Regex("\\d{3,4}x\\d{3,4}").find(url) ?: return 0
    val parts = m.value.split("x")
    val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return w * h
}
