#!/usr/bin/env python3
"""
转场效果工具 —— 在多段视频之间添加转场动画，平滑衔接。

支持淡入淡出、滑动、擦除、圆形等多种转场效果。

用法：
    # 两个视频之间用淡入淡出转场（默认 1s）
    python3 transition.py -i a.mp4 b.mp4

    # 指定转场类型和时长
    python3 transition.py -i a.mp4 b.mp4 c.mp4 --effect wipeleft --duration 0.8

    # 查看所有支持的转场效果
    python3 transition.py --list-effects

    # 指定输出
    python3 transition.py -i a.mp4 b.mp4 -o merged.mp4

参数：
    -i / --input        多个视频路径（至少 2 个）
    --effect            转场类型（默认 fade，见 --list-effects）
    --duration          转场时长（秒，默认 1.0）
    -o / --output       输出文件名（默认 output_transition.mp4）
    --list-effects      列出所有支持的转场效果

原理：
    xfade 滤镜：在两个视频的重叠区域做交叉过渡
    语法：xfade=transition=EFFECT:duration=D:offset=O
    offset = 第一个视频时长 - 转场时长
    多段视频需要链式 xfade
"""

import argparse
import os
import subprocess
import sys

# xfade 支持的转场效果
XFADE_EFFECTS = [
    "fade", "wipeleft", "wiperight", "wipeup", "wipedown",
    "slideleft", "slideright", "slideup", "slidedown",
    "circlecrop", "circleopen", "circleclose",
    "radial", "dissolve", "pixelize",
    "diagtl", "diagtr", "diagbl", "diagbr",
    "hlslice", "hrslice", "vuslice", "vdslice",
    "hblur", "fadegrays", "fadewhite", "fadeblack",
    "smoothleft", "smoothright", "smoothup", "smoothdown",
    "smush", "squeeze", "zoomin",
]

# 常用效果的中文名称
EFFECT_NAMES = {
    "fade": "淡入淡出",
    "wipeleft": "向左擦除",
    "wiperight": "向右擦除",
    "wipeup": "向上擦除",
    "wipedown": "向下擦除",
    "slideleft": "向左滑动",
    "slideright": "向右滑动",
    "slideup": "向上滑动",
    "slidedown": "向下滑动",
    "circleopen": "圆形展开",
    "circleclose": "圆形关闭",
    "dissolve": "溶解",
    "pixelize": "像素化",
    "fadewhite": "淡入白色",
    "fadeblack": "淡入黑色",
    "zoomin": "放大",
    "hblur": "水平模糊",
}


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def get_dimensions(path: str):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


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
    ap = argparse.ArgumentParser(description="转场效果工具：多视频间添加转场动画")
    ap.add_argument("-i", "--input", nargs="+", default=None, help="多个视频路径（至少 2 个）")
    ap.add_argument("--effect", default="fade", help="转场类型（默认 fade）")
    ap.add_argument("--duration", type=float, default=1.0, help="转场时长（秒，默认 1.0）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_transition.mp4）")
    ap.add_argument("--list-effects", action="store_true", help="列出所有支持的转场效果")
    args = ap.parse_args()

    # ── 列出效果 ──
    if args.list_effects:
        print("支持的转场效果：")
        for e in XFADE_EFFECTS:
            cn = EFFECT_NAMES.get(e, "")
            print(f"  {e:20s} {cn}")
        return

    # ── 参数检查 ──
    if not args.input or len(args.input) < 2:
        print("❌ 至少需要 2 个视频", file=sys.stderr)
        sys.exit(1)

    effect = args.effect
    if effect not in XFADE_EFFECTS:
        print(f"❌ 不支持的转场效果: {effect}", file=sys.stderr)
        print(f"   使用 --list-effects 查看支持的转场", file=sys.stderr)
        sys.exit(1)

    transition_dur = args.duration
    if transition_dur <= 0:
        print("❌ 转场时长必须大于 0", file=sys.stderr)
        sys.exit(1)

    videos = [os.path.abspath(v) for v in args.input]
    for v in videos:
        if not os.path.isfile(v):
            print(f"❌ 文件不存在: {v}", file=sys.stderr)
            sys.exit(1)

    # ── 获取元数据 ──
    n = len(videos)
    durations = [get_duration(v) for v in videos]
    dims = [get_dimensions(v) for v in videos]
    any_audio = any(has_audio(v) for v in videos)

    # 统一到第一个视频的分辨率
    tw, th = dims[0]
    tw -= tw % 2
    th -= th % 2

    # 检查转场时长是否合理
    for i, d in enumerate(durations):
        if d < transition_dur:
            print(f"⚠️  视频 {i} 时长 {d:.2f}s 小于转场时长 {transition_dur}s，可能效果异常", file=sys.stderr)

    print("转场视频:")
    for i, (v, d) in enumerate(zip(videos, durations)):
        w, h = dims[i]
        print(f"  [{i}] {os.path.basename(v)}  {w}x{h}  {d:.2f}s")
    print(f"转场: {effect} ({EFFECT_NAMES.get(effect, '')})  时长: {transition_dur}s  目标: {tw}x{th}")

    # ── 构建滤镜 ──
    # 1. 每个视频先统一缩放到目标尺寸
    # 2. 链式 xfade 转场
    # offset[i] = 前面所有视频的累计时长 - 转场时长 * (已完成的转场数)

    # 统一缩放
    scaled_parts = []
    for i in range(n):
        scaled_parts.append(
            f"[{i}:v]scale={tw}:{th}:force_original_aspect_ratio=decrease,"
            f"pad={tw}:{th}:(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1,format=yuv420p[v{i}]"
        )

    # 链式 xfade
    xfade_parts = []
    # 第一个 xfade: v0 + v1
    offset = durations[0] - transition_dur
    xfade_parts.append(
        f"[v0][v1]xfade=transition={effect}:duration={transition_dur}:offset={offset:.3f}[x0]"
    )

    # 后续 xfade: x[i-1] + v[i+1]
    cumulative = durations[0]  # 已播放的总时长
    for i in range(1, n - 1):
        cumulative += durations[i] - transition_dur  # 减去转场重叠
        offset = cumulative - transition_dur
        prev_label = f"[x{i - 1}]"
        cur_label = f"[v{i + 1}]"
        out_label = f"[x{i}]"
        xfade_parts.append(
            f"{prev_label}{cur_label}xfade=transition={effect}:duration={transition_dur}:offset={offset:.3f}{out_label}"
        )

    # 最终输出标签
    final_v_label = f"x{n - 2}"

    all_parts = scaled_parts + xfade_parts
    filter_complex = ";".join(all_parts)

    # 计算最终总时长
    total_dur = sum(durations) - transition_dur * (n - 1)

    # ── 构建命令 ──
    output_path = args.output or os.path.join(here, "output_transition.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    cmd = [
        "ffmpeg", "-y",
    ]
    for v in videos:
        cmd.extend(["-i", v])
    cmd.extend([
        "-filter_complex", filter_complex,
        "-map", f"[{final_v_label}]",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "20",
    ])

    # 音频：简单取第一个视频的音频（截断到总时长）
    if any_audio:
        cmd.extend(["-map", "0:a", "-c:a", "aac", "-b:a", "192k"])
        cmd.extend(["-t", f"{total_dur:.3f}"])
    else:
        cmd.append("-an")

    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {n} 个视频  {effect} 转场  总时长 {total_dur:.2f}s")


if __name__ == "__main__":
    main()
