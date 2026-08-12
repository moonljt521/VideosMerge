#!/usr/bin/env python3
"""
画面定格工具 —— 在指定时间点冻结画面，持续 N 秒后继续播放。

用法：
    # 在第 3 秒定格画面 2 秒
    python3 freeze_frame.py -i input.mp4 --at 3 --duration 2

    # 在 5 秒处定格 3 秒
    python3 freeze_frame.py -i input.mp4 --at 5 --duration 3

参数：
    -i / --input    输入视频路径
    --at            定格时间点（秒）
    --duration      定格持续时长（秒，默认 2）
    -o / --output   输出文件名（默认 output_freeze.mp4）

原理：
    1. 在指定时间点用 tpad 滤镜插入冻结帧
    2. tpad=stop_mode=clone:stop_duration=D
    3. 实际用 trim+loop+concat 方式实现（兼容性好）
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
    ap = argparse.ArgumentParser(description="画面定格工具：在指定时间点冻结画面")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--at", type=float, required=True, help="定格时间点（秒）")
    ap.add_argument("--duration", type=float, default=2.0, help="定格持续时长（秒，默认 2）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_freeze.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    duration = get_duration(input_path)
    audio_present = has_audio(input_path)
    freeze_at = args.at
    freeze_dur = args.duration

    if freeze_at < 0 or freeze_at >= duration:
        print(f"❌ 定格时间 {freeze_at}s 超出视频范围 [0, {duration:.2f}]", file=sys.stderr)
        sys.exit(1)

    new_duration = duration + freeze_dur

    print(f"输入: {os.path.basename(input_path)}  {duration:.2f}s")
    print(f"  定格: {freeze_at}s 处，持续 {freeze_dur}s")
    print(f"  新时长: {new_duration:.2f}s")

    output_path = args.output or os.path.join(here, "output_freeze.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 用 tpad 滤镜实现画面定格
    # tpad=stop_from=AT:stop_duration=DUR:stop_mode=clone
    # stop_from: 从何时开始冻结
    # stop_duration: 冻结多少秒
    # stop_mode=clone: 复制最后一帧
    vf = f"tpad=stop_mode=clone:stop_from={freeze_at:.3f}:stop_duration={freeze_dur:.3f}"

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
    print(f"   {duration:.2f}s → {new_duration:.2f}s（定格 {freeze_dur}s）")


if __name__ == "__main__":
    main()
