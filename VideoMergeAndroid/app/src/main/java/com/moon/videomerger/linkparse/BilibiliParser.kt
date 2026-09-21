package com.moon.videomerger.linkparse

/**
 * 视频站（B站）解析器 —— LinkParser 实现之二，对齐 iOS 的 BilibiliParser.swift。
 *
 * 流程（2026-09 实测有效，全程无需登录、无需 Cookie、无需 Wbi 签名）：
 * 1. 分享文案里取 b23.tv 短链或视频直链，短链跟随 302 拿 BV 号
 * 2. api .../x/web-interface/view?bvid= → cid、标题、UP 主、时长
 * 3. api .../x/player/playurl?bvid=&cid=&fnval=1&qn=64 → durl[].url
 *    fnval=1 才返回音视频合一的渐进式 mp4；fnval=16 的 DASH 是音视频分离，不能直接存相册。
 *    未登录最高只给到 720P（qn=64），再高会被截回 64。
 * 4. CDN 校验：缺 Referer 用浏览器 UA 也 403，换成抖音 App UA 同样 403
 */
class BilibiliLinkParser : LinkParser {

    override val id = "bilibili"
    override val requestHeaders = DownloadHeaders(BROWSER_UA, SITE_REFERER)

    override fun extractShareUrl(text: String): String? =
        Regex("https://b23\\.tv/[A-Za-z0-9]+/?").find(text)?.value
            ?: Regex("https?://[A-Za-z0-9.-]*bilibili\\.com/[A-Za-z0-9._/?=&%#:-]*").find(text)?.value

    override fun parse(shareUrl: String): ParsedVideo {
        val bvid = resolveBvid(shareUrl)
        val data = requestJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            .optJSONObject("data")
            ?: throw LinkParseException(MSG_NOT_FOUND)
        val cid = data.optLong("cid")
        if (cid <= 0) throw LinkParseException(MSG_NOT_FOUND)
        val pages = data.optJSONArray("pages")
        if (pages != null && pages.length() > 1) {
            throw LinkParseException("该作品为多集投稿，暂只支持单集视频")
        }

        val durl = requestJson(
            "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=1&qn=64"
        ).optJSONObject("data")?.optJSONArray("durl")
        val first = durl?.optJSONObject(0)
        val url = first?.optString("url")
        if (url.isNullOrBlank()) {
            throw LinkParseException("解析失败：未找到可下载地址（付费或受限内容不支持）")
        }
        // 长视频可能被切成多段，只下一段等于给用户一个残缺文件
        if (durl.length() > 1) throw LinkParseException("该视频被拆成多段存放，暂不支持")

        return ParsedVideo(
            videoId = bvid,
            title = data.optString("title"),
            author = data.optJSONObject("owner")?.optString("name") ?: "",
            durationMs = data.optLong("duration") * 1000,
            playUrls = listOf(url),
            sizeBytes = first.optLong("size").takeIf { it > 0 }
        )
    }

    /** 直链里已带 BV 号则直接用，b23.tv 短链要先跟随 302 */
    private fun resolveBvid(shareUrl: String): String =
        Regex("BV[0-9A-Za-z]{10}").find(shareUrl)?.value
            ?: Regex("BV[0-9A-Za-z]{10}").find(resolveLandingUrl(shareUrl))?.value
            ?: throw LinkParseException("无法从链接中提取视频编号，请确认是作品分享链接")

    companion object {
        /** 接口无 UA 直接 412，CDN 只认浏览器 UA */
        private const val BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val SITE_REFERER = "https://www.bilibili.com"
        private const val MSG_NOT_FOUND = "解析失败：视频不存在、已删除，或需登录/会员才能观看"
    }
}
