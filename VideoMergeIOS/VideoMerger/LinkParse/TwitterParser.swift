//
//  TwitterParser.swift
//  VideoMerger
//
//  X(推特)解析器 —— LinkParser 实现之三
//
//  双通道:先官方,拿不到再走第三方回退。
//
//  【主通道】cdn.syndication.twimg.com/tweet-result?id=&token=
//    这是推特自己给嵌入推文用的端点。video.variants 里同时有 m3u8 和逐档 mp4;
//    清晰度直接写在 URL 路径(/avc1/1280x720/…),变体对象不带码率字段,所以按路径里的像素数挑最高一档。
//    token 算法取自 JS 的 ((id/1e15)*Math.PI).toString(36)。
//    ⚠️ 2026-10-12 复测:该端点**会**校验 token —— 不带 token 直接返回空对象 {}。
//    旧注释「实测并不校验、乱填也返回正常数据」已不成立,别再据此简化。
//
//  【回退通道】api.fxtwitter.com/status/{id}
//    第三方公共接口(FixTweet 项目),无需 Key、无需登录。
//    为什么需要它:官方通道对「被平台限制查看」的内容(敏感/年龄限制)一律返回
//    __typename=TweetTombstone,官方 oEmbed 也是 403 not authorized;换 token、换 UA 都没用。
//    但视频文件本身在 CDN 上仍是 200 可下的,所以只有第三方拿得到地址。
//    该源用 HTTP 404 + body {"code":404} 表达「不存在」,故取数时要允许读错误响应体。
//    可用 RemoteConfig.twitterFallbackEnabled 一键关闭(合规预案,远端热修)。
//
//  【CDN】video.twimg.com 不校验任何请求头(无 UA / 抖音 UA 都放行),也不需要 Referer。
//
//  已知限制:一条推文挂多个视频时只取第一个(与 /video/1 的语义一致),暂不做多段合并。
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
        switch await resolve(statusID) {
        case .video(let parsed): return parsed
        case .noVideo:           throw LinkParseError(L10n.t("twitter.error_no_video"))
        case .notFound:          throw LinkParseError(L10n.t("twitter.error_not_found"))
        case .unavailable:       throw LinkParseError(L10n.t("twitter.error_unavailable"))
        }
    }

    // MARK: - 解析通道

    /// 三态分开,才能给出不误导的错误文案:
    /// 「作品不存在」和「作品存在但被限制查看」对用户是完全不同的下一步动作
    private enum Resolution {
        case video(ParsedVideo)
        /// 作品存在,但没有可下载的视频(纯文字/图片)
        case noVideo
        /// 作品不存在或已删除
        case notFound
        /// 内容被平台限制,或两路都取数失败,无法判定
        case unavailable
    }

    private func resolve(_ statusID: String) async -> Resolution {
        // 1. 官方嵌入端点
        if let tweet = try? await requestJSON(syndicationEndpoint(statusID), timeout: 20) {
            let isTweet = (tweet["__typename"] as? String) == "Tweet" && tweet["tombstone"] == nil
            if isTweet {
                // 官方明确说这条存在:没解析出视频就是真没有,不必再问回退源
                guard let parsed = await fromSyndication(tweet, statusID: statusID) else {
                    return .noVideo
                }
                return .video(parsed)
            }
            // TweetTombstone / 空对象:官方拒绝,落到回退通道
        }
        // 2. 第三方回退
        guard RemoteConfig.shared.twitterFallbackEnabled else { return .unavailable }
        return await resolveViaFallback(statusID)
    }

    private func syndicationEndpoint(_ statusID: String) -> String {
        "https://cdn.syndication.twimg.com/tweet-result?id=\(statusID)" +
            "&token=\(syndationToken(statusID))&lang=en"
    }

    private func fromSyndication(_ tweet: [String: Any], statusID: String) async -> ParsedVideo? {
        guard let video = tweet["video"] as? [String: Any],
              let variants = video["variants"] as? [[String: Any]] else { return nil }
        let candidates = variants.compactMap { v -> Candidate? in
            guard (v["type"] as? String) == "video/mp4",
                  let src = v["src"] as? String else { return nil }
            return Candidate(url: src)
        }
        let ordered = ranked(candidates)
        guard let best = ordered.first else { return nil }
        let user = tweet["user"] as? [String: Any]
        return ParsedVideo(
            videoID: statusID,
            title: tweet["text"] as? String ?? "",
            author: user?["name"] as? String ?? user?["screen_name"] as? String ?? "",
            durationMs: video["durationMs"] as? Int ?? 0,
            playURLs: ordered.map(\.url),
            // 变体不给体积,单独 HEAD 一次,好让体积闸门对长视频生效
            sizeBytes: await probeSize(of: best.url)
        )
    }

    private func resolveViaFallback(_ statusID: String) async -> Resolution {
        guard let json = try? await requestJSON("https://api.fxtwitter.com/status/\(statusID)",
                                                timeout: 20, allowHTTPError: true) else {
            return .unavailable     // 回退源不可达
        }
        if let code = json["code"] as? Int {
            if code == 404 { return .notFound }     // 明确不存在
            if code != 200 { return .unavailable }  // 429 / 5xx 等,别误判成不存在
        }
        guard let tweet = json["tweet"] as? [String: Any] else { return .unavailable }
        guard let media = tweet["media"] as? [String: Any],
              let video = (media["videos"] as? [[String: Any]])?.first else { return .noVideo }

        let candidates = (video["variants"] as? [[String: Any]] ?? []).compactMap { v -> Candidate? in
            guard (v["content_type"] as? String) == "video/mp4",
                  let src = v["url"] as? String else { return nil }
            return Candidate(url: src, bitrate: Int(number(v["bitrate"]) ?? 0))
        }
        let ordered = ranked(candidates)
        guard let best = ordered.first else { return .noVideo }

        let author = tweet["author"] as? [String: Any]
        let seconds = number(video["duration"]) ?? 0
        return .video(ParsedVideo(
            videoID: statusID,
            title: tweet["text"] as? String ?? "",
            author: author?["name"] as? String ?? author?["screen_name"] as? String ?? "",
            durationMs: Int(seconds * 1000),
            playURLs: ordered.map(\.url),
            sizeBytes: await probeSize(of: best.url)
        ))
    }

    // MARK: - 候选地址排序

    private struct Candidate {
        let url: String
        let bitrate: Int
        let pixels: Int

        init(url: String, bitrate: Int = 0) {
            self.url = url
            self.bitrate = bitrate
            self.pixels = Self.pixels(in: url)
        }

        /// 取 URL 路径里的 WxH 换算像素数,用于挑最高清晰度
        private static func pixels(in url: String) -> Int {
            guard let r = url.range(of: #"\d{3,4}x\d{3,4}"#, options: .regularExpression) else {
                return 0
            }
            let parts = url[r].split(separator: "x")
            guard parts.count == 2, let w = Int(parts[0]), let h = Int(parts[1]) else { return 0 }
            return w * h
        }
    }

    /// 分辨率优先;路径里读不出分辨率时(第三方源的 URL 不一定带 WxH)退回按码率排
    private func ranked(_ candidates: [Candidate]) -> [Candidate] {
        candidates.sorted {
            $0.pixels != $1.pixels ? $0.pixels > $1.pixels : $0.bitrate > $1.bitrate
        }
    }

    private func number(_ any: Any?) -> Double? {
        if let d = any as? Double { return d }
        if let i = any as? Int { return Double(i) }
        return (any as? NSNumber)?.doubleValue
    }

    // MARK: - 内部

    private func statusID(from url: String) -> String? {
        guard let r = url.range(of: "/status/") else { return nil }
        let digits = url[r.upperBound...].prefix { $0.isNumber }
        return digits.isEmpty ? nil : String(digits)
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
