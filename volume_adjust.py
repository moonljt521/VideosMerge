#!/usr/bin/env python3
"""
音量调整工具 —— 调整视频音量大小或静音。

用法：
    # 音量放大 2 倍
    python3 volume_adjust.py -i input.mp4 -v 2.0

    # 音量降低一半
    python3 volume_adjust.py -i input.mp4 -v 0.5

    # 静音（移除音频）
    python3 volume_adjust.py -i input.mp4 --mute

    # 指定输出
    python3 volume_adjust.py -i input.mp4 -v 1.5 -o loud.mp4

参数：
    -i / --input    输入视频路径
    -v / --volume   音量倍率（默认 1.5，0.0=静音，2.0=放大两倍）
    --mute          完全移除音频（与 -v 0 不同：--mute 无音轨，-v 0 有静音轨）
    -o / --output   输出文件名（默认 output_volume.mp4）

原理：
    volume 滤镜：volume=2.0（线性放大）
    视频流直接复制不重编码（快），音频用 AAC 重新编码
    也可用 -af "volume=2.0" 简化（单输入时）
"""

import argparse
import os
import subprocess
import sys


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
    ap = argparse.ArgumentParser(description="音量调整工具：调整视频音量或静音")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-v", "--volume", type=float, default=1.5,
                    help="音量倍率（默认 1.5，0.0=静音，2.0=放大两倍）")
    ap.add_argument("--mute", action="store_true", help="完全移除音频轨道")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_volume.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)

    if args.mute:
        print(f"输入: {os.path.basename(input_path)}")
        print(f"  操作: 移除音频")
    elif not audio_present:
        print(f"⚠️  视频无音频流，将直接复制视频", file=sys.stderr)
    else:
        print(f"输入: {os.path.basename(input_path)}")
        print(f"  音量倍率: {args.volume}x")

    output_path = args.output or os.path.join(here, "output_volume.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建命令 ──
    if args.mute or not audio_present:
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-c:v", "copy",
            "-an",  # 移除音频
            output_path,
        ]
    else:
        # 视频直接复制，音频用 volume 滤镜调整
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-af", f"volume={args.volume}",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "192k",
            output_path,
        ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)

    if args.mute:
        print(f"✅ 完成: {output_path}（已移除音频）")
    else:
        print(f"✅ 完成: {output_path}（音量 {args.volume}x）")


if __name__ == "__main__":
    main()
