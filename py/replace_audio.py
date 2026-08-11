#!/usr/bin/env python3
"""
音频替换工具 —— 用外部音频文件替换视频的原音轨。

用法：
    # 用 music.mp3 替换视频原音轨
    python3 replace_audio.py -i input.mp4 -a music.mp3

    # 指定输出文件名
    python3 replace_audio.py -i input.mp4 -a voice.m4a -o output.mp4

    # 替换并指定音频起始位置（从音频 5s 处开始取）
    python3 replace_audio.py -i input.mp4 -a music.mp3 --audio-start 5

参数：
    -i / --input        输入视频路径
    -a / --audio        替换音频文件路径
    -o / --output       输出文件名（默认 output_replace_audio.mp4）
    --audio-start       音频起始时间（秒，默认 0，用于截取音频片段）
    --audio-duration    音频截取时长（秒，默认与视频等长）

原理：
    -i video -i audio -map 0:v -map 1:a -c:v copy -c:a aac
    视频流直接复制不重编码（快），音频重新编码为 AAC
"""

import argparse
import os
import subprocess
import sys

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")
AUDIO_EXTS = (".mp3", ".aac", ".m4a", ".wav", ".ogg", ".flac", ".wma")


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="音频替换工具：用外部音频替换视频原音轨")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-a", "--audio", required=True, help="替换音频文件路径")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_replace_audio.mp4）")
    ap.add_argument("--audio-start", type=float, default=0.0,
                    help="音频起始时间（秒，默认 0）")
    ap.add_argument("--audio-duration", type=float, default=None,
                    help="音频截取时长（秒，默认与视频等长）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    audio_path = os.path.abspath(args.audio)

    if not os.path.isfile(input_path):
        print(f"❌ 视频文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(audio_path):
        print(f"❌ 音频文件不存在: {audio_path}", file=sys.stderr)
        sys.exit(1)

    video_dur = get_duration(input_path)
    audio_dur = get_duration(audio_path)
    audio_start = args.audio_start
    audio_duration = args.audio_duration or video_dur

    print(f"视频: {os.path.basename(input_path)}  时长 {video_dur:.2f}s")
    print(f"音频: {os.path.basename(audio_path)}  时长 {audio_dur:.2f}s")
    print(f"  音频起始: {audio_start}s  截取: {audio_duration:.2f}s")

    if audio_start + audio_duration > audio_dur:
        print(f"  ⚠️  音频不够长，末尾将静音")

    output_path = args.output or os.path.join(here, "output_replace_audio.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建命令 ──
    # 视频流直接复制，音频从指定位置截取并编码为 AAC
    # -ss 在 -i 之前是快速 seek（keyframe 对齐），在 -i 之后是精确 seek
    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-ss", f"{audio_start:.3f}",
        "-i", audio_path,
        "-t", f"{audio_duration:.3f}",  # 限制总时长
        "-map", "0:v",
        "-map", "1:a",
        "-c:v", "copy",  # 视频不重编码
        "-c:a", "aac",
        "-b:a", "192k",
        "-shortest",
        output_path,
    ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   视频原音轨已替换为 {os.path.basename(audio_path)}")


if __name__ == "__main__":
    main()
