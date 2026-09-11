#!/usr/bin/env python3
"""
视频时间段裁剪工具 —— 截取视频中指定起止时间的片段。

支持快速 seek（keyframe 对齐，速度极快）和精确 seek（帧级精确）。

用法：
    # 截取 2s~8s 的片段
    python3 trim_video.py -i input.mp4 --start 2 --end 8

    # 从 2s 开始截取 5 秒
    python3 trim_video.py -i input.mp4 --start 2 --duration 5

    # 精确 seek（帧级，较慢但准确）
    python3 trim_video.py -i input.mp4 --start 2.5 --duration 3 --accurate

    # 去掉开头 1s 和结尾 1s
    python3 trim_video.py -i input.mp4 --start 1 --end 10.8

参数：
    -i / --input      输入视频路径
    --start           起始时间（秒，默认 0）
    --end             结束时间（秒，与 --duration 二选一）
    --duration        截取时长（秒，与 --end 二选一）
    --accurate        使用精确 seek（帧级，默认快速 seek）
    -o / --output     输出文件名（默认 output_trim.mp4）
    --no-audio        丢弃音频

原理：
    快速 seek：-ss START -i input -t DURATION -c copy（秒级完成，不重编码）
    精确 seek：-i input -ss START -t DURATION（帧级精确，需要重编码）
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
    ap = argparse.ArgumentParser(description="视频时间段裁剪工具：截取指定片段")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--start", type=float, default=0.0, help="起始时间（秒，默认 0）")
    ap.add_argument("--end", type=float, default=None, help="结束时间（秒）")
    ap.add_argument("--duration", type=float, default=None, help="截取时长（秒，与 --end 二选一）")
    ap.add_argument("--accurate", action="store_true", help="精确 seek（帧级，默认快速 seek）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_trim.mp4）")
    ap.add_argument("--no-audio", action="store_true", help="丢弃音频")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    total_dur = get_duration(input_path)
    audio_present = has_audio(input_path)

    # ── 计算截取范围 ──
    start = max(0, args.start)
    if args.end is not None:
        end = min(args.end, total_dur)
        duration = end - start
    elif args.duration is not None:
        duration = args.duration
        end = start + duration
    else:
        print("❌ 请指定 --end 或 --duration", file=sys.stderr)
        sys.exit(1)

    if duration <= 0:
        print(f"❌ 截取时长必须大于 0（当前 {duration:.3f}s）", file=sys.stderr)
        sys.exit(1)
    if start >= total_dur:
        print(f"❌ 起始时间 {start}s 超过视频时长 {total_dur:.2f}s", file=sys.stderr)
        sys.exit(1)

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  总时长: {total_dur:.2f}s")
    print(f"  截取: {start:.2f}s ~ {end:.2f}s ({duration:.2f}s)")
    print(f"  模式: {'精确 seek' if args.accurate else '快速 seek'}  音频: {'有' if audio_present else '无'}")

    output_path = args.output or os.path.join(here, "output_trim.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建 ffmpeg 命令 ──
    keep_audio = audio_present and not args.no_audio

    if args.accurate:
        # 精确 seek：-i 在前，-ss 在后，需重编码
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-ss", f"{start:.3f}",
            "-t", f"{duration:.3f}",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "medium",
            "-crf", "20",
        ]
        if keep_audio:
            cmd.extend(["-c:a", "aac", "-b:a", "192k"])
        else:
            cmd.append("-an")
        cmd.append(output_path)
    else:
        # 快速 seek：-ss 在 -i 前，-c copy 不重编码
        cmd = [
            "ffmpeg", "-y",
            "-ss", f"{start:.3f}",
            "-i", input_path,
            "-t", f"{duration:.3f}",
            "-c:v", "copy",
        ]
        if keep_audio:
            cmd.extend(["-c:a", "copy"])
        else:
            cmd.append("-an")
        cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {start:.2f}s ~ {end:.2f}s ({duration:.2f}s) {'[精确]' if args.accurate else '[快速]'}")


if __name__ == "__main__":
    main()
