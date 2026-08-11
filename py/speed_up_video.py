#!/usr/bin/env python3
"""
视频快进工具 —— 加速视频播放并生成新视频。

用法：
    # 对当前目录下的视频默认 2 倍速快进
    python3 speed_up_video.py

    # 指定视频文件和倍速
    python3 speed_up_video.py -i input.mp4 -s 3

    # 指定输出文件名
    python3 speed_up_video.py -i input.mp4 -s 1.5 -o fast.mp4

参数：
    -i / --input    输入视频路径（默认自动扫描当前目录第一个视频）
    -s / --speed    快进倍速（默认 2.0，支持 0.5~100）
    -o / --output   输出文件名（默认 output_speed.mp4）
    --no-audio      丢弃音频（不加速音频）

原理：
    视频：用 ffmpeg setpts 滤镜调整 PTS（setpts=PTS/speed）
    音频：用 ffmpeg atempo 滤镜加速（atempo 单次范围 0.5~2.0，
          超过 2.0 时自动链式拼接多个 atempo）
"""

import argparse
import os
import subprocess
import sys

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")


def scan_first_video(directory: str) -> str | None:
    """扫描目录下第一个视频文件（排除 output 开头的文件）"""
    for f in sorted(os.listdir(directory)):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") \
                and not f.lower().startswith("output"):
            return os.path.join(directory, f)
    return None


def get_duration(path: str) -> float:
    """获取视频时长（秒）"""
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def has_audio(path: str) -> bool:
    """检测视频是否包含音频流"""
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
    atempo 单次倍率范围为 [0.5, 2.0]，超出范围需要链式拼接。
    例如 4 倍速 → atempo=2.0,atempo=2.0
    """
    if speed <= 2.0:
        return f"atempo={speed}"
    parts = []
    remaining = speed
    while remaining > 2.0:
        parts.append("atempo=2.0")
        remaining /= 2.0
    parts.append(f"atempo={remaining}")
    return ",".join(parts)


def main():
    here = os.path.dirname(os.path.abspath(__file__))

    ap = argparse.ArgumentParser(description="视频快进工具：加速视频播放并生成新视频")
    ap.add_argument("-i", "--input", default=None, help="输入视频路径（默认自动扫描当前目录第一个视频）")
    ap.add_argument("-s", "--speed", type=float, default=2.0, help="快进倍速（默认 2.0）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_speed.mp4）")
    ap.add_argument("--no-audio", action="store_true", help="丢弃音频（不加速音频）")
    args = ap.parse_args()

    # ── 确定输入文件 ──
    if args.input:
        input_path = os.path.abspath(args.input)
    else:
        input_path = scan_first_video(here)
    if not input_path or not os.path.isfile(input_path):
        print("❌ 找不到视频文件", file=sys.stderr)
        print(f"   已搜索目录: {here}", file=sys.stderr)
        print(f"   支持扩展名: {', '.join(VIDEO_EXTS)}", file=sys.stderr)
        sys.exit(1)

    speed = args.speed
    if speed <= 0:
        print("❌ 倍速必须大于 0", file=sys.stderr)
        sys.exit(1)

    # ── 获取视频信息 ──
    duration = get_duration(input_path)
    audio_present = has_audio(input_path)
    new_duration = duration / speed

    print(f"输入视频: {os.path.basename(input_path)}")
    print(f"  原始时长: {duration:.2f}s")
    print(f"  倍速:     {speed}x")
    print(f"  新时长:   {new_duration:.2f}s")
    print(f"  有音频:   {'是' if audio_present else '否'}")

    # ── 构建 ffmpeg 命令 ──
    output_path = args.output or os.path.join(here, "output_speed.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 视频滤镜：setpts=PTS/speed
    video_filter = f"[0:v]setpts=PTS/{speed},scale=iw:ih[vout]"

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
        filter_complex = video_filter
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-filter_complex", filter_complex,
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
    print(f"   原始时长 {duration:.2f}s → 新时长 {new_duration:.2f}s ({speed}x 快进)")


if __name__ == "__main__":
    main()
