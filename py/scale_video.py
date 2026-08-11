#!/usr/bin/env python3
"""
视频缩放工具 —— 改变视频分辨率。

支持等比缩放（保比例）和强制缩放（改比例），可选填充黑边或裁剪多余画面。

用法：
    # 缩放到 720p（等比缩放，短边=720）
    python3 scale_video.py -i input.mp4 -s 720

    # 指定宽高（强制缩放）
    python3 scale_video.py -i input.mp4 -w 1920 -h 1080

    # 等比缩放后填充黑边到目标尺寸
    python3 scale_video.py -i input.mp4 -w 1920 -h 1080 --fit pad

    # 等比缩放后裁剪填充到目标尺寸
    python3 scale_video.py -i input.mp4 -w 1920 -h 1080 --fit crop

参数：
    -i / --input    输入视频路径（默认自动扫描当前目录第一个视频）
    -s / --short    短边目标像素（如 720、1080），等比缩放
    -w / --width    目标宽度像素
    -h / --height   目标高度像素
    --fit           缩放模式：auto（默认，等比缩放不改尺寸）/ pad（填充黑边）/ crop（裁剪填充）
    -o / --output   输出文件名（默认 output_scale.mp4）

原理：
    auto:  scale=短边:目标（等比）
    pad:   scale + pad 填充到目标宽高
    crop:  scale + crop 填充到目标宽高
"""

import argparse
import os
import subprocess
import sys

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")


def scan_first_video(directory: str) -> str | None:
    for f in sorted(os.listdir(directory)):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") \
                and not f.lower().startswith("output"):
            return os.path.join(directory, f)
    return None


def get_dimensions(path: str):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def has_audio(path: str) -> bool:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a",
         "-show_entries", "stream=codec_type",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True,
    )
    return "audio" in r.stdout.strip()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="视频缩放工具：改变视频分辨率")
    ap.add_argument("-i", "--input", default=None, help="输入视频路径")
    ap.add_argument("-s", "--short", type=int, default=None,
                    help="短边目标像素（等比缩放，如 720/1080）")
    ap.add_argument("-w", "--width", type=int, default=None, help="目标宽度像素")
    ap.add_argument("--height", type=int, default=None, help="目标高度像素")
    ap.add_argument("--fit", choices=["auto", "pad", "crop"], default="auto",
                    help="缩放模式：auto=等比缩放 / pad=填充黑边 / crop=裁剪填充")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_scale.mp4）")
    args = ap.parse_args()

    # ── 确定输入文件 ──
    if args.input:
        input_path = os.path.abspath(args.input)
    else:
        input_path = scan_first_video(here)
    if not input_path or not os.path.isfile(input_path):
        print("❌ 找不到视频文件", file=sys.stderr)
        sys.exit(1)

    # ── 确定目标尺寸 ──
    src_w, src_h = get_dimensions(input_path)
    audio_present = has_audio(input_path)

    if args.short:
        # 短边模式：等比缩放
        if src_w <= src_h:
            dst_w = round(args.short * src_w / src_h)
            dst_h = args.short
        else:
            dst_w = args.short
            dst_h = round(args.short * src_h / src_w)
    elif args.width and args.height:
        dst_w, dst_h = args.width, args.height
    else:
        print("❌ 请指定 --short 或 --width + --height", file=sys.stderr)
        sys.exit(1)

    # 确保偶数
    dst_w -= dst_w % 2
    dst_h -= dst_h % 2

    print(f"输入: {os.path.basename(input_path)}  {src_w}x{src_h}")
    print(f"目标: {dst_w}x{dst_h}  模式: {args.fit}  音频: {'有' if audio_present else '无'}")

    # ── 构建滤镜 ──
    if args.fit == "auto":
        vf = f"scale={dst_w}:{dst_h}:flags=lanczos,setsar=1"
    elif args.fit == "pad":
        vf = (f"scale={dst_w}:{dst_h}:force_original_aspect_ratio=decrease:flags=lanczos,"
              f"pad={dst_w}:{dst_h}:(ow-iw)/2:(oh-ih)/2:black,setsar=1")
    else:  # crop
        vf = (f"scale={dst_w}:{dst_h}:force_original_aspect_ratio=increase:flags=lanczos,"
              f"crop={dst_w}:{dst_h},setsar=1")

    # ── 构建命令 ──
    output_path = args.output or os.path.join(here, "output_scale.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-vf", vf,
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "20",
    ]
    if audio_present:
        cmd.extend(["-c:a", "aac", "-b:a", "192k"])
    else:
        cmd.append("-an")
    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {src_w}x{src_h} → {dst_w}x{dst_h}")


if __name__ == "__main__":
    main()
