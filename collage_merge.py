#!/usr/bin/env python3
"""
自动合并同目录下所有视频为"一主多副"视频墙布局（含人脸智能裁剪）。

布局说明：
  - 一个大主窗口 + 若干小副窗口，严格无空隙铺满画布；
  - 主窗口在左/右/上/下，副窗口在对面网格排列；
  - 默认 cover（裁剪填充），每个窗口满屏无黑边，类似监控墙/影视多画面。

人脸智能裁剪：
  - cover 模式下自动检测每个人脸位置，裁剪时把人脸居中而非画面居中；
  - 多时间点采样（10% / 25% / 50%）+ 正脸/侧脸双 Haar 级联，提高检出率；
  - 检测不到人脸时回退到正中裁剪；
  - 需要 opencv-python（已自动检测，缺失时回退到正中裁剪）。

用法：
    python3 collage_merge.py                       # 默认：主窗左，副窗右，人脸智能裁剪
    python3 collage_merge.py --orient top          # 主窗在上，副窗在下
    python3 collage_merge.py --main 2              # 指定第3个视频为主窗口
    python3 collage_merge.py -r 0.5                # 主窗占 50%
    python3 collage_merge.py --fit contain         # 缩放留黑边（不裁剪、不动脸）
    python3 collage_merge.py --gap 4               # 窗口间 4px 间隔（模拟边框）
    python3 collage_merge.py --no-face-detect      # 关闭人脸检测，用正中裁剪
"""
import argparse
import math
import os
import re
import subprocess
import sys

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")


# ---------- 视频探测 ----------

def scan_videos(directory):
    files = []
    for f in os.listdir(directory):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") and not f.lower().startswith("output"):
            files.append(os.path.join(directory, f))
    return sorted(files)


def get_duration(path):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True)
    return float(r.stdout.strip())


def get_dimensions(path):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True)
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def has_audio(path):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a",
         "-show_entries", "stream=codec_type",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True)
    return "audio" in r.stdout.strip()


def detect_logo_cut(path, noise=0.01, min_dur=0.5, end_margin=0.05):
    r = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", path,
         "-filter:v", f"freezedetect=n={noise}:d={min_dur}", "-an", "-f", "null", "-"],
        capture_output=True, text=True)
    starts, ends = [], []
    for line in r.stderr.splitlines():
        ms = re.search(r"freeze_start:\s*([\d.]+)", line)
        me = re.search(r"freeze_end:\s*([\d.]+)", line)
        if ms:
            starts.append(float(ms.group(1)))
        if me:
            ends.append(float(me.group(1)))
    if not starts:
        return None
    last_end = ends[-1] if ends else -1.0
    tail = [s for s in starts if s > last_end]
    if not tail:
        tail = starts[-1:]
    cut = tail[0] - end_margin
    return cut if cut > 0 else None


# ---------- 人脸检测（智能裁剪） ----------

# 懒加载：只在首次调用时 import cv2
_cv2 = None
_frontal_cascade = None
_default_cascade = None
_profile_cascade = None


def _find_cascade(name):
    """在多个已知路径中搜索 Haar 级联 xml 文件"""
    import os
    candidates = []
    # 1. pip opencv-python 自带
    try:
        candidates.append(os.path.join(_cv2.data.haarcascades, name))
    except Exception:
        pass
    # 2. Homebrew OpenCV
    candidates.extend([
        f"/opt/homebrew/share/opencv4/haarcascades/{name}",
        f"/usr/local/share/opencv4/haarcascades/{name}",
        f"/opt/homebrew/Cellar/opencv/*/share/opencv4/haarcascades/{name}",
    ])
    # 3. 脚本同目录 cascades/
    here = os.path.dirname(os.path.abspath(__file__))
    candidates.append(os.path.join(here, "cascades", name))
    for p in candidates:
        import glob
        for hit in glob.glob(p):
            if os.path.isfile(hit):
                return hit
    return None


def _load_cascades():
    """加载 Haar 级联分类器（正脸 alt2 + 正脸 default + 侧脸）"""
    global _cv2, _frontal_cascade, _default_cascade, _profile_cascade
    if _cv2 is not None:
        return _cv2
    try:
        import cv2 as _mod
        _cv2 = _mod
        fpath = _find_cascade("haarcascade_frontalface_alt2.xml")
        dpath = _find_cascade("haarcascade_frontalface_default.xml")
        ppath = _find_cascade("haarcascade_profileface.xml")
        _frontal_cascade = _cv2.CascadeClassifier(fpath) if fpath else _cv2.CascadeClassifier()
        _default_cascade = _cv2.CascadeClassifier(dpath) if dpath else _cv2.CascadeClassifier()
        _profile_cascade = _cv2.CascadeClassifier(ppath) if ppath else _cv2.CascadeClassifier()
    except Exception:
        _cv2 = False  # 标记为不可用
    return _cv2


def detect_face_center(path, duration, dims, debug=False):
    """
    从视频中多帧多模型检测人脸，返回 (fx, fy) 比例位置。
    汇总所有检出帧的人脸位置取加权平均（人脸移动时也能覆盖），
    再与正中混合 30% 作安全余量。
    debug=True 时把每帧检测结果画框存到 _debug_faces/ 目录。
    """
    cv2 = _load_cascades()
    if not cv2:
        return 0.5, 0.5, False

    import numpy as np

    # 多时间点采样（4 帧），汇总所有检出
    timestamps = []
    for frac in (0.10, 0.25, 0.50, 0.75):
        t = duration * frac
        if t > 0.3:
            timestamps.append(t)
    if not timestamps:
        timestamps = [0.5]

    all_centers = []   # [(cx_frac, cy_frac, weight)]
    debug_dir = os.path.join(os.path.dirname(os.path.abspath(path)), "_debug_faces")

    for ti, ts in enumerate(timestamps):
        r = subprocess.run(
            ["ffmpeg", "-hide_banner", "-ss", f"{ts:.2f}", "-i", path,
             "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"],
            capture_output=True, timeout=15,
        )
        if not r.stdout:
            continue
        buf = np.frombuffer(r.stdout, np.uint8)
        frame = cv2.imdecode(buf, cv2.IMREAD_COLOR)
        if frame is None:
            continue

        fh, fw = frame.shape[:2]
        scale = min(1.0, 800.0 / max(fw, fh))
        if scale < 1.0:
            small = cv2.resize(frame, (int(fw * scale), int(fh * scale)))
        else:
            small = frame
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        gray = cv2.equalizeHist(gray)
        sw = fw * scale  # 缩小后宽
        sh = fh * scale

        faces = []
        # 正脸 alt2（较严格）
        if not _frontal_cascade.empty():
            faces += list(_frontal_cascade.detectMultiScale(
                gray, scaleFactor=1.1, minNeighbors=3, minSize=(24, 24)))
        # 正脸 default（更宽松，补充 alt2 漏检）
        if not _default_cascade.empty():
            faces += list(_default_cascade.detectMultiScale(
                gray, scaleFactor=1.1, minNeighbors=3, minSize=(24, 24)))
        # 侧脸 + 翻转侧脸
        if not _profile_cascade.empty():
            faces += list(_profile_cascade.detectMultiScale(
                gray, scaleFactor=1.1, minNeighbors=3, minSize=(24, 24)))
            flipped = cv2.flip(gray, 1)
            faces2 = list(_profile_cascade.detectMultiScale(
                flipped, scaleFactor=1.1, minNeighbors=3, minSize=(24, 24)))
            for (x, y, w, h) in faces2:
                faces.append((sw - x - w, y, w, h))

        # 去重（不同模型可能检出同一张脸）
        unique = []
        for (x, y, w, h) in faces:
            cx = x + w / 2
            cy = y + h / 2
            dup = False
            for (ux, uy, uw, uh) in unique:
                if abs(cx - (ux + uw / 2)) < w * 0.3 and abs(cy - (uy + uh / 2)) < h * 0.3:
                    dup = True
                    break
            if not dup:
                unique.append((x, y, w, h))

        if not unique:
            if debug:
                os.makedirs(debug_dir, exist_ok=True)
                tagged = small.copy()
                cv2.putText(tagged, f"t={ts:.1f}s NO FACE", (5, 20),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
                name = os.path.splitext(os.path.basename(path))[0]
                cv2.imwrite(os.path.join(debug_dir, f"{name}_{ti}.png"), tagged)
            continue

        # 画调试框
        if debug:
            os.makedirs(debug_dir, exist_ok=True)
            tagged = small.copy()
            for (x, y, w, h) in unique:
                cv2.rectangle(tagged, (x, y), (x + w, y + h), (0, 255, 0), 2)
            cv2.putText(tagged, f"t={ts:.1f}s {len(unique)} face(s)", (5, 20),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
            name = os.path.splitext(os.path.basename(path))[0]
            cv2.imwrite(os.path.join(debug_dir, f"{name}_{ti}.png"), tagged)

        # 收集该帧的人脸质心
        for (x, y, w, h) in unique:
            cx_frac = (x + w / 2) / sw
            cy_frac = (y + h / 2) / sh
            weight = w * h
            all_centers.append((cx_frac, cy_frac, weight))

    if not all_centers:
        return 0.5, 0.5, False

    # 加权平均所有帧所有人脸位置
    total_w = sum(w for _, _, w in all_centers)
    cx = sum(fx * w for fx, _, w in all_centers) / total_w
    cy = sum(fy * w for _, fy, w in all_centers) / total_w

    # 与正中混合 30% 作安全余量（避免极端偏移切到边缘）
    cx = cx * 0.7 + 0.5 * 0.3
    cy = cy * 0.7 + 0.5 * 0.3
    cx = max(0.08, min(0.92, cx))
    cy = max(0.08, min(0.92, cy))
    return cx, cy, True


# ---------- 一主多副布局算法（严格无空隙） ----------

def even_divide(total, parts, gap=0):
    """把 total（偶数）切成 parts 个偶数块，精确铺满，块间留 gap。"""
    total -= total % 2
    gap -= gap % 2
    avail = total - (parts - 1) * gap
    if avail < parts * 2:
        avail = parts * 2
    base = (avail // parts) // 2 * 2   # 偶数下取整
    if base < 2:
        base = 2
    sizes = [base] * parts
    rem = avail - sum(sizes)
    i = len(sizes) - 1
    while rem >= 2 and i >= 0:
        sizes[i] += 2
        rem -= 2
        i -= 1
    return sizes


def calc_main_sub(n, canvas_w, canvas_h, main_ratio, orient, sub_cols, gap, main_idx):
    """
    一主多副布局，严格无空隙。
    返回 sizes[(w,h)], positions[(x,y)], out_w, out_h（按输入顺序对应）
    """
    canvas_w -= canvas_w % 2
    canvas_h -= canvas_h % 2
    gap -= gap % 2
    subs = n - 1

    # 按输入顺序的最终结果
    sizes = [None] * n
    positions = [None] * n

    if subs == 0:
        sizes[0] = (canvas_w, canvas_h)
        positions[0] = (0, 0)
        return sizes, positions, canvas_w, canvas_h

    # 副窗口索引（排除主窗口）
    sub_indices = [i for i in range(n) if i != main_idx]

    if orient in ("left", "right"):
        main_w = round(canvas_w * main_ratio)
        main_w -= main_w % 2
        sub_w = canvas_w - main_w

        sc = sub_cols or (2 if subs >= 6 else 1)
        sub_rows = math.ceil(subs / sc)
        row_hs = even_divide(canvas_h, sub_rows, gap)

        # 主窗口
        main_x = 0 if orient == "left" else sub_w
        sizes[main_idx] = (main_w, canvas_h)
        positions[main_idx] = (main_x, 0)

        # 副窗口
        si = 0
        y = 0
        for r in range(sub_rows):
            remaining = subs - si
            cols_this = min(sc, remaining)
            col_ws = even_divide(sub_w, cols_this, gap)
            x_base = main_w if orient == "left" else 0
            x = x_base
            for c in range(cols_this):
                w = col_ws[c]
                h = row_hs[r]
                idx = sub_indices[si]
                sizes[idx] = (w, h)
                positions[idx] = (x, y)
                x += w + gap
                si += 1
            y += h + gap

    else:  # top / bottom
        main_h = round(canvas_h * main_ratio)
        main_h -= main_h % 2
        sub_h = canvas_h - main_h

        sr = sub_cols or (2 if subs >= 6 else 1)  # 这里复用为"每列几个"
        sub_cols_auto = math.ceil(subs / sr)
        col_ws = even_divide(canvas_w, sub_cols_auto, gap)

        main_y = 0 if orient == "top" else sub_h
        sizes[main_idx] = (canvas_w, main_h)
        positions[main_idx] = (0, main_y)

        si = 0
        x = 0
        for c in range(sub_cols_auto):
            remaining = subs - si
            rows_this = min(sr, remaining)
            row_hs = even_divide(sub_h, rows_this, gap)
            y_base = main_h if orient == "top" else 0
            y = y_base
            for r in range(rows_this):
                w = col_ws[c]
                h = row_hs[r]
                idx = sub_indices[si]
                sizes[idx] = (w, h)
                positions[idx] = (x, y)
                y += h + gap
                si += 1
            x += w + gap

    return sizes, positions, canvas_w, canvas_h


# ---------- filter 构建 ----------

def scale_fill(cw, ch, fit, fx=0.5, fy=0.5):
    """
    cover=裁剪填满无黑边（fx/fy 控制裁剪偏移：0.5=正中，人脸位置=跟脸走）；
    contain=缩放留黑边（不裁剪）。
    """
    if fit == "contain":
        return (
            f"scale={cw}:{ch}:force_original_aspect_ratio=decrease,"
            f"pad={cw}:{ch}:(ow-iw)/2:(oh-ih)/2:black,setsar=1"
        )
    # cover: crop 偏移 = 人脸比例 × 可裁剪余量
    return (
        f"scale={cw}:{ch}:force_original_aspect_ratio=increase,"
        f"crop={cw}:{ch}:{fx:.4f}*(in_w-out_w):{fy:.4f}*(in_h-out_h),setsar=1"
    )


def build_filter(n, sizes, positions, durations, max_dur, audio_mask, cut_times, fit, face_xy=None):
    if face_xy is None:
        face_xy = [(0.5, 0.5)] * n
    scaled = []
    for i in range(n):
        cw, ch = sizes[i]
        pad_dur = max(0.0, max_dur - durations[i])
        common = scale_fill(cw, ch, fit, *face_xy[i]) + f"[v{i}]"
        trim_pfx = f"trim=end={cut_times[i]:.3f},setpts=PTS-STARTPTS," if cut_times[i] else ""
        if pad_dur <= 0.001:
            scaled.append(f"[{i}:v]{trim_pfx}{common}")
        else:
            scaled.append(
                f"[{i}:v]{trim_pfx}split=2[{i}A][{i}B];"
                f"[{i}A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[{i}Fof];"
                f"[{i}B]setpts=PTS-STARTPTS[{i}M];"
                f"[{i}M][{i}Fof]concat=n=2:v=1:a=0[{i}C];"
                f"[{i}C]trim=end={max_dur:.3f},setpts=PTS-STARTPTS,{common}"
            )

    layouts = [f"{positions[i][0]}_{positions[i][1]}" for i in range(n)]
    inputs_concat = "".join(f"[v{i}]" for i in range(n))
    layout_str = "|".join(layouts)
    parts = [";".join(scaled),
             f"{inputs_concat}xstack=inputs={n}:layout={layout_str}:fill=black[vout]"]

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


# ---------- 主流程 ----------

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="自动扫描同目录视频并一主多副合并（视频墙）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名")
    ap.add_argument("-w", "--width", type=int, default=1920, help="画布宽度像素（默认 1920）")
    ap.add_argument("--height", type=int, default=1080, help="画布高度像素（默认 1080）")
    ap.add_argument("-r", "--main-ratio", type=float, default=0.62,
                    help="主窗口占比（默认 0.62，即占宽度或高度的 62%%）")
    ap.add_argument("--orient", choices=["left", "right", "top", "bottom"], default="left",
                    help="主窗口位置：left/right=主在左/右副在对面；top/bottom=主在上/下")
    ap.add_argument("--main", type=int, default=0, help="主窗口视频序号（默认第 0 个）")
    ap.add_argument("-c", "--sub-cols", type=int, default=None,
                    help="副窗口列数/行数（默认自动：≥6副用2列）")
    ap.add_argument("-g", "--gap", type=int, default=0, help="窗口间距像素（默认 0，设 4/8 模拟边框）")
    ap.add_argument("--fit", choices=["cover", "contain"], default="cover",
                    help="cover=裁剪填满无黑边（默认）；contain=缩放留黑边")
    ap.add_argument("-d", "--dir", default=None, help="视频所在目录")
    ap.add_argument("--no-trim-logo", action="store_true", help="跳过尾部 logo 检测与截断")
    ap.add_argument("--no-face-detect", action="store_true",
                    help="跳过人脸检测（cover 模式下用正中裁剪）")
    ap.add_argument("--debug-face", action="store_true",
                    help="把每帧人脸检测结果画框存到 _debug_faces/ 便于排查")
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
    if args.main >= n:
        print(f"❌ --main {args.main} 超出范围（共 {n} 个视频，序号 0~{n-1}）", file=sys.stderr)
        sys.exit(1)

    sizes, positions, out_w, out_h = calc_main_sub(
        n, args.width, args.height, args.main_ratio,
        args.orient, args.sub_cols, args.gap, args.main
    )

    dims = [get_dimensions(v) for v in videos]
    print("找到视频:")
    for i, (v, (w, h)) in enumerate(zip(videos, dims)):
        tag = " ★主" if i == args.main else ""
        print(f"   [{i}] {os.path.basename(v)}  {w}x{h}{tag}")
    print(f"数量: {n}  主窗位置: {args.orient}  主窗占比: {args.main_ratio}  "
          f"画布: {out_w}x{out_h}  填充: {args.fit}  间距: {args.gap}")
    print("布局:")
    for i in range(n):
        cw, ch = sizes[i]
        x, y = positions[i]
        tag = "★主" if i == args.main else " 副"
        print(f"   [{i}]{tag} {os.path.basename(videos[i])}  {cw}x{ch} @({x},{y})")

    durations = [get_duration(v) for v in videos]
    audio_mask = [has_audio(v) for v in videos]

    cut_times = [None] * n
    if not args.no_trim_logo:
        print("检测尾部 logo（静止片段）：")
        for i, v in enumerate(videos):
            cut = detect_logo_cut(v, noise=args.noise)
            if cut is not None:
                cut_times[i] = cut
                print(f"   {os.path.basename(v)}  原时长 {durations[i]:.2f}s → 截断至 {cut:.2f}s"
                      f"（去除尾部 {(durations[i]-cut):.2f}s logo）")
            else:
                print(f"   {os.path.basename(v)}  原时长 {durations[i]:.2f}s → 未检测到尾部静止 logo")
    else:
        print("已跳过 logo 检测")

    eff_durations = [cut_times[i] if cut_times[i] else durations[i] for i in range(n)]
    max_dur = max(eff_durations)
    print(f"有效时长: {[round(d, 2) for d in eff_durations]}  以最长 {max_dur:.2f}s 为准")
    print(f"音频: {['有' if a else '无' for a in audio_mask]}")

    # 人脸检测（cover 模式才需要）
    face_xy = [(0.5, 0.5)] * n
    if args.fit == "cover" and not args.no_face_detect:
        cv2 = _load_cascades()
        if cv2:
            print("人脸检测（智能裁剪）：")
            for i, v in enumerate(videos):
                fx, fy, found = detect_face_center(
                    v, eff_durations[i], dims[i], debug=args.debug_face)
                face_xy[i] = (fx, fy)
                tag = "★主" if i == args.main else " 副"
                if found:
                    print(f"   [{i}]{tag} {os.path.basename(v)}  "
                          f"人脸 @({fx:.2f}, {fy:.2f}) → 裁剪偏移已调整")
                else:
                    print(f"   [{i}]{tag} {os.path.basename(v)}  "
                          f"未检出人脸 → 正中裁剪")
            if args.debug_face:
                print("   （调试帧已存到 _debug_faces/ 目录）")
        else:
            print("⚠️  未安装 opencv-python，跳过人脸检测（正中裁剪）")
            print("    安装：pip3 install opencv-python")
    elif args.no_face_detect:
        print("已跳过人脸检测（正中裁剪）")

    output = args.output or os.path.join(here, "output_wall.mp4")

    cmd = ["ffmpeg", "-y"]
    for v in videos:
        cmd.extend(["-i", v])

    durations = eff_durations
    vf, has_a = build_filter(n, sizes, positions, durations, max_dur, audio_mask, cut_times, args.fit, face_xy)

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
        cmd.extend(["-map", "[aout]", "-c:a", "aac", "-b:a", "192k"])
    cmd.append(output)

    print("\n运行:\n", " ".join(cmd), "\n")
    subprocess.run(cmd, check=True)
    print(f"\n✅ 完成: {output}")


if __name__ == "__main__":
    main()
