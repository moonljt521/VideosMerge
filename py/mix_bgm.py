#!/usr/bin/env python3
"""
背景音乐混合工具 —— 在视频原音轨上叠加背景音乐（BGM）。

支持调节 BGM 音量、循环/截断、原音轨音量控制。

用法：
    # 用 music.mp3 作为 BGM，默认 BGM 音量 30%
    python3 mix_bgm.py -i input.mp4 -b music.mp3

    # BGM 音量 50%，原音轨音量 80%
    python3 mix_bgm.py -i input.mp4 -b music.mp3 --bgm-vol 0.5 --orig-vol 0.8

    # BGM 从 0.5s 淡入，末尾 2s 淡出
    python3 mix_bgm.py -i input.mp4 -b music.mp3 --fade-in 0.5 --fade-out 2

    # 原视频无音轨，纯加 BGM
    python3 mix_bgm.py -i input.mp4 -b music.mp3 --no-orig-audio

参数：
    -i / --input        输入视频路径
    -b / --bgm          背景音乐文件路径
    --bgm-vol           BGM 音量（0.0~1.0，默认 0.3）
    --orig-vol          原音轨音量（0.0~1.0，默认 0.8）
    --fade-in           BGM 淡入时长（秒，默认 0）
    --fade-out          BGM 淡出时长（秒，默认 0）
    --no-orig-audio     丢弃原音轨（只保留 BGM）
    -o / --output       输出文件名（默认 output_mix.mp4）

原理：
    amix 滤镜混合两路音频
    BGM 音量：volume=bgm_vol
    原音轨音量：volume=orig_vol
    淡入淡出：afade=t=in:d=fade_in, afade=t=out:st=fade_out_start:d=fade_out
    BGM 循环：stream_loop=-1（无限循环直到视频结束）
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
    ap = argparse.ArgumentParser(description="背景音乐混合工具：在视频上叠加 BGM")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-b", "--bgm", required=True, help="背景音乐文件路径")
    ap.add_argument("--bgm-vol", type=float, default=0.3, help="BGM 音量（默认 0.3）")
    ap.add_argument("--orig-vol", type=float, default=0.8, help="原音轨音量（默认 0.8）")
    ap.add_argument("--fade-in", type=float, default=0.0, help="BGM 淡入时长（秒）")
    ap.add_argument("--fade-out", type=float, default=0.0, help="BGM 淡出时长（秒）")
    ap.add_argument("--no-orig-audio", action="store_true", help="丢弃原音轨，只保留 BGM")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_mix.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    bgm_path = os.path.abspath(args.bgm)

    if not os.path.isfile(input_path):
        print(f"❌ 视频文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(bgm_path):
        print(f"❌ BGM 文件不存在: {bgm_path}", file=sys.stderr)
        sys.exit(1)

    video_dur = get_duration(input_path)
    orig_audio = has_audio(input_path) and not args.no_orig_audio
    bgm_dur = get_duration(bgm_path)

    print(f"视频: {os.path.basename(input_path)}  {video_dur:.2f}s")
    print(f"BGM:  {os.path.basename(bgm_path)}  {bgm_dur:.2f}s")
    print(f"  BGM 音量: {args.bgm_vol}  原音轨音量: {args.orig_vol}  原音轨: {'保留' if orig_audio else '丢弃'}")
    print(f"  淡入: {args.fade_in}s  淡出: {args.fade_out}s")

    output_path = args.output or os.path.join(here, "output_mix.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建 BGM 滤镜链 ──
    # BGM: 无限循环 → 截断到视频时长 → 调音量 → 淡入淡出
    bgm_filters = []
    bgm_filters.append(f"volume={args.bgm_vol}")
    if args.fade_in > 0:
        bgm_filters.append(f"afade=t=in:d={args.fade_in}")
    if args.fade_out > 0:
        fade_out_start = max(0, video_dur - args.fade_out)
        bgm_filters.append(f"afade=t=out:st={fade_out_start:.3f}:d={args.fade_out}")

    bgm_chain = ",".join(bgm_filters)

    if orig_audio:
        # 混合原音轨 + BGM（-stream_loop -1 已处理循环）
        filter_complex = (
            f"[1:a]atrim=end={video_dur:.3f},asetpts=PTS-STARTPTS,"
            f"aresample=44100,{bgm_chain}[bgm];"
            f"[0:a]volume={args.orig_vol},aresample=44100[orig];"
            f"[orig][bgm]amix=inputs=2:duration=first:normalize=0[aout]"
        )
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-stream_loop", "-1", "-i", bgm_path,
            "-filter_complex", filter_complex,
            "-map", "0:v",
            "-map", "[aout]",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "192k",
            "-t", f"{video_dur:.3f}",
            output_path,
        ]
    else:
        # 只用 BGM（-stream_loop -1 已处理循环）
        filter_complex = (
            f"[1:a]atrim=end={video_dur:.3f},asetpts=PTS-STARTPTS,"
            f"aresample=44100,{bgm_chain}[aout]"
        )
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-stream_loop", "-1", "-i", bgm_path,
            "-filter_complex", filter_complex,
            "-map", "0:v",
            "-map", "[aout]",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "192k",
            "-t", f"{video_dur:.3f}",
            output_path,
        ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   BGM 已混合（{'原音轨+BGM' if orig_audio else '仅BGM'}）")


if __name__ == "__main__":
    main()
