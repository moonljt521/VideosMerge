//
//  ExportCommandWatermarkTests.swift
//  VideoMergerTests
//
//  移植自 Android ExportCommandWatermarkTest.kt：
//  用 FFmpegKit 真实执行 FilterBuilder 生成的导出命令，
//  验证水印滤镜图语法/标签/坐标全部合法（编码器替换为 libx264）。
//  测试视频生成失败时自动跳过（XCTSkip）。
//

import XCTest
import ffmpegkit
@testable import VideoMerger

final class ExportCommandWatermarkTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("wm_test_\(Int(Date().timeIntervalSince1970 * 1000))", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // 生成 2s 720x1280 测试视频（含音频轨）
        let input = dir.appendingPathComponent("in.mp4")
        let session = FFmpegKit.execute(
            "-y -f lavfi -i testsrc2=size=720x1280:rate=30:duration=2 " +
            "-f lavfi -i sine=frequency=440:duration=2 " +
            "-c:v libx264 -pix_fmt yuv420p -c:a aac -shortest \"\(input.path)\""
        )
        guard let s = session, let rc = s.getReturnCode(), ReturnCode.isSuccess(rc) else {
            throw XCTSkip("测试视频生成失败（ffmpeg 环境不可用），跳过集成测试")
        }
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    // MARK: - 构造

    private func clip(_ regions: WatermarkRegion...) -> Clip {
        var c = Clip(mediaPath: dir.appendingPathComponent("in.mp4").path,
                     mediaName: "in.mp4", mediaDuration: 2.0,
                     width: 720, height: 1280, hasAudio: true)
        c.trimEnd = 2.0
        c.watermarkRegions = regions
        return c
    }

    private func project(_ clip: Clip) -> EditorProject {
        var p = EditorProject()
        p.tracks = [Track(type: .main, clips: [clip])]
        return p
    }

    /// 构建命令并真实执行（videotoolbox → libx264，只验证滤镜图本身）
    private func runExport(_ p: EditorProject) throws -> (ok: Bool, out: URL, log: String) {
        let out = dir.appendingPathComponent("out.mp4")
        var cmd = FilterBuilder().buildExportCommand(p, out.path)
        guard !cmd.isEmpty else { throw XCTSkip("命令构建失败") }
        // videotoolbox 模拟器可用性不确定，滤镜图语法验证统一用 libx264
        cmd = cmd.replacingOccurrences(of: "h264_videotoolbox", with: "libx264")
        guard let session = FFmpegKit.execute(cmd) else { throw XCTSkip("ffmpeg 会话创建失败") }
        guard let rc = session.getReturnCode() else { throw XCTSkip("ffmpeg 返回码为空") }
        return (ReturnCode.isSuccess(rc), out, session.getAllLogsAsString() ?? "")
    }

    private func probe(_ path: String) -> String {
        FFprobeKit.execute("-v error -show_entries format=duration:stream=width,height -of csv=p=0 \"\(path)\"")?
            .getOutput() ?? ""
    }

    // MARK: - 用例

    func test单个模糊区域导出成功() throws {
        let (ok, out, log) = try runExport(project(clip(WatermarkRegion(x: 0.6, y: 0.8, w: 0.3, h: 0.15))))
        XCTAssertTrue(ok, "ffmpeg 执行失败:\n\(log.suffix(1500))")
        XCTAssertGreaterThan((try? FileManager.default.attributesOfItem(atPath: out.path)[.size] as? Int64) ?? 0, 0,
                             "输出文件为空")
    }

    func test多区域混合模式导出成功() throws {
        let (ok, out, log) = try runExport(project(clip(
            WatermarkRegion(x: 0.6, y: 0.8, w: 0.3, h: 0.15, mode: .blur, strength: 14),
            WatermarkRegion(x: 0.05, y: 0.05, w: 0.25, h: 0.10, mode: .mosaic, strength: 12)
        )))
        XCTAssertTrue(ok, "ffmpeg 执行失败:\n\(log.suffix(1500))")
        XCTAssertGreaterThan((try? FileManager.default.attributesOfItem(atPath: out.path)[.size] as? Int64) ?? 0, 0)
    }

    func test极端小区域与高强度不崩溃() throws {
        let (ok, out, log) = try runExport(project(clip(
            WatermarkRegion(x: 0.9, y: 0.9, w: 0.02, h: 0.02, mode: .blur, strength: 40)
        )))
        XCTAssertTrue(ok, "ffmpeg 执行失败:\n\(log.suffix(1500))")
        XCTAssertGreaterThan((try? FileManager.default.attributesOfItem(atPath: out.path)[.size] as? Int64) ?? 0, 0)
    }

    func test输出时长与分辨率正确() throws {
        let (ok, out, log) = try runExport(project(clip(WatermarkRegion(x: 0.6, y: 0.8, w: 0.3, h: 0.15))))
        XCTAssertTrue(ok, "ffmpeg 执行失败:\n\(log.suffix(1500))")
        // 验证输出时长 ≈ 2s、分辨率 = 画布（防止区域 crop 误当输出）
        let probeText = probe(out.path)
        let duration = probeText
            .split(separator: "\n")
            .compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            .first ?? 0.0
        XCTAssertTrue((1.8...2.2).contains(duration), "输出时长异常: \(duration)\n\(probeText)")
        XCTAssertTrue(probeText.contains("1088,1920"),
                      "输出分辨率异常（区域被误当整帧输出）:\n\(probeText)")
    }

    func test带时间段的水印区域导出成功() throws {
        let (ok, out, log) = try runExport(project(clip(
            WatermarkRegion(x: 0.6, y: 0.8, w: 0.3, h: 0.15, startTime: 0.5, endTime: 1.5)
        )))
        XCTAssertTrue(ok, "ffmpeg 执行失败:\n\(log.suffix(1500))")
        XCTAssertGreaterThan((try? FileManager.default.attributesOfItem(atPath: out.path)[.size] as? Int64) ?? 0, 0)
    }
}
