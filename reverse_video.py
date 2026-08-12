#!/usr/bin/env python3
"""
视频倒放工具 —— 反向播放视频，画面和音频同步倒退。

用法：
    # 对当前目录第一个视频倒放
    python3 reverse_video.py

    # 指定输入输出
    python3 reverse_video.py -i input.mp4 -o reversed.mp4

    # 只倒放画面，不倒放音频
    python3 reverse_video.py -i input.mp4 --no-audio-reverse

参数：
    -i / --input          输入视频路径（默认自动扫描当前目录第一个视频）
    -o / --output         输出文件名（默认 output_reverse.mp4）
    --no-audio-reverse    音频不倒放（静音原音轨或丢弃音频）
    --mute                完全移除音频

原理：
    视频：reverse 滤镜（需要全量解码到内存，大视频注意内存占用）
    音频：areverse 滤镜（同步倒放）
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
    ap = argparse.ArgumentParser(description="视频倒放工具：反向播放视频")
    ap.add_argument("-i", "--input", default=None, help="输入视频路径")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_reverse.mp4）")
    ap.add_argument("--no-audio-reverse", action="store_true",
                    help="音频不倒放（保留正向音频）")
    ap.add_argument("--mute", action="store_true", help="移除音频")
    args = ap.parse_args()

    # ── 确定输入文件 ──
    if args.input:
        input_path = os.path.abspath(args.input)
    else:
        input_path = scan_first_video(here)
    if not input_path or not os.path.isfile(input_path):
        print("❌ 找不到视频文件", file=sys.stderr)
        sys.exit(1)

    duration = get_duration(input_path)
    audio_present = has_audio(input_path)

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  时长: {duration:.2f}s")
    print(f"  音频: {'有' if audio_present else '无'}")

    # ── 构建滤镜 ──
    output_path = args.output or os.path.join(here, "output_reverse.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 视频必倒放
    video_filter = "[0:v]reverse[vout]"

    keep_audio = audio_present and not args.mute
    reverse_audio = keep_audio and not args.no_audio_reverse

    if reverse_audio:
        filter_complex = f"[0:v]reverse[vout];[0:a]areverse[aout]"
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-filter_complex", filter_complex,
            "-map", "[vout]",
            "-map", "[aout]",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "medium",
            "-crf", "20",
            "-c:a", "aac",
            "-b:a", "192k",
            output_path,
        ]
    elif keep_audio:
        # 保留正向音频
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-vf", "reverse",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "medium",
            "-crf", "20",
            "-c:a", "aac",
            "-b:a", "192k",
            output_path,
        ]
    else:
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-vf", "reverse",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "medium",
            "-crf", "20",
            "-an",
            output_path,
        ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {duration:.2f}s 视频已倒放")


if __name__ == "__main__":
    main()
