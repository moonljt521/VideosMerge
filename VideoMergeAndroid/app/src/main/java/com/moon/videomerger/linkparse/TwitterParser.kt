package com.moon.videomerger.linkparse

import kotlin.math.floor

/**
 * X（推特）解析器 —— LinkParser 实现之三，对齐 iOS 的 TwitterParser.swift。
 *
 * 流程（2026-09 实测有效，无需登录、无 API Key）：
 * 1. 从 x.com/{user}/status/{id} 或 twitter.com 同形链接里取推文编号
 * 2. GET cdn.syndication.twimg.com/tweet-result?id=&token= —— 推特自己给嵌入推文用的端点。
 *    video.variants 里同时有 m3u8 和逐档 mp4；清晰度直接写在 URL 路径（/avc1/1280x720/…），
 *    变体对象不带码率字段，所以按路径里的像素数挑最高一档。
 * 3. video.mp4 的 CDN 不校验任何请求头（无 UA / 抖音 UA 都放行），也不需要 Referer
 *
 * token 算法取自 JS 的 ((id/1e15)*Math.PI).toString(36)；实测该端点目前并不校验 token
 * （乱填也返回正常数据，仅空 token 会退化），仍照算法生成，对方一旦开始校验不至于整体失效。
 *
 * 已知限制：x.com 在中国大陆不可达，真机验证需要出境网络。
 */
class TwitterLinkParser : LinkParser {

    override val id = "twitter"
    override val requestHeaders = DownloadHeaders(BROWSER_UA)

    override fun extractShareUrl(text: String): String? =
        Regex("https?://(?:www\\.|m\\.)?(?:x|twitter)\\.com/[^/\\s?]+/status/\\d+").find(text)?.value

    override fun parse(shareUrl: String): ParsedVideo {
        val statusId = statusId(shareUrl)
            ?: throw LinkParseException("无法从链接中提取内容编号，请确认是作品分享链接")
        val tweet = requestJson(
            "https://cdn.syndication.twimg.com/tweet-result" +
                    "?id=$statusId&token=${syndationToken(statusId)}&lang=en"
        )
        if (tweet.optString("__typename") != "Tweet" || tweet.has("tombstone")) {
            throw LinkParseException(MSG_NOT_FOUND)
        }
        val video = tweet.optJSONObject("video")
            ?: throw LinkParseException("该链接没有可下载的视频（纯文字或图片作品）")
        val variants = video.optJSONArray("variants")
            ?: throw LinkParseException(MSG_NO_PLAY_URL)

        val mp4s = (0 until variants.length()).mapNotNull { i ->
            val v = variants.optJSONObject(i) ?: return@mapNotNull null
            if (v.optString("type") == "video/mp4") v.optString("src").takeIf { it.isNotBlank() } else null
        }
        if (mp4s.isEmpty()) throw LinkParseException(MSG_NO_PLAY_URL)
        val ranked = mp4s.sortedByDescending { pixels(it) }

        // 变体不给体积，单独 HEAD 一次，好让体积闸门对长视频生效
        val user = tweet.optJSONObject("user")
        return ParsedVideo(
            videoId = statusId,
            title = tweet.optString("text"),
            author = user?.optString("name") ?: "",
            durationMs = video.optLong("durationMs"),
            playUrls = ranked,
            sizeBytes = probeSize(ranked.first())
        )
    }

    private fun statusId(url: String): String? =
        Regex("/status/(\\d+)").find(url)?.groupValues?.get(1)

    /** 取 URL 路径里的 WxH 换算像素数，用于挑最高清晰度 */
    private fun pixels(url: String): Int {
        val m = Regex("\\d{3,4}x\\d{3,4}").find(url) ?: return 0
        val parts = m.value.split("x")
        return parts.getOrNull(0)?.toIntOrNull()?.times(parts.getOrNull(1)?.toIntOrNull() ?: 0) ?: 0
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
        private const val MSG_NOT_FOUND = "解析失败：内容不存在、已删除，或作者设为私密"
        private const val MSG_NO_PLAY_URL = "解析失败：未找到可下载地址"
    }
}
