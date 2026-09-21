package com.moon.videomerger.linkparse

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「短视频去水印」的平台抽象层，对齐 iOS 的 LinkParser.swift。
 *
 * - [LinkParser]：一个平台要提供的能力（识别分享文案 → 解析无水印地址）
 * - [LinkParserRegistry]：已接入平台清单 + 按分享文案自动路由
 * - [LinkNetwork]：平台无关的取数与下载执行器，由各平台提供自己的请求头
 *
 * 接新平台 = 写一个 LinkParser 实现 + 在 registry 登记一行，
 * LinkParseViewModel / LinkParseScreen 不需要改动。
 */

/** 解析结果：作品元数据 + 候选无水印下载地址 */
data class ParsedVideo(
    val videoId: String,
    val title: String,
    val author: String,
    val durationMs: Long,
    /** 已按可靠性排序，下载时依次尝试 */
    val playUrls: List<String>,
    /** 接口报出的文件字节数；平台不给则为 null，此时跳过体积闸门 */
    val sizeBytes: Long?
)

/**
 * 该平台请求接口与 CDN 时必须携带的请求头。
 * 各平台彼此不兼容：抖音只认自家 App UA，B站要浏览器 UA 且缺 Referer 直接 403。
 */
data class DownloadHeaders(val userAgent: String, val referer: String? = null)

/** 解析/下载失败，message 为可直接展示给用户的文案 */
class LinkParseException(message: String) : Exception(message)

interface LinkParser {

    /** 平台标识，用于落盘文件名，如 "douyin" */
    val id: String
    val requestHeaders: DownloadHeaders

    /** 从分享文案中摘出本平台的链接；识别不出（属于别的平台）时返回 null */
    fun extractShareUrl(text: String): String?

    /** 链接 → 元数据 + 无水印地址 */
    fun parse(shareUrl: String): ParsedVideo

    /** 依次尝试候选地址下载到 dest，进度 0~1（null = 总长未知）。接口已给默认实现 */
    fun download(playUrls: List<String>, dest: File, onProgress: (Float?) -> Unit) {
        LinkNetwork.download(playUrls, dest, requestHeaders, onProgress)
    }

    /** 跟随重定向，返回最终落地页 URL */
    fun resolveLandingUrl(url: String): String = LinkNetwork.resolveLandingUrl(url, requestHeaders)

    /** 取 JSON 接口，返回顶层对象 */
    fun requestJson(url: String): JSONObject = LinkNetwork.requestJson(url, requestHeaders)

    /** 探一下文件多大；探不到返回 null，体积闸门随之跳过 */
    fun probeSize(url: String): Long? = LinkNetwork.contentLength(url, requestHeaders)
}

/** 平台无关的网络执行器 */
object LinkNetwork {

    fun resolveLandingUrl(url: String, headers: DownloadHeaders): String {
        var current = url
        repeat(6) {
            val conn = open(current, headers, follow = false)
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

    fun requestJson(url: String, headers: DownloadHeaders): JSONObject {
        val conn = open(url, headers)
        val body = try {
            if (conn.responseCode != 200) throw LinkParseException(MSG_REQUEST_FAILED)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw LinkParseException(MSG_REQUEST_FAILED)
        }
    }

    /** 只为拿文件大小的一次 HEAD；拿不到就返回 null */
    fun contentLength(url: String, headers: DownloadHeaders): Long? {
        val conn = try {
            open(url, headers, method = "HEAD")
        } catch (e: Exception) {
            return null
        }
        return try {
            if (conn.responseCode != 200) null else conn.contentLengthLong.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun download(playUrls: List<String>, dest: File, headers: DownloadHeaders,
                 onProgress: (Float?) -> Unit) {
        dest.parentFile?.mkdirs()
        var lastError: Exception? = null
        for (url in playUrls) {
            try {
                downloadOne(url, dest, headers, onProgress)
                return
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw LinkParseException("视频下载失败：${lastError?.message ?: "所有地址均不可用"}")
    }

    private fun downloadOne(url: String, dest: File, headers: DownloadHeaders,
                            onProgress: (Float?) -> Unit) {
        val conn = open(url, headers)
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

    private fun open(url: String, headers: DownloadHeaders, method: String = "GET",
                     follow: Boolean = true): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = method
        conn.instanceFollowRedirects = follow
        conn.setRequestProperty("User-Agent", headers.userAgent)
        headers.referer?.let { conn.setRequestProperty("Referer", it) }
        return conn
    }

    private const val MSG_REQUEST_FAILED = "解析失败：接口请求失败，请稍后重试"
}

/** 下载侧的公共策略 */
object LinkParsePolicy {

    /**
     * 单次下载体积上限。视频站的长内容在未登录封顶 720P 下仍能到 GB 级，
     * 不设闸会把缓存目录塞满并让用户对着一个走不完的进度条干等。
     */
    const val MAX_DOWNLOAD_BYTES = 150L * 1_000_000

    fun human(bytes: Long): String =
        if (bytes >= 1_000_000_000) String.format("%.1fGB", bytes / 1e9)
        else String.format("%.0fMB", bytes / 1e6)
}

/** 分享文案命中的平台与其链接 */
data class Routing(val parser: LinkParser, val shareUrl: String)

/** 已接入的解析平台。列表顺序即分享文案的匹配优先级 */
object LinkParserRegistry {

    val parsers: List<LinkParser> = listOf(
        DouyinLinkParser(),
        BilibiliLinkParser(),
        TwitterLinkParser(),
    )

    /** 找出文案里首个可识别的平台及其链接；全部识别失败返回 null */
    fun detect(text: String): Routing? {
        for (parser in parsers) {
            val url = parser.extractShareUrl(text) ?: continue
            return Routing(parser, url)
        }
        return null
    }
}
