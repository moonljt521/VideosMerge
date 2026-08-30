//
//  DouyinParser.swift
//  VideoMerger
//
//  iOS 移植自 Android DouyinParser.kt —— 抖音分享链接解析,提取无水印视频
//
//  流程(2026-08 实测有效):
//  1. 从分享文案提取短链 https://v.douyin.com/xxx/
//  2. 跟随 302 落到 www.iesdouyin.com/share/video/{视频ID}/,提取视频 ID
//  3. 请求移动端 feed 接口(aweme.snssdk.com/aweme/v1/feed/)拿完整视频数据
//     (分享页 HTML 自 2024 起不再内嵌数据;web 接口需要 a_bogus 签名,App 内无法生成)
//  4. play_addr.url_list 即无水印地址(play 而非 playwm),下载必须带抖音 App UA
//

import Foundation

enum DouyinError: LocalizedError {
    case badLink
    case parseFailed(String)

    var errorDescription: String? {
        switch self {
        case .badLink: return "链接无效"
        case .parseFailed(let msg): return msg
        }
    }
}

enum DouyinParser {

    /// 抖音 App UA —— feed 接口与视频下载都必须携带
    static let appUA = "com.ss.android.ugc.aweme/110101 (Linux; U; Android 12; zh_CN; Pixel 6; " +
        "Build/SQ3A.220705.004; Cronet/TTNetVersion:62945871-3d9d5d8d-2024-01-23;nowak)"

    struct VideoInfo {
        let videoID: String
        let title: String
        let author: String
        let durationMs: Int
        /// 候选无水印地址:稳定的 video_id 形式(无时效)在前,带时间戳的 CDN 直链在后
        let playURLs: [String]
    }

    // MARK: - 解析链路

    /// 短链 → 落地页 → 视频 ID → 视频信息,一次到位
    static func resolveAndFetch(shareLink: String) async throws -> VideoInfo {
        let pageURL = try await resolveRedirect(shareLink)
        guard let videoID = extractVideoID(from: pageURL) else {
            throw DouyinError.parseFailed("无法从链接中提取视频 ID,请确认是作品分享链接")
        }
        return try await fetchVideoInfo(videoID: videoID)
    }

    /// 从分享文案中提取抖音链接(短链优先;短码含下划线/连字符,勿漏)
    static func extractShareURL(from text: String) -> String? {
        firstMatch("https://v\\.douyin\\.com/[A-Za-z0-9_-]+/?", in: text)
            ?? firstMatch("https?://[A-Za-z0-9.-]*douyin\\.com/[A-Za-z0-9._/?=&%#:-]*", in: text)
    }

    /// 跟随重定向,返回最终落地页 URL(URLSession 默认自动跟随,response.url 即终点)
    static func resolveRedirect(_ urlString: String) async throws -> URL {
        guard let url = URL(string: urlString) else { throw DouyinError.badLink }
        var req = URLRequest(url: url)
        req.setValue(appUA, forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 15
        let (_, resp) = try await URLSession.shared.data(for: req)
        return resp.url ?? url
    }

    /// 从落地页 URL 提取视频 ID(兼容 video/note/modal_id 形式)
    static func extractVideoID(from pageURL: URL) -> String? {
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
    static func fetchVideoInfo(videoID: String) async throws -> VideoInfo {
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
        var req = URLRequest(url: comps.url!)
        req.setValue(appUA, forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 20

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw DouyinError.parseFailed("解析失败:接口请求失败,请稍后重试")
        }

        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = root["aweme_list"] as? [[String: Any]],
              let item = list.first else {
            throw DouyinError.parseFailed("解析失败:视频不存在或已删除(私密视频无法解析)")
        }
        guard let video = item["video"] as? [String: Any] else {
            throw DouyinError.parseFailed("该链接是图集/图文作品,暂不支持")
        }
        guard let playAddr = video["play_addr"] as? [String: Any],
              let rawURLs = playAddr["url_list"] as? [String] else {
            throw DouyinError.parseFailed("解析失败:未找到播放地址")
        }

        let fixed = rawURLs
            .filter { !$0.isEmpty }
            .map { $0.hasPrefix("//") ? "https:" + $0 : $0 }
        let stable = fixed.filter { $0.contains("video_id=") }
        let cdn = fixed.filter { !$0.contains("video_id=") }
        let candidates = stable + cdn
        guard !candidates.isEmpty else {
            throw DouyinError.parseFailed("解析失败:播放地址为空")
        }

        let author = (item["author"] as? [String: Any])?["nickname"] as? String ?? ""
        return VideoInfo(
            videoID: videoID,
            title: item["desc"] as? String ?? "",
            author: author,
            durationMs: video["duration"] as? Int ?? 0,
            playURLs: candidates
        )
    }

    // MARK: - 下载

    /// 依次尝试候选地址下载到 dest,回调进度 0~1(nil = 总长未知)
    static func downloadVideo(from urls: [String], to dest: URL,
                              onProgress: @escaping (Double?) -> Void) async throws {
        var lastError: Error = DouyinError.parseFailed("视频下载失败:所有地址均不可用")
        for u in urls {
            guard let url = URL(string: u) else { continue }
            do {
                try await downloadOne(url, to: dest, onProgress: onProgress)
                return
            } catch {
                lastError = error
                try? FileManager.default.removeItem(at: dest)
            }
        }
        throw lastError
    }

    private static func downloadOne(_ url: URL, to dest: URL,
                                    onProgress: @escaping (Double?) -> Void) async throws {
        var req = URLRequest(url: url)
        req.setValue(appUA, forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 60

        let delegate = DownloadDelegate(onProgress: onProgress)
        let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let location: URL = try await withCheckedThrowingContinuation { cont in
            delegate.continuation = cont
            session.downloadTask(with: req).resume()
        }

        let dir = dest.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.moveItem(at: location, to: dest)
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value ?? 0
        if size == 0 {
            throw DouyinError.parseFailed("下载内容为空")
        }
    }

    // MARK: - 下载会话代理

    private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate {
        private let onProgress: (Double?) -> Void
        private var lastPct = -1
        private var reportedUnknown = false
        fileprivate var continuation: CheckedContinuation<URL, Error>?

        init(onProgress: @escaping (Double?) -> Void) {
            self.onProgress = onProgress
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didFinishDownloadingTo location: URL) {
            // URLSession 会在本方法返回后删除临时文件,必须先同步搬走
            let stable = FileManager.default.temporaryDirectory
                .appendingPathComponent("dy_dl_\(UUID().uuidString).mp4")
            do {
                if FileManager.default.fileExists(atPath: stable.path) {
                    try FileManager.default.removeItem(at: stable)
                }
                try FileManager.default.moveItem(at: location, to: stable)
                continuation?.resume(returning: stable)
            } catch {
                continuation?.resume(throwing: error)
            }
            continuation = nil
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            if let error {
                continuation?.resume(throwing: error)
                continuation = nil
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            // 跨域重定向时补回 App UA,否则 CDN 拿不到数据
            var r = request
            r.setValue(DouyinParser.appUA, forHTTPHeaderField: "User-Agent")
            completionHandler(r)
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                        totalBytesExpectedToWrite: Int64) {
            if totalBytesExpectedToWrite > 0 {
                let pct = Int(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) * 100)
                guard pct != lastPct else { return }
                lastPct = pct
                onProgress(Double(pct) / 100)
            } else if !reportedUnknown {
                reportedUnknown = true
                onProgress(nil)
            }
        }
    }

    // MARK: - 内部

    private static func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let m = re.firstMatch(in: text, range: range),
              let r = Range(m.range(at: 0), in: text) else { return nil }
        return String(text[r])
    }
}
