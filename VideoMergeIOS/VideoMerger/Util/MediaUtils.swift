//
//  MediaUtils.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 MediaUtils.kt
//

import Foundation
import UIKit
import AVFoundation
import Photos
import SwiftUI
import PhotosUI
import ffmpegkit

/// 媒体工具
enum MediaUtils {

    // MARK: - 临时文件管理

    /// 临时输入目录
    static var inputDir: URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("merge_inputs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 合并输出文件 URL
    static var outputFileURL: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("merge_output.mp4")
    }

    /// 合并缩略图 URL
    static var thumbnailFileURL: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("merge_thumb.jpg")
    }

    /// 将选中视频 URL 对应的文件复制到本地临时目录（ffmpeg 需要文件路径）
    /// - Parameters:
    ///   - url: PHPicker 选出的 URL（可能是 dataACCESS 范围的 security-scoped URL）
    ///   - index: 索引，用于命名
    static func copyToTempFile(url: URL, index: Int) throws -> URL {
        let ext = guessExtension(url: url)
        let dest = inputDir.appendingPathComponent("input_\(index).\(ext)")

        // 需要访问 security-scoped resource
        let needsScope = url.startAccessingSecurityScopedResource()
        defer {
            if needsScope { url.stopAccessingSecurityScopedResource() }
        }

        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.copyItem(at: url, to: dest)
        return dest
    }

    // MARK: - PHPicker 批量加载视频 URL（避免内存爆炸）

    /// 从 PHPicker 结果（含 assetIdentifier）批量加载视频 URL。
    ///
    /// 实现思路：
    /// 1. 用 PHAsset.fetch(withLocalIdentifiers:) 拿到 PHAsset 数组
    /// 2. 对每个 PHAsset，用 PHAssetResourceManager requestData 直接读资源，
    ///    写入我们自己的临时文件（流式写盘，不一次性读入内存）
    /// 3. 限制并发 3 个，避免 IO 峰值
    ///
    /// - Parameter assetIdentifiers: PHPickerResult 的 assetIdentifier 数组
    /// - Returns: 加载成功的本地 URL 数组
    static func loadVideoURLs(assetIdentifiers: [String]) async -> [URL] {
        guard !assetIdentifiers.isEmpty else { return [] }
        print("[loadVideoURLs] 开始加载 \(assetIdentifiers.count) 个视频")

        // 1. 拿到 PHAsset 数组
        let assets: [PHAsset] = await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                let fetchResult = PHAsset.fetchAssets(
                    withLocalIdentifiers: assetIdentifiers,
                    options: nil
                )
                var arr: [PHAsset] = []
                fetchResult.enumerateObjects { asset, _, _ in arr.append(asset) }
                cont.resume(returning: arr)
            }
        }
        guard !assets.isEmpty else {
            print("[loadVideoURLs] 未取到 PHAsset")
            return []
        }
        print("[loadVideoURLs] 取到 \(assets.count) 个 PHAsset，开始写盘")

        // 2. 分批并发写临时文件（每批 3 个，避免 IO 峰值；
        //    不用 DispatchSemaphore——它在 async 上下文里是反模式）
        let inputDir = self.inputDir
        let batchSize = 3
        var results: [URL] = []

        for batchStart in stride(from: 0, to: assets.count, by: batchSize) {
            let batchEnd = min(batchStart + batchSize, assets.count)
            let batchResults = await withTaskGroup(of: (Int, URL?).self) { group in
                for i in batchStart..<batchEnd {
                    let asset = assets[i]
                    group.addTask {
                        return (i, await loadOneVideoFromPHAsset(asset: asset, index: i, inputDir: inputDir))
                    }
                }
                var arr: [(Int, URL?)] = []
                for await r in group { arr.append(r) }
                return arr
            }
            // 按索引排序，保持选择顺序
            for (_, url) in batchResults.sorted(by: { $0.0 < $1.0 }) {
                if let u = url {
                    results.append(u)
                    print("[loadVideoURLs] 已加载 \(results.count)/\(assets.count): \(u.lastPathComponent)")
                }
            }
        }
        print("[loadVideoURLs] 完成，成功 \(results.count)/\(assets.count)")
        return results
    }

    /// 用 PHAssetResourceManager 把单个 PHAsset 的视频资源写入临时文件
    private static func loadOneVideoFromPHAsset(asset: PHAsset,
                                                index: Int,
                                                inputDir: URL) async -> URL? {
        guard let resource = PHAssetResource.assetResources(for: asset).first else {
            print("[loadOne] #\(index) 无 PHAssetResource")
            return nil
        }
        let dest = inputDir.appendingPathComponent("picker_\(index)_\(UUID().uuidString).mov")
        do {
            try FileManager.default.createDirectory(at: inputDir, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            FileManager.default.createFile(atPath: dest.path, contents: nil)
        } catch {
            print("[loadOne] #\(index) 创建文件失败: \(error)")
            return nil
        }

        guard let fileHandle = try? FileHandle(forWritingTo: dest) else {
            print("[loadOne] #\(index) 打开 FileHandle 失败")
            return nil
        }

        let success: Bool = await withCheckedContinuation { cont in
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = true
            PHAssetResourceManager.default().requestData(
                for: resource,
                options: options,
                dataReceivedHandler: { data in
                    // 流式写盘（不保留在内存）
                    fileHandle.write(data)
                },
                completionHandler: { error in
                    try? fileHandle.close()
                    if let error = error {
                        print("[loadOne] #\(index) requestData 失败: \(error)")
                    }
                    cont.resume(returning: error == nil)
                }
            )
        }
        return success ? dest : nil
    }

    // MARK: - 视频元数据（FFprobe）

    /// 用 FFprobeKit 提取视频元数据（宽高、时长、是否有音频）
    static func getVideoMeta(path: String) -> VideoMeta? {
        // 宽高
        guard let dimSession = FFprobeKit.execute(
            "-v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x \"\(path)\""
        ) else { return nil }

        let dimOut = (dimSession.getOutput() ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = dimOut.split(separator: "x")
        guard parts.count == 2,
              let w = Int(parts[0]), let h = Int(parts[1]) else { return nil }

        // 时长
        let durSession = FFprobeKit.execute(
            "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"\(path)\""
        )
        let durOut = (durSession?.getOutput() ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let duration = Double(durOut) ?? 0.0

        // 音频
        let audioSession = FFprobeKit.execute(
            "-v error -select_streams a -show_entries stream=codec_type -of default=noprint_wrappers=1:nokey=1 \"\(path)\""
        )
        let audioOut = (audioSession?.getOutput() ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let hasAudio = audioOut.contains("audio")

        return VideoMeta(width: w, height: h, duration: duration, hasAudio: hasAudio)
    }

    // MARK: - 相册保存

    /// 将合并后的视频文件保存到系统相册（Movies/VideoMerger/）。
    /// 返回 PHAsset 的 localIdentifier。
    @discardableResult
    static func saveToGallery(fileURL: URL, displayName: String) throws -> String {
        var actualURL = fileURL
        var tempResourceAccess = false
        if fileURL.startAccessingSecurityScopedResource() {
            tempResourceAccess = true
        }
        defer {
            if tempResourceAccess { fileURL.stopAccessingSecurityScopedResource() }
        }

        var createdAssetIdentifier: String?
        var saveError: Error?

        let semaphore = DispatchSemaphore(value: 0)

        PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .video, fileURL: actualURL, options: nil)
            request.creationDate = Date()
        } completionHandler: { success, error in
            if success {
                // 取最新保存的视频 asset
                let opts = PHFetchOptions()
                opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
                opts.fetchLimit = 1
                let fetch = PHAsset.fetchAssets(with: .video, options: opts)
                if fetch.count > 0 {
                    createdAssetIdentifier = fetch.firstObject?.localIdentifier
                }
            } else {
                saveError = error
            }
            semaphore.signal()
        }
        semaphore.wait()

        if let err = saveError { throw err }
        _ = actualURL
        return createdAssetIdentifier ?? ""
    }

    // MARK: - 缩略图

    /// 从文件路径加载视频缩略图（第一帧）
    static func loadThumbnailFromFile(path: String) -> UIImage? {
        let url = URL(fileURLWithPath: path)
        return loadThumbnailFromURL(url: url)
    }

    static func loadThumbnailFromURL(url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let imgGen = AVAssetImageGenerator(asset: asset)
        imgGen.appliesPreferredTrackTransform = true
        imgGen.maximumSize = CGSize(width: 720, height: 1280)
        do {
            let cgImage = try imgGen.copyCGImage(at: .zero, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            return nil
        }
    }

    /// 加载 JPEG/PNG 图片文件
    static func loadImageFromFile(path: String) -> UIImage? {
        UIImage(contentsOfFile: path)
    }

    /// 将 UIImage 保存为 JPEG 文件
    @discardableResult
    static func saveImageAsJpeg(_ image: UIImage, to url: URL, quality: CGFloat = 0.85) -> Bool {
        guard let data = image.jpegData(compressionQuality: quality) else { return false }
        do {
            try data.write(to: url)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Logo 检测

    /// 检测视频末尾静止 logo 片段（抖音结尾），返回截断时间戳（秒）。
    /// 检测不到返回 nil。
    static func detectLogoCut(path: String, noise: Double = 0.01, minDur: Double = 0.5) -> Double? {
        guard let session = FFmpegKit.execute(
            "-hide_banner -i \"\(path)\" -filter:v freezedetect=n=\(noise):d=\(minDur) -an -f null -"
        ) else { return nil }

        let logs = session.getAllLogsAsString() ?? ""

        let startPattern = try? NSRegularExpression(pattern: "freeze_start:\\s*([\\d.]+)")
        let endPattern = try? NSRegularExpression(pattern: "freeze_end:\\s*([\\d.]+)")

        var starts: [Double] = []
        var ends: [Double] = []
        let range = NSRange(logs.startIndex..<logs.endIndex, in: logs)

        if let p = startPattern {
            p.enumerateMatches(in: logs, range: range) { match, _, _ in
                if let m = match, m.numberOfRanges > 1,
                   let r = Range(m.range(at: 1), in: logs),
                   let v = Double(logs[r]) {
                    starts.append(v)
                }
            }
        }
        if let p = endPattern {
            p.enumerateMatches(in: logs, range: range) { match, _, _ in
                if let m = match, m.numberOfRanges > 1,
                   let r = Range(m.range(at: 1), in: logs),
                   let v = Double(logs[r]) {
                    ends.append(v)
                }
            }
        }

        if starts.isEmpty { return nil }

        // 取"持续到结尾"的那一段
        let lastEnd = ends.last ?? -1.0
        let tail = starts.filter { $0 > lastEnd }
        let cutStart = tail.first ?? starts.last!

        let endMargin = 0.05
        let cut = cutStart - endMargin
        return cut > 0 ? cut : nil
    }

    // MARK: - 清理

    /// 清理临时输入文件（不删除输出文件）
    static func cleanupInputFiles() {
        try? FileManager.default.removeItem(at: inputDir)
    }

    /// 清理输出文件和缩略图
    static func cleanupOutputFiles() {
        try? FileManager.default.removeItem(at: outputFileURL)
        try? FileManager.default.removeItem(at: thumbnailFileURL)
    }

    // MARK: - 私有

    private static func guessExtension(url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        let known = ["mp4", "mov", "mkv", "avi", "m4v", "webm", "flv", "ts"]
        if known.contains(ext) { return ext }
        return "mp4"
    }
}
