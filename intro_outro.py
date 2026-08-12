#!/usr/bin/env python3
"""
片头片尾工具 —— 在视频开头和/或结尾添加片头、片尾画面。

支持图片片头（自动转视频）、视频片头、纯色过渡片头。

用法：
    # 添加图片片头和片尾（各 3 秒）
    python3 intro_outro.py -i main.mp4 --intro intro.png --outro outro.png --duration 3

    # 只加片头
    python3 intro_outro.py -i main.mp4 --intro intro.png --duration 2

    # 只加片尾
    python3 intro_outro.py -i main.mp4 --outro outro.png --duration 3

    # 添加纯黑过渡片头（1 秒）
    python3 intro_outro.py -i main.mp4 --solid black --duration 1

参数：
    -i / --input      主视频路径
    --intro            片头图片/视频路径（开头插入）
    --outro            片尾图片/视频路径（结尾插入）
    --solid            纯色过渡片头（black/white），与 --intro 二选一
    --duration         片头/片尾持续时长（秒，默认 3）
    --transition       片头与主视频间的淡入过渡时长（秒，默认 0.5）
    -o / --output      输出文件名（默认 output_intro_outro.mp4）

原理：
    图片→视频：loop -frames + scale + color
    concat：片头 + 主视频 + 片尾 首尾拼接
    淡入：xfade transition=fade
"""

import argparse
import os
import subprocess
import sys


def get_info(path: str):
    """获取视频宽高和时长"""
    r = subprocess.run(
        ["ffprobe", "-v", "error",
         "-show_entries", "format=duration:stream=width,height,codec_type",
         "-of", "json", path],
        capture_output=True, text=True, check=True,
    )
    import json
    info = json.loads(r.stdout)
    vw = vh = 0
    has_audio = False
    for s in info.get("streams", []):
        if s.get("codec_type") == "video" and vw == 0:
            vw, vh = int(s["width"]), int(s["height"])
        elif s.get("codec_type") == "audio":
            has_audio = True
    dur = float(info.get("format", {}).get("duration", 0))
    return vw, vh, dur, has_audio


def is_image(path: str) -> bool:
    ext = os.path.splitext(path)[1].lower()
    return ext in (".png", ".jpg", ".jpeg", ".bmp", ".webp")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="片头片尾工具：添加片头/片尾画面")
    ap.add_argument("-i", "--input", required=True, help="主视频路径")
    ap.add_argument("--intro", default=None, help="片头图片/视频路径")
    ap.add_argument("--outro", default=None, help="片尾图片/视频路径")
    ap.add_argument("--solid", default=None, choices=["black", "white"],
                    help="纯色片头（与 --intro 二选一）")
    ap.add_argument("--duration", type=float, default=3.0, help="片头/片尾时长（默认 3 秒）")
    ap.add_argument("--transition", type=float, default=0.5,
                    help="淡入过渡时长（秒，默认 0.5，0=无过渡）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_intro_outro.mp4）")
    args = ap.parse_args()

    if not args.intro and not args.outro and not args.solid:
        print("❌ 请指定 --intro / --outro / --solid", file=sys.stderr)
        sys.exit(1)

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 主视频不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh, main_dur, audio_present = get_info(input_path)
    vw -= vw % 2
    vh -= vh % 2

    intro_dur = args.duration
    outro_dur = args.duration
    fade_dur = args.transition

    print(f"主视频: {os.path.basename(input_path)}  {vw}x{vh}  {main_dur:.2f}s")
    parts = []

    # ── 构建 filter_complex ──
    # 策略：各段统一缩放到主视频尺寸 → concat → 可选 xfade
    filter_parts = []
    input_idx = 0  # ffmpeg -i 输入索引
    labels = []

    # 片头
    if args.solid:
        color = "black" if args.solid == "black" else "white"
        filter_parts.append(
            f"color=c={color}:s={vw}x{vh}:d={intro_dur}:r=30[v_intro]"
        )
        labels.append("[v_intro]")
        print(f"  片头: 纯 {args.solid} {intro_dur}s")
    elif args.intro:
        intro_path = os.path.abspath(args.intro)
        if not os.path.isfile(intro_path):
            print(f"❌ 片头文件不存在: {intro_path}", file=sys.stderr)
            sys.exit(1)
        if is_image(intro_path):
            filter_parts.append(
                f"[{input_idx}:v]loop=loop={int(intro_dur*30)}:size=1,"
                f"scale={vw}:{vh}:force_original_aspect_ratio=decrease,"
                f"pad={vw}:{vh}:(ow-iw)/2:(oh-ih)/2:{'black' if args.solid != 'white' else 'white'},"
                f"fps=30,setsar=1[v_intro]"
            )
        else:
            ivw, ivh, _, _ = get_info(intro_path)
            filter_parts.append(
                f"[{input_idx}:v]scale={vw}:{vh}:force_original_aspect_ratio=decrease,"
                f"pad={vw}:{vh}:(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1[v_intro]"
            )
        labels.append("[v_intro]")
        print(f"  片头: {os.path.basename(intro_path)} {intro_dur}s")
        input_idx += 1

    # 主视频
    filter_parts.append(
        f"[{input_idx}:v]scale={vw}:{vh}:force_original_aspect_ratio=decrease,"
        f"pad={vw}:{vh}:(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1[v_main]"
    )
    labels.append("[v_main]")
    input_idx += 1

    # 片尾
    if args.outro:
        outro_path = os.path.abspath(args.outro)
        if not os.path.isfile(outro_path):
            print(f"❌ 片尾文件不存在: {outro_path}", file=sys.stderr)
            sys.exit(1)
        if is_image(outro_path):
            filter_parts.append(
                f"[{input_idx}:v]loop=loop={int(outro_dur*30)}:size=1,"
                f"scale={vw}:{vh}:force_original_aspect_ratio=decrease,"
                f"pad={vw}:{vh}:(ow-iw)/2:(oh-ih)/2:black,"
                f"fps=30,setsar=1[v_outro]"
            )
        else:
            filter_parts.append(
                f"[{input_idx}:v]scale={vw}:{vh}:force_original_aspect_ratio=decrease,"
                f"pad={vw}:{vh}:(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1[v_outro]"
            )
        labels.append("[v_outro]")
        print(f"  片尾: {os.path.basename(outro_path)} {outro_dur}s")
        input_idx += 1

    # concat 拼接
    concat_labels = "".join(labels)
    n_parts = len(labels)
    filter_parts.append(f"{concat_labels}concat=n={n_parts}:v=1:a=0[vout]")

    filter_complex = ";".join(filter_parts)

    # ── 构建 ffmpeg 命令 ──
    output_path = args.output or os.path.join(here, "output_intro_outro.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    cmd = ["ffmpeg", "-y"]
    # 片头输入
    if args.intro and not args.solid:
        cmd.extend(["-i", os.path.abspath(args.intro)])
    elif args.solid:
        pass  # color 滤镜不需要输入
    # 主视频输入
    cmd.extend(["-i", input_path])
    # 片尾输入
    if args.outro:
        cmd.extend(["-i", os.path.abspath(args.outro)])

    cmd.extend([
        "-filter_complex", filter_complex,
        "-map", "[vout]",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "20",
    ])
    if audio_present:
        # 取主视频音频（与主视频对齐）
        main_input_idx = 0 if not args.intro or args.solid else 1
        cmd.extend(["-map", f"{main_input_idx}:a", "-c:a", "aac", "-b:a", "192k"])
    else:
        cmd.append("-an")
    cmd.append(output_path)

    total_dur = main_dur
    if args.intro or args.solid:
        total_dur += intro_dur
    if args.outro:
        total_dur += outro_dur

    print(f"  总时长: {total_dur:.2f}s")
    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {main_dur:.2f}s → {total_dur:.2f}s")


if __name__ == "__main__":
    main()
