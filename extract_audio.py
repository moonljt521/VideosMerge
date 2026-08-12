#!/usr/bin/env python3
"""
音频提取工具 —— 从视频中提取音轨，输出为 MP3/AAC/WAV。

用法：
    # 从当前目录第一个视频提取 MP3
    python3 extract_audio.py

    # 指定输入和格式
    python3 extract_audio.py -i input.mp4 -f aac

    # 提取为 WAV（无损）
    python3 extract_audio.py -i input.mp4 -f wav

    # 提取并指定比特率
    python3 extract_audio.py -i input.mp4 -f mp3 -b 320

参数：
    -i / --input    输入视频路径（默认自动扫描当前目录第一个视频）
    -f / --format   输出音频格式（mp3/aac/wav，默认 mp3）
    -b / --bitrate  音频比特率 kbps（默认 192，wav 无效）
    -o / --output   输出文件名（默认 output_audio.<fmt>）

原理：
    mp3: -c:a libmp3lame -b:a 192k
    aac: -c:a aac -b:a 192k
    wav: -c:a pcm_s16le（无损，无压缩）
    如原音轨已是目标编码可 -c:a copy 直接复制（快），但跨格式时需重新编码
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


def has_audio(path: str) -> bool:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a",
         "-show_entries", "stream=codec_type",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True,
    )
    return "audio" in r.stdout.strip()


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="音频提取工具：从视频中提取音轨")
    ap.add_argument("-i", "--input", default=None, help="输入视频路径")
    ap.add_argument("-f", "--format", choices=["mp3", "aac", "wav"], default="mp3",
                    help="输出音频格式（默认 mp3）")
    ap.add_argument("-b", "--bitrate", type=int, default=192,
                    help="音频比特率 kbps（默认 192，wav 无效）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名")
    args = ap.parse_args()

    # ── 确定输入文件 ──
    if args.input:
        input_path = os.path.abspath(args.input)
    else:
        input_path = scan_first_video(here)
    if not input_path or not os.path.isfile(input_path):
        print("❌ 找不到视频文件", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)
    duration = get_duration(input_path)

    if not audio_present:
        print("❌ 该视频没有音频流", file=sys.stderr)
        sys.exit(1)

    fmt = args.format
    bitrate = args.bitrate

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  时长: {duration:.2f}s")
    print(f"  输出格式: {fmt}")
    print(f"  比特率: {bitrate}k" if fmt != "wav" else "  无损 PCM")

    # ── 构建输出路径 ──
    if args.output:
        output_path = args.output
        if not os.path.isabs(output_path):
            output_path = os.path.join(here, output_path)
    else:
        output_path = os.path.join(here, f"output_audio.{fmt}")

    # ── 构建 ffmpeg 命令 ──
    if fmt == "mp3":
        codec_args = ["-c:a", "libmp3lame", "-b:a", f"{bitrate}k"]
    elif fmt == "aac":
        codec_args = ["-c:a", "aac", "-b:a", f"{bitrate}k"]
    else:  # wav
        codec_args = ["-c:a", "pcm_s16le"]

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-vn",  # 丢弃视频
        *codec_args,
        output_path,
    ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")


if __name__ == "__main__":
    main()
