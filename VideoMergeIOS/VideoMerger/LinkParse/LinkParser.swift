//
//  LinkParser.swift
//  VideoMerger
//
//  「短视频去水印」的平台抽象层:
//  - LinkParser:一个平台要提供的能力(识别分享文案 → 解析无水印地址)
//  - LinkParserRegistry:已接入平台清单 + 按分享文案自动路由
//  - LinkNetwork:平台无关的取数与下载执行器,由各平台提供自己的请求头
//
//  接新平台 = 写一个 LinkParser 实现 + 在 registry 登记一行,
//  LinkParseViewModel / LinkParseView 不需要改动。
//

import Foundation

/// 解析结果:作品元数据 + 候选无水印下载地址
struct ParsedVideo {
    let videoID: String
    let title: String
    let author: String
    let durationMs: Int
    /// 已按可靠性排序,下载时依次尝试
    let playURLs: [String]
    /// 接口报出的文件字节数;平台不给则为 nil,此时跳过体积闸门
    let sizeBytes: Int64?
}

/// 解析/下载失败。message 为可直接展示给用户的本地化文案
struct LinkParseError: LocalizedError {
    let message: String
    var errorDescription: String? { message }

    init(_ message: String) { self.message = message }
}

/// 该平台请求接口与 CDN 时必须携带的请求头。
/// 各平台彼此不兼容:抖音只认自家 App UA,B站要浏览器 UA 且缺 Referer 直接 403。
struct DownloadHeaders {
    let userAgent: String
    let referer: String?

    init(_ userAgent: String, referer: String? = nil) {
        self.userAgent = userAgent
        self.referer = referer
    }
}

/// 下载侧的公共策略
enum LinkParsePolicy {

    /// 单次下载体积上限。视频站的长内容在未登录封顶 720P 下仍能到 GB 级,
    /// 不设闸会把临时目录塞满并让用户对着一个看不见的进度条等下去。
    static let maxDownloadBytes: Int64 = 150 * 1_000_000

    static func human(_ bytes: Int64) -> String {
        bytes >= 1_000_000_000
            ? String(format: "%.1fGB", Double(bytes) / 1_000_000_000)
            : String(format: "%.0fMB", Double(bytes) / 1_000_000)
    }
}

protocol LinkParser {
    /// 平台标识,用于埋点与落盘文件名,如 "douyin"
    var id: String { get }
    var requestHeaders: DownloadHeaders { get }

    /// 从分享文案中摘出本平台的链接;识别不出(属于别的平台)时返回 nil
    func extractShareURL(from text: String) -> String?

    /// 链接 → 元数据 + 无水印地址
    func parse(shareLink: String) async throws -> ParsedVideo

    /// 依次尝试候选地址下载到 dest,进度 0~1(nil = 总长未知)。协议已给默认实现
    func download(urls: [String], to dest: URL,
                  onProgress: @escaping (Double?) -> Void) async throws
}

extension LinkParser {

    func download(urls: [String], to dest: URL,
                  onProgress: @escaping (Double?) -> Void) async throws {
        try await LinkNetwork.download(urls: urls, to: dest,
                                       headers: requestHeaders, onProgress: onProgress)
    }

    /// 跟随重定向,返回最终落地页 URL(URLSession 默认自动跟随,response.url 即终点)
    func resolveLandingURL(_ urlString: String) async throws -> URL {
        try await LinkNetwork.resolveLandingURL(urlString, headers: requestHeaders)
    }

    /// 取 JSON 接口。返回顶层字典,由调用方按各自结构继续取值
    func requestJSON(_ urlString: String, timeout: TimeInterval) async throws -> [String: Any] {
        try await LinkNetwork.requestJSON(urlString, headers: requestHeaders, timeout: timeout)
    }

    /// 探一下文件多大;探不到返回 nil,体积闸门随之跳过
    func probeSize(of urlString: String) async -> Int64? {
        await LinkNetwork.contentLength(urlString, headers: requestHeaders)
    }

    /// 正则取首个匹配
    func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let m = re.firstMatch(in: text, range: range),
              let r = Range(m.range(at: 0), in: text) else { return nil }
        return String(text[r])
    }
}

/// 平台无关的网络执行器
enum LinkNetwork {

    static func resolveLandingURL(_ urlString: String, headers: DownloadHeaders) async throws -> URL {
        guard let url = URL(string: urlString) else {
            throw LinkParseError(L10n.t("douyin.error_bad_link"))
        }
        var req = URLRequest(url: url)
        apply(headers, to: &req)
        req.timeoutInterval = 15
        let (_, resp) = try await URLSession.shared.data(for: req)
        return resp.url ?? url
    }

    static func requestJSON(_ urlString: String, headers: DownloadHeaders,
                            timeout: TimeInterval) async throws -> [String: Any] {
        guard let url = URL(string: urlString) else {
            throw LinkParseError(L10n.t("douyin.error_request"))
        }
        var req = URLRequest(url: url)
        apply(headers, to: &req)
        req.timeoutInterval = timeout
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw LinkParseError(L10n.t("douyin.error_request"))
        }
        return json
    }

    /// 只为拿文件大小的一次 HEAD;拿不到就返回 nil,调用方据此跳过体积闸门
    static func contentLength(_ urlString: String, headers: DownloadHeaders) async -> Int64? {
        guard let url = URL(string: urlString) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "HEAD"
        apply(headers, to: &req)
        req.timeoutInterval = 10
        guard let http = try? await URLSession.shared.data(for: req).1 as? HTTPURLResponse,
              http.statusCode == 200 else { return nil }
        return http.value(forHTTPHeaderField: "Content-Length").flatMap { Int64($0) }
    }

    static func download(urls: [String], to dest: URL, headers: DownloadHeaders,
                         onProgress: @escaping (Double?) -> Void) async throws {
        var lastError: Error = LinkParseError(L10n.t("douyin.error_download_all_failed"))
        for u in urls {
            guard let url = URL(string: u) else { continue }
            do {
                try await downloadOne(url, to: dest, headers: headers, onProgress: onProgress)
                return
            } catch {
                lastError = error
                try? FileManager.default.removeItem(at: dest)
            }
        }
        throw lastError
    }

    private static func downloadOne(_ url: URL, to dest: URL, headers: DownloadHeaders,
                                    onProgress: @escaping (Double?) -> Void) async throws {
        var req = URLRequest(url: url)
        apply(headers, to: &req)
        req.timeoutInterval = 60

        let delegate = DownloadDelegate(headers: headers, onProgress: onProgress)
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
            throw LinkParseError(L10n.t("douyin.error_empty_download"))
        }
    }

    private static func apply(_ headers: DownloadHeaders, to req: inout URLRequest) {
        req.setValue(headers.userAgent, forHTTPHeaderField: "User-Agent")
        if let referer = headers.referer {
            req.setValue(referer, forHTTPHeaderField: "Referer")
        }
    }

    private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate {
        private let headers: DownloadHeaders
        private let onProgress: (Double?) -> Void
        private var lastPct = -1
        private var reportedUnknown = false
        fileprivate var continuation: CheckedContinuation<URL, Error>?

        init(headers: DownloadHeaders, onProgress: @escaping (Double?) -> Void) {
            self.headers = headers
            self.onProgress = onProgress
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didFinishDownloadingTo location: URL) {
            // URLSession 会在本方法返回后删除临时文件,必须先同步搬走
            let stable = FileManager.default.temporaryDirectory
                .appendingPathComponent("link_dl_\(UUID().uuidString).mp4")
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
            // 跨域重定向会丢掉原请求头,必须补回,否则 CDN 拿不到数据
            var r = request
            LinkNetwork.apply(headers, to: &r)
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
}

/// 已接入的解析平台。数组顺序即分享文案的匹配优先级
enum LinkParserRegistry {

    static let parsers: [LinkParser] = [
        DouyinLinkParser(),
        BilibiliLinkParser(),
        TwitterLinkParser(),
    ]

    /// 找出文案里首个可识别的平台及其链接;全部识别失败返回 nil
    static func detect(in text: String) -> (parser: LinkParser, shareURL: String)? {
        for parser in parsers {
            guard let shareURL = parser.extractShareURL(from: text) else { continue }
            return (parser, shareURL)
        }
        return nil
    }
}
