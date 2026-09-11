#!/usr/bin/env python3
"""
批量处理工具 —— 对目录下所有视频批量执行指定操作。

内置支持常用操作（裁剪、缩放、旋转、压缩等），也可自定义 ffmpeg 参数。

用法：
    # 批量截取前 5 秒
    python3 batch_process.py -d ./videos --op trim --start 0 --duration 5

    # 批量缩放到 480p
    python3 batch_process.py -d ./videos --op scale --short 480

    # 批量压缩
    python3 batch_process.py -d ./videos --op compress --crf 30

    # 批量旋转 90 度
    python3 batch_process.py -d ./videos --op rotate --rotate 90

    # 批量转 H.264
    python3 batch_process.py -d ./videos --op convert --codec h264

    # 批量提取音频
    python3 batch_process.py -d ./videos --op extract_audio --format mp3

参数：
    -d / --dir       视频目录（默认当前目录）
    --op             操作类型（trim/scale/rotate/compress/convert/extract_audio）
    -o / --outdir    输出目录（默认 目录/batch_output/）
    其余参数传递给对应操作

原理：
    扫描目录下所有视频 → 逐个调用 ffmpeg → 输出到指定目录
    等价于对每个视频执行对应脚本
"""

import argparse
import os
import subprocess
import sys

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")

OPS = ["trim", "scale", "rotate", "compress", "convert", "extract_audio", "mute"]


def scan_videos(directory: str):
    files = []
    for f in sorted(os.listdir(directory)):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") \
                and not f.lower().startswith("output") \
                and "batch_output" not in f:
            files.append(os.path.join(directory, f))
    return files


def get_dims_dur(path: str):
    r = subprocess.run(
        ["ffprobe", "-v", "error",
         "-show_entries", "format=duration:stream=width,height",
         "-of", "json", path],
        capture_output=True, text=True, check=True,
    )
    import json
    info = json.loads(r.stdout)
    dur = float(info.get("format", {}).get("duration", 0))
    vw = vh = 0
    for s in info.get("streams", []):
        if s.get("codec_type") == "video" and vw == 0:
            vw, vh = int(s["width"]), int(s["height"])
    return vw, vh, dur


def build_command(op, input_path, output_path, args, vw, vh, dur):
    """根据操作类型构建 ffmpeg 命令"""
    if op == "trim":
        start = args.start or 0
        d = args.duration or 5
        return [
            "ffmpeg", "-y", "-ss", f"{start}", "-i", input_path,
            "-t", f"{d}", "-c", "copy", output_path,
        ]
    elif op == "scale":
        s = args.short or 480
        if vw <= vh:
            w = round(s * vw / vh)
            h = s
        else:
            w = s
            h = round(s * vh / vw)
        w -= w % 2
        h -= h % 2
        return [
            "ffmpeg", "-y", "-i", input_path,
            "-vf", f"scale={w}:{h}:flags=lanczos,setsar=1",
            "-c:v", "libx264", "-crf", "20", "-preset", "medium",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            output_path,
        ]
    elif op == "rotate":
        r = args.rotate or 90
        trans = {90: "transpose=1", -90: "transpose=0", 180: "transpose=1,transpose=1"}.get(r, "transpose=1")
        return [
            "ffmpeg", "-y", "-i", input_path,
            "-vf", trans,
            "-c:v", "libx264", "-crf", "20", "-preset", "medium",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            output_path,
        ]
    elif op == "compress":
        crf = args.crf or 30
        s = args.scale or 0.75
        w = round(vw * s) - round(vw * s) % 2
        h = round(vh * s) - round(vh * s) % 2
        return [
            "ffmpeg", "-y", "-i", input_path,
            "-vf", f"scale={w}:{h}:flags=lanczos",
            "-c:v", "libx264", "-crf", str(crf), "-preset", "medium",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k",
            output_path,
        ]
    elif op == "convert":
        codec = args.codec or "h264"
        encoder = {"h264": "libx264", "hevc": "libx265", "h265": "libx265"}.get(codec, "libx264")
        return [
            "ffmpeg", "-y", "-i", input_path,
            "-c:v", encoder, "-crf", "20", "-preset", "medium",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            output_path,
        ]
    elif op == "extract_audio":
        fmt = args.format or "mp3"
        codec_map = {"mp3": "libmp3lame", "aac": "aac", "wav": "pcm_s16le"}
        ext = fmt
        out = output_path.rsplit(".", 1)[0] + f".{ext}"
        return [
            "ffmpeg", "-y", "-i", input_path,
            "-vn", "-c:a", codec_map.get(fmt, "libmp3lame"),
            "-b:a", "192k", out,
        ]
    elif op == "mute":
        return [
            "ffmpeg", "-y", "-i", input_path,
            "-c:v", "copy", "-an", output_path,
        ]
    return None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="批量处理工具：对目录下所有视频批量执行操作")
    ap.add_argument("-d", "--dir", default=".", help="视频目录（默认当前目录）")
    ap.add_argument("--op", required=True, choices=OPS, help="操作类型")
    ap.add_argument("-o", "--outdir", default=None, help="输出目录（默认 目录/batch_output/）")
    # 通用参数
    ap.add_argument("--start", type=float, default=None)
    ap.add_argument("--duration", type=float, default=None)
    ap.add_argument("--short", type=int, default=None)
    ap.add_argument("--rotate", type=int, default=None)
    ap.add_argument("--crf", type=int, default=None)
    ap.add_argument("--scale", type=float, default=None)
    ap.add_argument("--codec", default=None)
    ap.add_argument("--format", default=None)
    args = ap.parse_args()

    directory = os.path.abspath(args.dir)
    videos = scan_videos(directory)

    if not videos:
        print(f"❌ 目录 {directory} 下没有视频文件", file=sys.stderr)
        sys.exit(1)

    outdir = args.outdir or os.path.join(directory, "batch_output")
    os.makedirs(outdir, exist_ok=True)

    print(f"批量操作: {args.op}")
    print(f"目录: {directory}")
    print(f"视频数量: {len(videos)}")
    print(f"输出目录: {outdir}\n")

    success = 0
    failed = 0
    for i, v in enumerate(videos):
        name = os.path.splitext(os.path.basename(v))[0]
        ext = "mp4" if args.op != "extract_audio" else (args.format or "mp3")
        out_path = os.path.join(outdir, f"{name}.{ext}")

        vw, vh, dur = get_dims_dur(v)
        cmd = build_command(args.op, v, out_path, args, vw, vh, dur)

        if not cmd:
            print(f"  [{i+1}/{len(videos)}] ❌ 未知操作")
            failed += 1
            continue

        print(f"  [{i+1}/{len(videos)}] {os.path.basename(v)} ({vw}x{vh}, {dur:.1f}s)")

        try:
            subprocess.run(cmd, check=True, capture_output=True)
            print(f"    ✅ → {os.path.basename(out_path)}")
            success += 1
        except subprocess.CalledProcessError as e:
            print(f"    ❌ 失败: {e}")
            failed += 1

    print(f"\n{'='*40}")
    print(f"完成: {success} 成功, {failed} 失败, 共 {len(videos)} 个")
    print(f"输出目录: {outdir}")


if __name__ == "__main__":
    main()
