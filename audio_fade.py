#!/usr/bin/env python3
"""
音频淡入淡出工具 —— 为视频音频添加开头淡入和结尾淡出效果。

用法：
    # 开头 2s 淡入 + 结尾 3s 淡出
    python3 audio_fade.py -i input.mp4 --fade-in 2 --fade-out 3

    # 仅开头淡入
    python3 audio_fade.py -i input.mp4 --fade-in 3

    # 仅结尾淡出
    python3 audio_fade.py -i input.mp4 --fade-out 2

参数：
    -i / --input    输入视频路径
    --fade-in       淡入时长（秒，默认 0）
    --fade-out      淡出时长（秒，默认 0）
    -o / --output   输出文件名（默认 output_audio_fade.mp4）

原理：
    afade 滤镜：
    淡入：afade=t=in:st=0:d=fade_in
    淡出：afade=t=out:st=fade_out_start:d=fade_out
    fade_out_start = 总时长 - 淡出时长
    视频流直接复制不重编码（快），音频用 AAC 重新编码
"""

import argparse
import os
import subprocess
import sys


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


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
    ap = argparse.ArgumentParser(description="音频淡入淡出工具")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--fade-in", type=float, default=0.0, help="淡入时长（秒）")
    ap.add_argument("--fade-out", type=float, default=0.0, help="淡出时长（秒）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_audio_fade.mp4）")
    args = ap.parse_args()

    if args.fade_in <= 0 and args.fade_out <= 0:
        print("❌ 请至少指定 --fade-in 或 --fade-out", file=sys.stderr)
        sys.exit(1)

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    duration = get_duration(input_path)
    audio_present = has_audio(input_path)

    if not audio_present:
        print("❌ 视频无音频流", file=sys.stderr)
        sys.exit(1)

    # 构建淡入淡出滤镜链
    filters = []
    if args.fade_in > 0:
        filters.append(f"afade=t=in:st=0:d={args.fade_in}")
    if args.fade_out > 0:
        fade_out_start = max(0, duration - args.fade_out)
        filters.append(f"afade=t=out:st={fade_out_start:.3f}:d={args.fade_out}")

    af = ",".join(filters)

    print(f"输入: {os.path.basename(input_path)}  {duration:.2f}s")
    print(f"  淡入: {args.fade_in}s  淡出: {args.fade_out}s")
    print(f"  淡出起点: {max(0, duration - args.fade_out):.2f}s")

    output_path = args.output or os.path.join(here, "output_audio_fade.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-af", af,
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "192k",
        output_path,
    ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   淡入 {args.fade_in}s + 淡出 {args.fade_out}s")


if __name__ == "__main__":
    main()
