//
//  TwitterParser.swift
//  VideoMerger
//
//  X(推特)解析器 —— LinkParser 实现之三
//
//  流程(2026-09-20 实测有效,全程无需登录、无需 API Key):
//  1. 从 x.com/{user}/status/{id} 或 twitter.com 同形链接里取推文编号
//  2. GET cdn.syndication.twimg.com/tweet-result?id=&token= —— 这是推特自己给嵌入推文用的端点
//     video.variants 里同时有 m3u8 和逐档 mp4;清晰度直接写在 URL 路径(/avc1/1280x720/…),
//     变体对象不带码率字段,所以按路径里的像素数挑最高一档
//  3. video.mp4 CDN 不校验任何请求头(无 UA / 抖音 UA 都放行),也不需要 Referer
//
//  token 算法取自 JS 的 ((id/1e15)*Math.PI).toString(36);实测该端点目前并不校验 token
//  (乱填也返回正常数据),仍照算法生成,对方一旦开始校验不至于整体失效。
//

import Foundation

struct TwitterLinkParser: LinkParser {

    var id: String { "twitter" }
    var requestHeaders: DownloadHeaders { DownloadHeaders(Self.browserUA) }

    static let browserUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    // MARK: - LinkParser

    func extractShareURL(from text: String) -> String? {
        firstMatch("https?://(?:www\\.|m\\.)?(?:x|twitter)\\.com/[^/\\s?]+/status/\\d+", in: text)
    }

    func parse(shareLink: String) async throws -> ParsedVideo {
        guard let statusID = statusID(from: shareLink) else {
            throw LinkParseError(L10n.t("twitter.error_no_video_id"))
        }
        let endpoint = "https://cdn.syndication.twimg.com/tweet-result?id=\(statusID)" +
            "&token=\(syndationToken(statusID))&lang=en"
        let tweet = try await requestJSON(endpoint, timeout: 20)
        guard (tweet["__typename"] as? String) == "Tweet", tweet["tombstone"] == nil else {
            throw LinkParseError(L10n.t("twitter.error_not_found"))
        }
        guard let video = tweet["video"] as? [String: Any],
              let variants = video["variants"] as? [[String: Any]] else {
            throw LinkParseError(L10n.t("twitter.error_no_video"))
        }
        let mp4s = variants
            .filter { ($0["type"] as? String) == "video/mp4" }
            .compactMap { $0["src"] as? String }
        guard !mp4s.isEmpty else { throw LinkParseError(L10n.t("twitter.error_no_play_url")) }
        let ranked = mp4s.sorted { pixels($0) > pixels($1) }

        // 变体不给体积,单独 HEAD 一次,好让体积闸门对长视频生效
        let size = await probeSize(of: ranked[0])
        let user = tweet["user"] as? [String: Any]
        return ParsedVideo(
            videoID: statusID,
            title: tweet["text"] as? String ?? "",
            author: user?["name"] as? String ?? user?["screen_name"] as? String ?? "",
            durationMs: video["durationMs"] as? Int ?? 0,
            playURLs: ranked,
            sizeBytes: size
        )
    }

    // MARK: - 内部

    private func statusID(from url: String) -> String? {
        guard let r = url.range(of: "/status/") else { return nil }
        let digits = url[r.upperBound...].prefix { $0.isNumber }
        return digits.isEmpty ? nil : String(digits)
    }

    /// 取 URL 路径里的 WxH 换算像素数,用于挑最高清晰度
    private func pixels(_ url: String) -> Int {
        guard let r = url.range(of: #"\d{3,4}x\d{3,4}"#, options: .regularExpression) else { return 0 }
        let parts = url[r].split(separator: "x")
        guard parts.count == 2, let w = Int(parts[0]), let h = Int(parts[1]) else { return 0 }
        return w * h
    }

    /// ((id/1e15)*π) 的 36 进制小数部分,再去掉 0/1/i/l/g —— 与嵌入播放器的取法一致
    private func syndationToken(_ statusID: String) -> String {
        guard let value = Double(statusID) else { return "1" }
        let digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var frac = (value / 1e15) * Double.pi
        frac -= floor(frac)
        var out = ""
        for _ in 0..<12 {
            frac *= 36
            let d = Int(floor(frac))
            out.append(digits[digits.index(digits.startIndex, offsetBy: min(d, 35))])
            frac -= Double(d)
        }
        let trimmed = String(out.filter { !"01ilg".contains($0) })
        return trimmed.isEmpty ? "1" : trimmed
    }
}
