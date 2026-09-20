//
//  DouyinParser.swift
//  VideoMerger
//
//  抖音解析器 —— LinkParser 的抖音实现,移植自 Android DouyinParser.kt
//
//  流程(2026-09 实测有效):
//  1. 从分享文案提取短链 https://v.douyin.com/xxx/
//  2. 跟随 302 落到 www.iesdouyin.com/share/video/{视频ID}/,提取视频 ID
//  3. 请求移动端 feed 接口(aweme.snssdk.com/aweme/v1/feed/)拿完整视频数据
//     (分享页 HTML 自 2024 起不再内嵌数据;web 接口需要 a_bogus 签名,App 内无法生成)
//  4. play_addr.url_list 即无水印地址(play 而非 playwm),下载必须带抖音 App UA
//

import Foundation

struct DouyinLinkParser: LinkParser {

    var id: String { "douyin" }
    var requestHeaders: DownloadHeaders { DownloadHeaders(Self.appUA) }

    /// 抖音 App UA —— feed 接口与视频 CDN 都必须携带
    static let appUA = "com.ss.android.ugc.aweme/110101 (Linux; U; Android 12; zh_CN; Pixel 6; " +
        "Build/SQ3A.220705.004; Cronet/TTNetVersion:62945871-3d9d5d8d-2024-01-23;nowak)"

    // MARK: - LinkParser

    /// 从分享文案中提取抖音链接(短链优先;短码含下划线/连字符,勿漏)
    func extractShareURL(from text: String) -> String? {
        firstMatch("https://v\\.douyin\\.com/[A-Za-z0-9_-]+/?", in: text)
            ?? firstMatch("https?://[A-Za-z0-9.-]*douyin\\.com/[A-Za-z0-9._/?=&%#:-]*", in: text)
    }

    /// 短链 → 落地页 → 视频 ID → 视频信息,一次到位
    func parse(shareLink: String) async throws -> ParsedVideo {
        let pageURL = try await resolveLandingURL(shareLink)
        guard let videoID = extractVideoID(from: pageURL) else {
            throw LinkParseError(L10n.t("douyin.error_no_video_id"))
        }
        return try await fetchVideoInfo(videoID: videoID)
    }

    // MARK: - 抖音侧取数

    /// 从落地页 URL 提取视频 ID(兼容 video/note/modal_id 形式)
    private func extractVideoID(from pageURL: URL) -> String? {
        let s = pageURL.absoluteString
        for key in ["/video/", "/note/"] {
            if let r = s.range(of: key) {
                let digits = s[r.upperBound...].prefix { $0.isNumber }
                if !digits.isEmpty { return String(digits) }
            }
        }
        for key in ["modal_id=", "item_ids=", "aweme_id="] {
            if let r = s.range(of: key) {
                let digits = s[r.upperBound...].prefix { $0.isNumber }
                if !digits.isEmpty { return String(digits) }
            }
        }
        return nil
    }

    /// 请求移动端 feed 接口,解析出视频信息与无水印候选地址
    private func fetchVideoInfo(videoID: String) async throws -> ParsedVideo {
        var comps = URLComponents(string: "https://aweme.snssdk.com/aweme/v1/feed/")!
        comps.queryItems = [
            URLQueryItem(name: "aweme_id", value: videoID),
            URLQueryItem(name: "version_code", value: "9.7.0"),
            URLQueryItem(name: "app_name", value: "aweme"),
            URLQueryItem(name: "channel", value: "App Store"),
            URLQueryItem(name: "device_platform", value: "iphone"),
            URLQueryItem(name: "device_type", value: "iPhone10,1"),
            URLQueryItem(name: "os_version", value: "13.5"),
            URLQueryItem(name: "aid", value: "1128"),
        ]
        let root = try await requestJSON(comps.url!.absoluteString, timeout: 20)

        guard let list = root["aweme_list"] as? [[String: Any]] else {
            throw LinkParseError(L10n.t("douyin.error_not_found"))
        }
        // 视频不存在/不可见时接口并不报错,而是回一整屏推荐流。
        // 必须比对 aweme_id,否则会把别人的视频当作用户要的结果展示并下载。
        guard let item = list.first(where: { ($0["aweme_id"] as? String) == videoID }) else {
            throw LinkParseError(L10n.t("douyin.error_not_found"))
        }
        guard let video = item["video"] as? [String: Any] else {
            throw LinkParseError(L10n.t("douyin.error_album_post"))
        }
        guard let playAddr = video["play_addr"] as? [String: Any],
              let rawURLs = playAddr["url_list"] as? [String] else {
            throw LinkParseError(L10n.t("douyin.error_no_play_url"))
        }

        let fixed = rawURLs
            .filter { !$0.isEmpty }
            .map { $0.hasPrefix("//") ? "https:" + $0 : $0 }
        // 稳定的 video_id 形式(无时效)在前,带时间戳的 CDN 直链在后
        let stable = fixed.filter { $0.contains("video_id=") }
        let cdn = fixed.filter { !$0.contains("video_id=") }
        let candidates = stable + cdn
        guard !candidates.isEmpty else {
            throw LinkParseError(L10n.t("douyin.error_empty_play_url"))
        }

        let author = (item["author"] as? [String: Any])?["nickname"] as? String ?? ""
        return ParsedVideo(
            videoID: videoID,
            title: item["desc"] as? String ?? "",
            author: author,
            durationMs: video["duration"] as? Int ?? 0,
            playURLs: candidates,
            // feed 接口不报文件体积;这里的作品本身只有十几到几十秒,不会触发上限
            sizeBytes: nil
        )
    }
}
