//
//  InpaintEngine.swift
//  VideoMerger
//
//  视频去水印（局部内容修复）引擎：
//    探测帧 → 静态遮罩 → ROI 分块逐帧修复 → overlay 回原片 → 拼接 + 原音轨/元数据
//
//  设计要点：
//  - 只重编码画面，音频 copy；AIGC 等元数据标签原样回写
//  - 全链路使用「显示方向」坐标（ffmpeg 默认 autorotate），输出不带 rotation 元数据
//  - 分块处理控制磁盘峰值：一段 raws → 编码无音轨分段 → concat → mux 原音轨
//

import Foundation
import UIKit
import ffmpegkit

struct InpaintVideoMeta {
    let displayWidth: Int
    let displayHeight: Int
    let duration: Double
    let fps: Double
    let frameCount: Int
    let hasAudio: Bool
    let isVFR: Bool
}

struct InpaintPrepared {
    let meta: InpaintVideoMeta
    let probeTime: Double
    let probeImage: UIImage
    /// ROI 大小的修复结果与遮罩（UI 按 roi 位置叠在整帧上，避免 4K 下多张全帧位图撑内存）
    let afterROI: UIImage
    let maskROI: UIImage
    let mask: InpaintMask
    var roi: InpaintRect { mask.roi }
}

enum InpaintEngineError: LocalizedError {
    case ffmpeg(String)
    case probeFailed
    case maskFailed(String)
    case cancelled
    case outputEmpty

    var errorDescription: String? {
        switch self {
        case .ffmpeg(let msg): return msg
        case .probeFailed: return L10n.t("repair.error_probe")
        case .maskFailed(let msg): return msg
        case .cancelled: return L10n.t("repair.cancelled")
        case .outputEmpty: return L10n.t("repair.error_output")
        }
    }
}

final class InpaintEngine {

    static let shared = InpaintEngine()
    private let fm = FileManager.default

    // MARK: - 元数据

    func probeMeta(path: String, displayWidth: Int, displayHeight: Int) -> InpaintVideoMeta? {
        let cmd = "-v error -select_streams v:0 " +
            "-show_entries stream=width,height,r_frame_rate,avg_frame_rate,nb_frames " +
            "-show_entries format=duration -of json \"\(path)\""
        guard let session = FFprobeKit.execute(cmd),
              let out = session.getOutput(),
              let data = out.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }

        let stream = (json["streams"] as? [[String: Any]])?.first ?? [:]
        let format = json["format"] as? [String: Any] ?? [:]

        let rFps = Self.parseRate(stream["r_frame_rate"] as? String)
        let aFps = Self.parseRate(stream["avg_frame_rate"] as? String)
        let fps = rFps > 0 ? rFps : (aFps > 0 ? aFps : 30)
        let duration = Double(format["duration"] as? String ?? "") ?? 0
        var frames = Int(stream["nb_frames"] as? String ?? "") ?? 0
        if frames <= 0 { frames = max(Int((duration * fps).rounded()), 1) }
        let vfr = rFps > 0 && aFps > 0 && abs(rFps - aFps) / rFps > 0.01

        let audioCmd = "-v error -select_streams a -show_entries stream=codec_type " +
            "-of default=noprint_wrappers=1:nokey=1 \"\(path)\""
        let hasAudio = (FFprobeKit.execute(audioCmd)?.getOutput() ?? "").contains("audio")

        guard duration > 0, displayWidth > 0, displayHeight > 0 else { return nil }
        return InpaintVideoMeta(displayWidth: displayWidth, displayHeight: displayHeight,
                                duration: duration, fps: fps, frameCount: frames,
                                hasAudio: hasAudio, isVFR: vfr)
    }

    private static func parseRate(_ s: String?) -> Double {
        guard let s = s, !s.isEmpty else { return 0 }
        let parts = s.split(separator: "/")
        if parts.count == 2, let n = Double(parts[0]), let d = Double(parts[1]), d > 0 {
            return n / d
        }
        return Double(s) ?? 0
    }

    // MARK: - 探测帧

    /// 抽一帧全分辨率 PNG（显示方向），返回图片与像素尺寸
    func extractProbeFrame(path: String, time: Double, to url: URL) -> (UIImage, Int, Int)? {
        try? fm.removeItem(at: url)
        let cmd = "-v error -ss \(String(format: "%.3f", max(time, 0))) -i \"\(path)\" " +
            "-frames:v 1 -update 1 \"\(url.path)\" -y"
        guard runFFmpeg(cmd), let image = UIImage(contentsOfFile: url.path), let cg = image.cgImage else {
            return nil
        }
        return (image, cg.width, cg.height)
    }

    /// 自动挑选探测帧：抽样缩略图，选水印框外围梯度最低（背景最干净）的一帧
    func autoProbeTime(path: String, duration: Double, fps: Double, bboxNorm: CGRect) -> Double {
        guard duration > 0.5 else { return 0 }
        let dir = fm.temporaryDirectory.appendingPathComponent("inpaint_probe_\(Int(Date().timeIntervalSince1970 * 1000))")
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: dir) }

        let n = duration < 2 ? 4 : 8
        let rate = Double(n) / duration
        let cmd = "-v error -i \"\(path)\" -vf \"fps=\(String(format: "%.6f", rate)),scale=240:-2\" " +
            "-frames:v \(n) -q:v 3 \"\(dir.path)/t_%02d.jpg\" -y"
        guard runFFmpeg(cmd) else { return duration * 0.3 }
        let files = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?
            .filter { $0.lastPathComponent.hasPrefix("t_") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent } ?? []
        guard !files.isEmpty else { return duration * 0.3 }

        var bestIdx = 0
        var bestScore = Double.greatestFiniteMagnitude
        for (i, f) in files.enumerated() {
            guard let img = UIImage(contentsOfFile: f.path) else { continue }
            let luma = WatermarkDetector.toLumaArray(img)
            let aw = Int(img.size.width * img.scale)
            let ah = Int(img.size.height * img.scale)
            guard luma.count == aw * ah, aw > 4, ah > 4 else { continue }
            let score = ringGradientEnergy(luma: luma, w: aw, h: ah, bboxNorm: bboxNorm)
            if score < bestScore { bestScore = score; bestIdx = i }
        }
        return min(max((Double(bestIdx) + 0.5) * duration / Double(files.count), 0), max(duration - 0.05, 0))
    }

    private func ringGradientEnergy(luma: [Float], w: Int, h: Int, bboxNorm: CGRect) -> Double {
        let x0 = max(Int(bboxNorm.minX * Double(w)) - 8, 0)
        let y0 = max(Int(bboxNorm.minY * Double(h)) - 8, 0)
        let x1 = min(Int(bboxNorm.maxX * Double(w)) + 8, w - 2)
        let y1 = min(Int(bboxNorm.maxY * Double(h)) + 8, h - 2)
        guard x1 > x0 + 2, y1 > y0 + 2 else { return .greatestFiniteMagnitude }
        var sum = 0.0
        var count = 0
        for y in y0...y1 {
            for x in x0...x1 {
                let inside = x >= Int(bboxNorm.minX * Double(w)) && x <= Int(bboxNorm.maxX * Double(w)) &&
                    y >= Int(bboxNorm.minY * Double(h)) && y <= Int(bboxNorm.maxY * Double(h))
                if inside { continue }
                let i = y * w + x
                let gx = abs(luma[i + 1] - luma[i])
                let gy = abs(luma[i + w] - luma[i])
                sum += Double(gx + gy)
                count += 1
            }
        }
        return count > 0 ? sum / Double(count) : .greatestFiniteMagnitude
    }

    // MARK: - 遮罩与预览

    func prepare(sourcePath: String,
                 meta: InpaintVideoMeta,
                 probeTime: Double,
                 bboxPx: InpaintRect,
                 dilate: Int,
                 probeCache: URL) -> Result<InpaintPrepared, Error> {
        guard let (image, w, h) = extractProbeFrame(path: sourcePath, time: probeTime, to: probeCache) else {
            return .failure(InpaintEngineError.probeFailed)
        }
        let mw = meta.displayWidth
        let mh = meta.displayHeight
        guard w == mw, h == mh else {
            // 极少数情况下 ffprobe 尺寸与解码帧不一致：以解码帧为准
            let fixed = InpaintVideoMeta(displayWidth: w, displayHeight: h, duration: meta.duration,
                                         fps: meta.fps, frameCount: meta.frameCount,
                                         hasAudio: meta.hasAudio, isVFR: meta.isVFR)
            return buildPreview(image: image, width: w, height: h, meta: fixed,
                                probeTime: probeTime, bboxPx: bboxPx, dilate: dilate)
        }
        return buildPreview(image: image, width: w, height: h, meta: meta,
                            probeTime: probeTime, bboxPx: bboxPx, dilate: dilate)
    }

    private func buildPreview(image: UIImage, width: Int, height: Int, meta: InpaintVideoMeta,
                              probeTime: Double, bboxPx: InpaintRect, dilate: Int) -> Result<InpaintPrepared, Error> {
        guard let rgb = Self.rgbBytes(from: image, width: width, height: height) else {
            return .failure(InpaintEngineError.probeFailed)
        }
        var params = InpaintMaskParams()
        params.dilateKernel = max(5, min(dilate, 25))
        let res = InpaintMaskBuilder.build(rgb: rgb, frameW: width, frameH: height, bbox: bboxPx, params: params)
        guard let mask = res.mask else {
            return .failure(InpaintEngineError.maskFailed(res.message ?? L10n.t("repair.error_no_mask")))
        }
        let roi = mask.roi
        var roiRGB = [UInt8](repeating: 0, count: roi.w * roi.h * 3)
        for y in 0..<roi.h {
            let srcOff = ((roi.y + y) * width + roi.x) * 3
            let dstOff = y * roi.w * 3
            for i in 0..<(roi.w * 3) { roiRGB[dstOff + i] = rgb[srcOff + i] }
        }
        var repaired = roiRGB
        TeleaInpainter(w: roi.w, h: roi.h, mask: mask.mask).inpaint(&repaired)

        // 遮罩可视化（ROI 大小）：红色 45% 像素
        var overlay = [UInt8](repeating: 0, count: roi.w * roi.h * 4)
        for i in 0..<(roi.w * roi.h) where mask.mask[i] {
            overlay[i * 4] = 255; overlay[i * 4 + 1] = 70
            overlay[i * 4 + 2] = 70; overlay[i * 4 + 3] = 115
        }

        guard let after = Self.uiImage(rgb: repaired, width: roi.w, height: roi.h),
              let ov = Self.uiImageRGBA(rgba: overlay, width: roi.w, height: roi.h) else {
            return .failure(InpaintEngineError.maskFailed(L10n.t("repair.error_mask_image")))
        }
        return .success(InpaintPrepared(meta: meta, probeTime: probeTime, probeImage: image,
                                        afterROI: after, maskROI: ov, mask: mask))
    }

    // MARK: - 修复

    func repair(sourcePath: String,
                prepared: InpaintPrepared,
                outputPath: String,
                onProgress: @escaping (Double, String) -> Void,
                isCancelled: @escaping () -> Bool) -> Result<URL, Error> {
        let meta = prepared.meta
        let mask = prepared.mask
        let roi = mask.roi
        let frameBytes = roi.w * roi.h * 3
        guard frameBytes > 0 else { return .failure(InpaintEngineError.maskFailed(L10n.t("repair.error_no_mask"))) }

        let work = fm.temporaryDirectory.appendingPathComponent("inpaint_work_\(Int(Date().timeIntervalSince1970 * 1000))")
        try? fm.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: work) }

        let totalFrames = max(meta.frameCount, 1)
        let fps = max(meta.fps, 1)
        // 每块原始帧数据 ≤ 96MB；至少 1s，最多 20s
        let perByBytes = max(96 * 1024 * 1024 / frameBytes, 1)
        let per = max(min(Int(fps * 20), perByBytes), min(Int(fps), 24))

        let inpainter = TeleaInpainter(w: roi.w, h: roi.h, mask: mask.mask)
        var segments: [URL] = []
        var processed = 0
        var frame = [UInt8](repeating: 0, count: frameBytes)

        onProgress(0.02, L10n.t("repair.phase_analyze"))
        // ★ 按「实际解码帧数」推进，而不是按秒数切段：-ss/-t 的取整会在分块边界丢帧
        var nextFrame = 0
        while nextFrame < totalFrames {
            if isCancelled() { return .failure(InpaintEngineError.cancelled) }
            let want = min(per, totalFrames - nextFrame)
            let t0 = Double(nextFrame) / fps
            let tag = String(format: "%06d", nextFrame)

            let rawIn = work.appendingPathComponent("in_\(tag).rgb")
            let rawOut = work.appendingPathComponent("out_\(tag).rgb")
            try? fm.removeItem(at: rawIn)
            try? fm.removeItem(at: rawOut)

            // ① 解出 ROI 原始帧（按帧数截断，避免秒数取整丢帧）
            let decode = "-v error -ss \(String(format: "%.6f", t0)) " +
                "-i \"\(sourcePath)\" -vf \"crop=\(roi.w):\(roi.h):\(roi.x):\(roi.y)\" -an " +
                "-fps_mode cfr -r \(String(format: "%.6f", fps)) -frames:v \(want) " +
                "-f rawvideo -pix_fmt rgb24 \"\(rawIn.path)\" -y"
            guard runFFmpeg(decode), fm.fileExists(atPath: rawIn.path) else {
                return .failure(InpaintEngineError.ffmpeg(L10n.t("repair.error_decode")))
            }

            // ② 逐帧修复（流式读写，单帧内存）
            guard let fIn = FileHandle(forReadingAtPath: rawIn.path),
                  fm.createFile(atPath: rawOut.path, contents: nil),
                  let fOut = FileHandle(forWritingAtPath: rawOut.path) else {
                return .failure(InpaintEngineError.ffmpeg(L10n.t("repair.error_io")))
            }
            defer { try? fIn.close(); try? fOut.close() }
            let fdIn = fIn.fileDescriptor
            let fdOut = fOut.fileDescriptor
            var eof = false
            while !eof {
                if isCancelled() { return .failure(InpaintEngineError.cancelled) }
                var read = 0
                frame.withUnsafeMutableBytes { raw -> Void in
                    guard let base = raw.baseAddress else { return }
                    while read < frameBytes {
                        let n = Darwin.read(fdIn, base + read, frameBytes - read)
                        if n <= 0 { break }
                        read += n
                    }
                }
                if read < frameBytes { eof = true; break }
                inpainter.inpaint(&frame)
                frame.withUnsafeBytes { raw in
                    if let base = raw.baseAddress { _ = Darwin.write(fdOut, base, frameBytes) }
                }
                processed += 1
                if processed % 6 == 0 {
                    let p = 0.03 + 0.9 * min(Double(processed) / Double(totalFrames), 1)
                    onProgress(p, L10n.t("repair.phase_process"))
                }
            }
            try? fIn.close(); try? fOut.close()

            let chunkFrames = fileSize(rawOut.path) / Int64(frameBytes)
            if chunkFrames <= 0 {
                // nb_frames 可能比实际略多：已经处理过内容则视为到达片尾
                if processed > 0 { break }
                return .failure(InpaintEngineError.ffmpeg(L10n.t("repair.error_decode")))
            }

            // ③ 编码无音轨分段（帧数与 raw 一致）
            let seg = work.appendingPathComponent("seg_\(tag).mp4")
            let encode = "-v error -ss \(String(format: "%.6f", t0)) " +
                "-i \"\(sourcePath)\" -f rawvideo -pix_fmt rgb24 -s \(roi.w)x\(roi.h) " +
                "-framerate \(String(format: "%.6f", fps)) -i \"\(rawOut.path)\" " +
                "-filter_complex \"[0:v][1:v]overlay=\(roi.x):\(roi.y):shortest=1,format=yuv420p[v]\" " +
                "-map \"[v]\" -an -frames:v \(chunkFrames) -c:v h264_videotoolbox -b:v 8M " +
                "-fps_mode passthrough \"\(seg.path)\" -y"
            guard runFFmpeg(encode), fileSize(seg.path) > 0 else {
                return .failure(InpaintEngineError.ffmpeg(L10n.t("repair.error_encode")))
            }
            segments.append(seg)
            try? fm.removeItem(at: rawIn)
            try? fm.removeItem(at: rawOut)

            nextFrame += Int(chunkFrames)
            onProgress(0.03 + 0.9 * min(Double(processed) / Double(totalFrames), 1),
                       L10n.t("repair.phase_process"))
        }

        if segments.isEmpty { return .failure(InpaintEngineError.ffmpeg(L10n.t("repair.error_encode"))) }
        if isCancelled() { return .failure(InpaintEngineError.cancelled) }

        // ④ concat 视频 + 原音轨/元数据
        onProgress(0.95, L10n.t("repair.phase_mux"))
        let list = work.appendingPathComponent("concat.txt")
        let listBody = segments.map { "file '\($0.path.replacingOccurrences(of: "'", with: "'\\''"))'\n" }.joined()
        try? listBody.write(to: list, atomically: true, encoding: .utf8)

        try? fm.removeItem(atPath: outputPath)
        let mux = "-v error -f concat -safe 0 -i \"\(list.path)\" -i \"\(sourcePath)\" " +
            "-map 0:v:0 -map 1:a? -c copy -map_metadata 1 " +
            "-movflags +faststart+use_metadata_tags \"\(outputPath)\" -y"
        guard runFFmpeg(mux), fileSize(outputPath) > 0 else {
            return .failure(InpaintEngineError.outputEmpty)
        }
        onProgress(1.0, L10n.t("repair.phase_done"))
        return .success(URL(fileURLWithPath: outputPath))
    }

    // MARK: - 工具

    private func fileSize(_ path: String) -> Int64 {
        ((try? fm.attributesOfItem(atPath: path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    @discardableResult
    private func runFFmpeg(_ cmd: String) -> Bool {
        guard let session = FFmpegKit.execute(cmd), let rc = session.getReturnCode() else { return false }
        if ReturnCode.isSuccess(rc) { return true }
        let logs = session.getAllLogsAsString() ?? ""
        print("[InpaintEngine] ffmpeg failed:\n\(cmd)\n\(logs.suffix(800))")
        return false
    }

    static func rgbBytes(from image: UIImage, width: Int, height: Int) -> [UInt8]? {
        guard let cg = image.cgImage else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let info = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        guard let ctx = CGContext(data: &rgba, width: width, height: height, bitsPerComponent: 8,
                                  bytesPerRow: width * 4, space: space, bitmapInfo: info) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        for i in 0..<(width * height) {
            rgb[i * 3] = rgba[i * 4]
            rgb[i * 3 + 1] = rgba[i * 4 + 1]
            rgb[i * 3 + 2] = rgba[i * 4 + 2]
        }
        return rgb
    }

    static func uiImage(rgb: [UInt8], width: Int, height: Int) -> UIImage? {
        guard rgb.count >= width * height * 3,
              let provider = CGDataProvider(data: Data(rgb) as CFData) else { return nil }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue)
        guard let cg = CGImage(width: width, height: height, bitsPerComponent: 8,
                               bitsPerPixel: 24, bytesPerRow: width * 3,
                               space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info,
                               provider: provider, decode: nil,
                               shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func uiImageRGBA(rgba: [UInt8], width: Int, height: Int) -> UIImage? {
        guard rgba.count >= width * height * 4,
              let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard let cg = CGImage(width: width, height: height, bitsPerComponent: 8,
                               bitsPerPixel: 32, bytesPerRow: width * 4,
                               space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info,
                               provider: provider, decode: nil,
                               shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }
}


/// 丢到后台队列执行同步引擎调用
func inpaintBackground<T>(_ work: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { cont in
        DispatchQueue.global(qos: .userInitiated).async {
            do { cont.resume(returning: try work()) }
            catch { cont.resume(throwing: error) }
        }
    }
}

/// 不抛错的版本（内部返回 Result 的引擎调用）
func inpaintBackgroundValue<T>(_ work: @escaping () -> T) async -> T {
    await withCheckedContinuation { cont in
        DispatchQueue.global(qos: .userInitiated).async { cont.resume(returning: work()) }
    }
}
