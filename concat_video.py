#!/usr/bin/env python3
"""
视频拼接工具 —— 将多个视频首尾拼接为一个长视频。

支持同编码快速拼接（stream copy）和异编码重新编码拼接。

用法：
    # 拼接当前目录所有视频（同编码直接复制，最快）
    python3 concat_video.py

    # 指定多个视频拼接
    python3 concat_video.py -i a.mp4 b.mp4 c.mp4

    # 强制重新编码（不同编码/分辨率时需要）
    python3 concat_video.py --reencode

    # 拼接到指定目录的文件
    python3 concat_video.py -o merged.mp4

参数：
    -i / --input      多个视频路径（默认扫描当前目录所有视频）
    -o / --output     输出文件名（默认 output_concat.mp4）
    --reencode        强制重新编码（不同编码/分辨率时使用）
    --no-audio       丢弃音频

原理：
    同编码：concat demuxer + -c copy（不重编码，秒级完成）
    异编码：concat filter（统一编码为 H.264 + AAC，重编码较慢）
"""

import argparse
import os
import subprocess
import sys
import tempfile

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")


def scan_videos(directory: str):
    files = []
    for f in sorted(os.listdir(directory)):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") \
                and not f.lower().startswith("output"):
            files.append(os.path.join(directory, f))
    return files


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


def get_codec(path: str) -> str:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=codec_name",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return r.stdout.strip()


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
    ap = argparse.ArgumentParser(description="视频拼接工具：多视频首尾拼接")
    ap.add_argument("-i", "--input", nargs="+", default=None, help="多个视频路径")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_concat.mp4）")
    ap.add_argument("--reencode", action="store_true", help="强制重新编码（不同编码/分辨率时使用）")
    ap.add_argument("--no-audio", action="store_true", help="丢弃音频")
    args = ap.parse_args()

    # ── 确定输入文件列表 ──
    if args.input:
        videos = [os.path.abspath(v) for v in args.input]
    else:
        videos = scan_videos(here)

    if len(videos) < 2:
        print("❌ 至少需要 2 个视频", file=sys.stderr)
        sys.exit(1)

    for v in videos:
        if not os.path.isfile(v):
            print(f"❌ 文件不存在: {v}", file=sys.stderr)
            sys.exit(1)

    # ── 获取每个视频的元数据 ──
    metas = []
    for v in videos:
        dur = get_duration(v)
        w, h = get_dimensions(v)
        codec = get_codec(v)
        audio = has_audio(v)
        metas.append({"path": v, "dur": dur, "w": w, "h": h, "codec": codec, "audio": audio})

    total_dur = sum(m["dur"] for m in metas)
    codecs = set(m["codec"] for m in metas)
    dims = set((m["w"], m["h"]) for m in metas)
    any_audio = any(m["audio"] for m in metas)

    print("拼接视频:")
    for i, m in enumerate(metas):
        print(f"  [{i}] {os.path.basename(m['path'])}  {m['w']}x{m['h']}  {m['codec']}  {m['dur']:.2f}s")
    print(f"总时长: {total_dur:.2f}s  编码: {codecs}  分辨率: {dims}")

    output_path = args.output or os.path.join(here, "output_concat.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 判断是否可以同编码拼接 ──
    same_codec = len(codecs) == 1
    same_dims = len(dims) == 1
    use_demuxer = same_codec and same_dims and not args.reencode

    if use_demuxer:
        print("\n模式: concat demuxer（同编码，直接复制流）")

        # 创建 concat 列表文件
        list_file = os.path.join(here, "_concat_list.txt")
        with open(list_file, "w", encoding="utf-8") as f:
            for v in videos:
                # 单引号转义
                safe_path = v.replace("'", "'\\''")
                f.write(f"file '{safe_path}'\n")

        cmd = [
            "ffmpeg", "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", list_file,
            "-c", "copy",
        ]
        if args.no_audio or not any_audio:
            cmd.append("-an")
        cmd.append(output_path)

        print(f"运行 ffmpeg...")
        print(f"  {' '.join(cmd)}\n")
        subprocess.run(cmd, check=True)

        os.remove(list_file)
    else:
        print("\n模式: concat filter（重新编码统一格式）")
        if not same_codec:
            print(f"  原因: 编码不同 {codecs}")
        if not same_dims:
            print(f"  原因: 分辨率不同 {dims}")

        # 统一到第一个视频的分辨率
        target_w, target_h = metas[0]["w"], metas[0]["h"]
        target_w -= target_w % 2
        target_h -= target_h % 2

        n = len(videos)
        # 为每个视频构建 scale + setsar
        scaled = []
        for i in range(n):
            scaled.append(
                f"[{i}:v]scale={target_w}:{target_h}:force_original_aspect_ratio=decrease,"
                f"pad={target_w}:{target_h}:(ow-iw)/2:(oh-ih)/2:black,"
                f"fps=30,setsar=1[v{i}]"
            )

        v_labels = "".join(f"[v{i}]" for i in range(n))
        concat_filter = f"{v_labels}concat=n={n}:v=1:a=0[vout]"

        parts = [";".join(scaled), concat_filter]

        # 音频
        if any_audio and not args.no_audio:
            audio_parts = []
            for i in range(n):
                if metas[i]["audio"]:
                    audio_parts.append(f"[{i}:a]aresample=44100[a{i}]")
            if audio_parts:
                a_labels = "".join(f"[a{i}]" for i in range(n) if metas[i]["audio"])
                concat_audio = f"{a_labels}concat=n={len(audio_parts)}:v=0:a=1[aout]"
                parts.append(";".join(audio_parts))
                parts.append(concat_audio)

        filter_complex = ";".join(parts)

        cmd = [
            "ffmpeg", "-y",
        ]
        for v in videos:
            cmd.extend(["-i", v])
        cmd.extend([
            "-filter_complex", filter_complex,
            "-map", "[vout]",
        ])
        if any_audio and not args.no_audio:
            cmd.extend(["-map", "[aout]", "-c:a", "aac", "-b:a", "192k"])
        cmd.extend([
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "medium",
            "-crf", "20",
        ])
        cmd.append(output_path)

        print(f"运行 ffmpeg...")
        print(f"  {' '.join(cmd)}\n")
        subprocess.run(cmd, check=True)

    print(f"✅ 完成: {output_path}")
    print(f"   {len(videos)} 个视频已拼接，总时长 {total_dur:.2f}s")


if __name__ == "__main__":
    main()
