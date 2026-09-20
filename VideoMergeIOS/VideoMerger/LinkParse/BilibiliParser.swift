//
//  BilibiliParser.swift
//  VideoMerger
//
//  视频站解析器(LinkParser 实现之二)——分享链接取原始 mp4
//
//  流程(2026-09 实测有效,全程无需登录、无需 Cookie、无需 Wbi 签名):
//  1. 分享文案里取 b23.tv 短链或视频直链,短链跟随 302 拿 BV 号
//  2. api .../x/web-interface/view?bvid= → cid、标题、UP 主、时长
//  3. api .../x/player/playurl?bvid=&cid=&fnval=1&qn=64 → durl[].url
//     fnval=1 才返回音视频合一的渐进式 mp4;fnval=16 的 DASH 是音视频分离,不能直接存相册
//     未登录最高只给到 720P(qn=64),再高会被截回 64
//  4. CDN 校验:缺 Referer 用浏览器 UA 也 403,换成 App UA 同样 403
//

import Foundation

struct BilibiliLinkParser: LinkParser {

    var id: String { "bilibili" }
    var requestHeaders: DownloadHeaders {
        DownloadHeaders(Self.browserUA, referer: Self.siteReferer)
    }

    /// 接口无 UA 直接 412,CDN 只认浏览器 UA
    static let browserUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    static let siteReferer = "https://www.bilibili.com"

    // MARK: - LinkParser

    func extractShareURL(from text: String) -> String? {
        firstMatch("https://b23\\.tv/[A-Za-z0-9]+/?", in: text)
            ?? firstMatch("https?://[A-Za-z0-9.-]*bilibili\\.com/[A-Za-z0-9._/?=&%#:-]*", in: text)
    }

    func parse(shareLink: String) async throws -> ParsedVideo {
        let bvid = try await resolveBVID(from: shareLink)
        let view = try await requestJSON(
            "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, timeout: 20)
        guard let data = view["data"] as? [String: Any],
              let cid = data["cid"] as? Int, cid > 0 else {
            throw LinkParseError(L10n.t("bilibili.error_not_found"))
        }
        let pages = (data["pages"] as? [[String: Any]]) ?? []
        guard pages.count <= 1 else {
            throw LinkParseError(L10n.t("bilibili.error_multi_part"))
        }

        let play = try await requestJSON(
            "https://api.bilibili.com/x/player/playurl?bvid=\(bvid)&cid=\(cid)&fnval=1&qn=64",
            timeout: 20)
        guard let playData = play["data"] as? [String: Any],
              let durl = playData["durl"] as? [[String: Any]],
              let url = durl.first?["url"] as? String, !url.isEmpty else {
            throw LinkParseError(L10n.t("bilibili.error_no_play_url"))
        }
        // 长视频可能被切成多段,只下一段等于给用户一个残缺文件
        guard durl.count == 1 else {
            throw LinkParseError(L10n.t("bilibili.error_segmented"))
        }

        let author = (data["owner"] as? [String: Any])?["name"] as? String ?? ""
        return ParsedVideo(
            videoID: bvid,
            title: data["title"] as? String ?? "",
            author: author,
            durationMs: (data["duration"] as? Int ?? 0) * 1000,
            playURLs: [url],
            sizeBytes: (durl.first?["size"] as? NSNumber)?.int64Value
        )
    }

    // MARK: - 内部

    /// 直链里已带 BV 号则直接用,b23.tv 短链要先跟随 302
    private func resolveBVID(from shareLink: String) async throws -> String {
        if let bvid = firstMatch("BV[0-9A-Za-z]{10}", in: shareLink) { return bvid }
        let landing = try await resolveLandingURL(shareLink)
        guard let bvid = firstMatch("BV[0-9A-Za-z]{10}", in: landing.absoluteString) else {
            throw LinkParseError(L10n.t("bilibili.error_no_video_id"))
        }
        return bvid
    }
}
