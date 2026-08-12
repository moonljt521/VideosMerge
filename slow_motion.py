#!/usr/bin/env python3
"""
视频慢动作工具 —— 减速播放视频，产生慢动作效果。

与 speed_up_video.py 互补：speed_up 加速，本脚本减速。

用法：
    # 默认 0.5 倍速（慢一倍）
    python3 slow_motion.py -i input.mp4

    # 0.25 倍速（慢四倍）
    python3 slow_motion.py -i input.mp4 -s 0.25

    # 0.5 倍速，丢弃音频
    python3 slow_motion.py -i input.mp4 -s 0.5 --no-audio

参数：
    -i / --input    输入视频路径（默认自动扫描当前目录第一个视频）
    -s / --speed    减速倍率（默认 0.5，范围 0.1~1.0）
    -o / --output   输出文件名（默认 output_slow.mp4）
    --no-audio      丢弃音频

原理：
    视频：setpts=PTS/speed（speed<1 时 PTS 增大 → 帧间间隔变大 → 慢放）
    音频：atempo=speed（atempo 范围 0.5~2.0，小于 0.5 时链式拼接）
          例如 0.25x → atempo=0.5,atempo=0.5
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


def build_atempo_chain(speed: float) -> str:
    """
    构建 atempo 滤镜链。
    atempo 单次倍率范围为 [0.5, 2.0]，小于 0.5 时需要链式拆分。
    例如 0.25x → atempo=0.5,atempo=0.5
    """
    if speed >= 0.5:
        return f"atempo={speed}"
    parts = []
    remaining = speed
    while remaining < 0.5:
        parts.append("atempo=0.5")
        remaining /= 0.5
    parts.append(f"atempo={remaining}")
    return ",".join(parts)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="视频慢动作工具：减速播放视频")
    ap.add_argument("-i", "--input", default=None, help="输入视频路径")
    ap.add_argument("-s", "--speed", type=float, default=0.5,
                    help="减速倍率（默认 0.5，范围 0.1~1.0）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_slow.mp4）")
    ap.add_argument("--no-audio", action="store_true", help="丢弃音频")
    args = ap.parse_args()

    # ── 确定输入文件 ──
    if args.input:
        input_path = os.path.abspath(args.input)
    else:
        input_path = scan_first_video(here)
    if not input_path or not os.path.isfile(input_path):
        print("❌ 找不到视频文件", file=sys.stderr)
        sys.exit(1)

    speed = args.speed
    if not (0.01 < speed < 1.0):
        print("❌ 倍率范围应为 0.01~1.0（减速）", file=sys.stderr)
        print("   如需加速请使用 speed_up_video.py", file=sys.stderr)
        sys.exit(1)

    duration = get_duration(input_path)
    audio_present = has_audio(input_path)
    new_duration = duration / speed

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  原始时长: {duration:.2f}s")
    print(f"  倍率:     {speed}x")
    print(f"  新时长:   {new_duration:.2f}s")
    print(f"  有音频:   {'是' if audio_present else '否'}")

    # ── 构建命令 ──
    output_path = args.output or os.path.join(here, "output_slow.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 视频：setpts=PTS/speed（speed<1 → PTS 变大 → 慢放）
    video_filter = f"[0:v]setpts=PTS/{speed}[vout]"

    keep_audio = audio_present and not args.no_audio

    if keep_audio:
        atempo_chain = build_atempo_chain(speed)
        audio_filter = f"[0:a]{atempo_chain}[aout]"
        filter_complex = f"{video_filter};{audio_filter}"
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
    else:
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-filter_complex", video_filter,
            "-map", "[vout]",
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
    print(f"   {duration:.2f}s → {new_duration:.2f}s ({speed}x 慢放)")


if __name__ == "__main__":
    main()
