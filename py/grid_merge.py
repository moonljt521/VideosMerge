#!/usr/bin/env python3
"""
自动合并同目录下所有视频为网格拼贴视频。
把任意数量的视频文件和本脚本放在同一目录，运行：
    python3 grid_merge.py
即可生成 output.mp4。
"""
import argparse
import glob
import math
import os
import subprocess
import sys

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")


def scan_videos(directory: str):
    """扫描目录下所有视频文件（按文件名排序），排除输出文件"""
    files = []
    for f in os.listdir(directory):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") and not f.lower().startswith("output"):
            files.append(os.path.join(directory, f))
    return sorted(files)


def calc_grid(n: int):
    cols = math.ceil(math.sqrt(n))
    rows = math.ceil(n / cols)
    return rows, cols


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def get_dimensions(path: str):
    """返回 (width, height)"""
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def dominant_aspect(dims):
    """从各视频尺寸里取出现次数最多的宽高比（含浮点容差）"""
    from collections import Counter
    ratios = [round(w / h, 3) for w, h in dims]
    return Counter(ratios).most_common(1)[0][0]


def cell_size_from_aspect(aspect: float, target: int):
    """target 为单元格较长边的像素；返回 cell_w, cell_h"""
    if aspect >= 1:
        return target, round(target / aspect)
    return round(target * aspect), target


def has_audio(path: str) -> bool:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a",
         "-show_entries", "stream=codec_type",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True,
    )
    return "audio" in r.stdout.strip()


def detect_logo_cut(path: str, noise=0.01, min_dur=0.5, end_margin=0.05):
    """检测视频末尾静止 logo 片段的起点，返回截断时间戳；检测不到返回 None"""
    r = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", path,
         "-filter:v", f"freezedetect=n={noise}:d={min_dur}", "-an", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    import re
    starts, ends = [], []
    for line in r.stderr.splitlines():
        ms = re.search(r"freeze_start:\s*([\d.]+)", line)
        me = re.search(r"freeze_end:\s*([\d.]+)", line)
        if ms: starts.append(float(ms.group(1)))
        if me: ends.append(float(me.group(1)))
    if not starts:
        return None
    # 取"持续到结尾"的那一段：freeze_start 之后没有对应的 freeze_end
    # 即倒数第一个 freeze_start 落在最后一个 freeze_end 之后（或没有 end）
    last_end = ends[-1] if ends else -1.0
    tail = [s for s in starts if s > last_end]
    if not tail:
        # 兜底：取最后一次 freeze_start（可能就是末段 logo）
        tail = starts[-1:]
    cut = tail[0] - end_margin
    return cut if cut > 0 else None


def build_filter(n, cell_w, cell_h, rows, cols, durations, max_dur, audio_mask, cut_times):
    scaled = []
    for i in range(n):
        pad_dur = max(0.0, max_dur - durations[i])
        common = (
            f"scale={cell_w}:{cell_h}:force_original_aspect_ratio=decrease,"
            f"pad={cell_w}:{cell_h}:(ow-iw)/2:(oh-ih)/2:black,setsar=1[v{i}]"
        )
        trim_pfx = f"trim=end={cut_times[i]:.3f},setpts=PTS-STARTPTS," if cut_times[i] else ""
        if pad_dur <= 0.001:
            scaled.append(f"[{i}:v]{trim_pfx}{common}")
        else:
            # 先按 logo 截断 → 再取首帧无限循环拼到末尾 → 再截到总时长
            scaled.append(
                f"[{i}:v]{trim_pfx}split=2[{i}A][{i}B];"
                f"[{i}A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[{i}Fof];"
                f"[{i}B]setpts=PTS-STARTPTS[{i}M];"
                f"[{i}M][{i}Fof]concat=n=2:v=1:a=0[{i}C];"
                f"[{i}C]trim=end={max_dur:.3f},setpts=PTS-STARTPTS,{common}"
            )
    layouts = []
    for idx in range(n):
        r = idx // cols
        c = idx % cols
        layouts.append(f"{c * cell_w}_{r * cell_h}")
    inputs_concat = "".join(f"[v{i}]" for i in range(n))
    layout_str = "|".join(layouts)
    parts = [";".join(scaled),
             f"{inputs_concat}xstack=inputs={n}:layout={layout_str}[vout]"]

    audio_parts = []
    for i in range(n):
        if not audio_mask[i]:
            continue
        a_trim = f"atrim=end={cut_times[i]:.3f},asetpts=PTS-STARTPTS," if cut_times[i] else ""
        pad_a = max(0.0, max_dur - durations[i])
        apad = f",apad=whole_dur={max_dur:.3f}" if pad_a > 0 else ""
        audio_parts.append(f"[{i}:a]{a_trim}aresample=44100{apad}[a{i}]")

    if audio_parts:
        amix_in = "".join(f"[a{i}]" for i in range(n) if audio_mask[i])
        parts.append(";".join(audio_parts))
        parts.append(f"{amix_in}amix=inputs={len(audio_parts)}:duration=first:normalize=0[aout]")

    return ";".join(parts), bool(audio_parts)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="自动扫描同目录视频并网格合并")
    ap.add_argument("-o", "--output", default=None, help="输出文件名")
    ap.add_argument("-s", "--cell", type=int, default=720, help="单元格较长边像素（默认 720）")
    ap.add_argument("-d", "--dir", default=None, help="视频所在目录（默认脚本所在目录）")
    ap.add_argument("--no-trim-logo", action="store_true", help="跳过尾部 logo 检测与截断")
    ap.add_argument("--noise", type=float, default=0.01, help="freezedetect 噪声阈值（默认 0.01）")
    args = ap.parse_args()
    if args.dir:
        here = os.path.abspath(args.dir)

    videos = scan_videos(here)
    if not videos:
        print("❌ 目录里没有视频文件:", here, file=sys.stderr)
        print("支持扩展名:", ", ".join(VIDEO_EXTS), file=sys.stderr)
        sys.exit(1)

    n = len(videos)
    rows, cols = calc_grid(n)

    dims = [get_dimensions(v) for v in videos]
    aspect = dominant_aspect(dims)
    cell_w, cell_h = cell_size_from_aspect(aspect, args.cell)
    # 确保偶数（libx264 要求）
    cell_w -= cell_w % 2
    cell_h -= cell_h % 2
    out_w, out_h = cell_w * cols, cell_h * rows

    print("找到视频:")
    for v, (w, h) in zip(videos, dims):
        print(f"   {os.path.basename(v)}  {w}x{h}")
    print(f"数量: {n}  布局: {rows}x{cols}  主比例: {aspect:.3f}  "
          f"单格: {cell_w}x{cell_h}  总尺寸: {out_w}x{out_h}")

    durations = [get_duration(v) for v in videos]
    audio_mask = [has_audio(v) for v in videos]

    cut_times = [None] * n
    if not args.no_trim_logo:
        print("检测尾部 logo（静止片段）：")
        for i, v in enumerate(videos):
            cut = detect_logo_cut(v, noise=args.noise)
            if cut is not None:
                cut_times[i] = cut
                print(f"   {os.path.basename(v)}  原时长 {durations[i]:.2f}s → 截断至 {cut:.2f}s（去除尾部 {(durations[i]-cut):.2f}s logo）")
            else:
                print(f"   {os.path.basename(v)}  原时长 {durations[i]:.2f}s → 未检测到尾部静止 logo")
    else:
        print("已跳过 logo 检测")

    eff_durations = [cut_times[i] if cut_times[i] else durations[i] for i in range(n)]
    max_dur = max(eff_durations)
    print(f"有效时长: {[round(d,2) for d in eff_durations]}  以最长 {max_dur:.2f}s 为准")
    print(f"音频: {['有' if a else '无' for a in audio_mask]}")

    output = args.output or os.path.join(here, "output.mp4")

    cmd = ["ffmpeg", "-y"]
    for v in videos:
        cmd.extend(["-i", v])

    durations = eff_durations
    vf, has_a = build_filter(n, cell_w, cell_h, rows, cols, durations, max_dur, audio_mask, cut_times)

    cmd.extend([
        "-filter_complex", vf,
        "-map", "[vout]",
        "-t", f"{max_dur:.3f}",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "20",
    ])
    if has_a:
        cmd.extend(["-map", "[aout]",
                    "-c:a", "aac", "-b:a", "192k"])
    cmd.append(output)

    print("\n运行:\n", " ".join(cmd), "\n")
    subprocess.run(cmd, check=True)
    print(f"\n✅ 完成: {output}")


if __name__ == "__main__":
    main()