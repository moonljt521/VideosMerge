package com.moon.videomerger.linkparse

/**
 * 抖音解析器 —— LinkParser 的抖音实现。
 *
 * 流程（2026-09 实测有效）：
 * 1. 从分享文案提取短链 https://v.douyin.com/xxx/
 * 2. 跟随 302 落到 www.iesdouyin.com/share/video/{视频ID}/，提取视频 ID
 * 3. 请求移动端 feed 接口（aweme.snssdk.com/aweme/v1/feed/）拿完整视频数据。
 *    注意：分享页 HTML 自 2024 起不再内嵌 _ROUTER_DATA 视频数据，老的解析方案已失效；
 *    www.douyin.com 的 web 接口需要 a_bogus 签名，App 内无法生成。
 * 4. play_addr.url_list 即无水印地址（play 而非 playwm），下载必须带抖音 App UA，
 *    否则重定向后拿不到数据（实测空 UA 返回 302 空响应）。
 */
class DouyinLinkParser : LinkParser {

    override val id = "douyin"
    override val requestHeaders = DownloadHeaders(APP_UA)

    /** 从分享文案中提取抖音链接（短链优先；短码含下划线/连字符，勿漏） */
    override fun extractShareUrl(text: String): String? =
        Regex("https://v\\.douyin\\.com/[A-Za-z0-9_-]+/?").find(text)?.value
            ?: Regex("https?://[A-Za-z0-9.-]*douyin\\.com/[A-Za-z0-9._/?=&%#:-]*").find(text)?.value

    /** 短链 → 落地页 → 视频 ID → 视频信息，一次到位 */
    override fun parse(shareUrl: String): ParsedVideo {
        val pageUrl = resolveLandingUrl(shareUrl)
        val videoId = extractVideoId(pageUrl)
            ?: throw LinkParseException("无法从链接中提取视频 ID，请确认是作品分享链接")
        return fetchVideoInfo(videoId)
    }

    /** 从落地页 URL 提取视频 ID（兼容 video/note/modal_id 形式） */
    private fun extractVideoId(pageUrl: String): String? =
        Regex("/video/(\\d+)").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("/note/(\\d+)").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("[?&](?:modal_id|item_ids|aweme_id)=(\\d+)").find(pageUrl)?.groupValues?.get(1)

    /** 请求移动端 feed 接口，解析出视频信息与无水印候选地址 */
    private fun fetchVideoInfo(videoId: String): ParsedVideo {
        val api = "https://aweme.snssdk.com/aweme/v1/feed/?aweme_id=$videoId" +
                "&version_code=9.7.0&app_name=aweme&channel=App+Store" +
                "&device_platform=iphone&device_type=iPhone10,1&os_version=13.5&aid=1128"

        val list = requestJson(api).optJSONArray("aweme_list")
            ?: throw LinkParseException("解析失败：视频不存在或已删除（私密视频无法解析）")

        // 视频不存在/不可见时接口并不报错，而是回一整屏推荐流。
        // 必须比对 aweme_id，否则会把别人的视频当作用户要的结果展示并下载。
        val item = (0 until list.length())
            .asSequence()
            .mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optString("aweme_id") == videoId }
            ?: throw LinkParseException("解析失败：视频不存在或已删除（私密视频无法解析）")

        val video = item.optJSONObject("video")
            ?: throw LinkParseException("该链接是图集/图文作品，暂不支持")

        val urls = video.optJSONObject("play_addr")?.optJSONArray("url_list")
            ?: throw LinkParseException("解析失败：未找到播放地址")

        val stable = mutableListOf<String>()
        val cdn = mutableListOf<String>()
        for (i in 0 until urls.length()) {
            val raw = urls.optString(i)
            if (raw.isBlank()) continue
            val fixed = if (raw.startsWith("//")) "https:$raw" else raw
            (if (fixed.contains("video_id=")) stable else cdn).add(fixed)
        }
        val candidates = stable + cdn
        if (candidates.isEmpty()) throw LinkParseException("解析失败：播放地址为空")

        return ParsedVideo(
            videoId = videoId,
            title = item.optString("desc"),
            author = item.optJSONObject("author")?.optString("nickname") ?: "",
            durationMs = video.optLong("duration"),
            playUrls = candidates,
            // feed 接口不报文件体积；这里的作品本身只有十几到几十秒，不会触发上限
            sizeBytes = null
        )
    }

    companion object {
        /** 抖音 App UA —— feed 接口与视频 CDN 都必须携带 */
        private const val APP_UA =
            "com.ss.android.ugc.aweme/110101 (Linux; U; Android 12; zh_CN; Pixel 6; " +
                    "Build/SQ3A.220705.004; Cronet/TTNetVersion:62945871-3d9d5d8d-2024-01-23;nowak)"
    }
}
