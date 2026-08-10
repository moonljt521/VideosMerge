#!/usr/bin/env python3
"""
照片墙风格合并视频 —— 大大小小、错落有致，并尽量保留人脸不被裁剪。

用法与 grid_merge.py 一致，把视频文件和本脚本放在同一目录运行：
    python3 photo_wall_merge.py
即可生成 output_wall.mp4。

核心思路
--------
1. 用 OpenCV Haar 级联检测每个视频中多帧的人脸区域；
2. 为每个视频计算一个 "安全裁剪中心"（基于人脸位置 + 头顶余量），保证裁剪时人脸不丢失；
3. 用 treemap-style 算法把画布分成大大小小的矩形块（大的占大块、小的占小块），
   并加上随机偏移和微小旋转，形成照片墙的错落感；
4. 每个视频按格子宽高比裁剪后缩放填满格子（不拉伸），裁剪中心偏向人脸；
5. 为每个视频添加白色边框 + 阴影效果，模拟真实照片贴在墙上的感觉；
6. 用 ffmpeg overlay 滤镜逐层叠加到画布上。
"""

import argparse
import math
import os
import random
import subprocess
import sys

try:
    import cv2
    import numpy as np
    _CV2_AVAILABLE = True
except ImportError:
    _CV2_AVAILABLE = False

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")

# ─── 默认常量 ────────────────────────────────────────────
def _find_haar_cascade():
    """动态查找 Haar 级联文件路径，兼容多种安装方式"""
    if not _CV2_AVAILABLE:
        return None
    # 1) 优先使用 cv2.data（pip install opencv-python 自带）
    candidates = [
        os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml"),
        os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_alt2.xml"),
    ]
    # 2) Homebrew 常见路径
    for prefix in ["/opt/homebrew", "/usr/local"]:
        import glob as _glob
        for p in _glob.glob(os.path.join(prefix, "Cellar/opencv/*/share/opencv4/haarcascades/haarcascade_frontalface_default.xml")):
            candidates.append(p)
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None

HAAR_PATH = _find_haar_cascade()
DEFAULT_CANVAS_W = 3840    # 画布宽度（4K）
DEFAULT_CANVAS_H = 2160    # 画布高度
BORDER_PX = 8              # 白色边框宽度
SHADOW_PX = 12             # 阴影偏移
ROTATION_MAX = 3.5         # 最大旋转角度（度）
PADDING_GAP = 18           # 照片之间最小间距
BG_COLOR = (40, 40, 45)    # 墙壁背景色 (BGR for OpenCV)


# ─── 工具函数 ────────────────────────────────────────────

def scan_videos(directory: str):
    """扫描目录下所有视频文件（按文件名排序），排除输出文件"""
    files = []
    for f in os.listdir(directory):
        if f.lower().endswith(VIDEO_EXTS) and not f.startswith(".") and not f.lower().startswith("output"):
            files.append(os.path.join(directory, f))
    return sorted(files)


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
    last_end = ends[-1] if ends else -1.0
    tail = [s for s in starts if s > last_end]
    if not tail:
        tail = starts[-1:]
    cut = tail[0] - end_margin
    return cut if cut > 0 else None


# ─── 人脸检测 & 智能裁剪 ────────────────────────────────

def _clip(val, lo, hi):
    """纯 Python 的 clip，不依赖 numpy"""
    return max(lo, min(val, hi))


def detect_faces(path: str):
    """检测视频中的人脸，抽取多帧取并集，返回 [(x, y, w, h), ...] 列表"""
    if not _CV2_AVAILABLE or not HAAR_PATH:
        return []
    classifier = cv2.CascadeClassifier(HAAR_PATH)
    if classifier.empty():
        print("  ⚠️  Haar 级联文件加载失败，跳过人脸检测", file=sys.stderr)
        return []

    # 尝试加载 alt2 级联作为 fallback（对侧脸/角度更鲁棒）
    alt2_path = None
    if _CV2_AVAILABLE:
        alt2_path = os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_alt2.xml")
        if not os.path.isfile(alt2_path):
            alt2_path = None
    alt2_classifier = cv2.CascadeClassifier(alt2_path) if alt2_path else None

    dur = get_duration(path)
    all_faces = []
    # 抽取 5 帧做检测，取并集，提高检出率
    timestamps = [0.5, dur * 0.25, dur * 0.5, max(1.0, dur * 0.75), max(1.5, dur * 0.9)]
    for ts in timestamps:
        try:
            r = subprocess.run(
                ["ffmpeg", "-hide_banner", "-ss", f"{ts:.3f}", "-i", path,
                 "-vframes", "1", "-f", "image2pipe", "-vcodec", "png", "-"],
                capture_output=True, check=True,
            )
            arr = np.frombuffer(r.stdout, dtype=np.uint8)
            frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if frame is None:
                continue
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            # 降低阈值以提高检出率
            faces = classifier.detectMultiScale(
                gray, scaleFactor=1.05, minNeighbors=3, minSize=(50, 50)
            )
            if len(faces) == 0 and alt2_classifier and not alt2_classifier.empty():
                faces = alt2_classifier.detectMultiScale(
                    gray, scaleFactor=1.05, minNeighbors=3, minSize=(50, 50)
                )
            for (x, y, w, h) in faces:
                all_faces.append((int(x), int(y), int(w), int(h)))
        except Exception:
            continue
    return all_faces


def get_face_bbox(faces):
    """从人脸列表计算包围盒 (fx_min, fy_min, fx_max, fy_max)，无脸返回 None"""
    if not faces:
        return None
    fx_min = min(x for x, y, w, h in faces)
    fy_min = min(y for x, y, w, h in faces)
    fx_max = max(x + w for x, y, w, h in faces)
    fy_max = max(y + h for x, y, w, h in faces)
    return (fx_min, fy_min, fx_max, fy_max)


def compute_safe_crop_center(vw: int, vh: int, faces, crop_w: int, crop_h: int):
    """
    根据人脸位置计算安全裁剪中心 (cx, cy)。

    原理：从视频中心开始裁剪时，人脸可能被切掉。
    本函数将裁剪中心朝人脸重心方向偏移，使人脸尽量在裁剪框内。
    同时在头顶方向留出余量，确保完整头部可见。
    """
    half_w = crop_w / 2
    half_h = crop_h / 2

    # 未检测到人脸时，默认取上 1/3 处（人物头部通常在画面上方）
    if not faces:
        cx = vw / 2
        cy = vh / 3.0
        cx = max(half_w, min(cx, vw - half_w))
        cy = max(half_h, min(cy, vh - half_h))
        return cx, cy

    bbox = get_face_bbox(faces)
    fx_min, fy_min, fx_max, fy_max = bbox
    face_cx = (fx_min + fx_max) / 2
    face_cy = (fy_min + fy_max) / 2

    # 计算人脸高度，在头顶方向留出余量（约 0.6 倍脸高）
    face_h = fy_max - fy_min
    face_cy -= face_h * 0.6

    # 裁剪范围限制（中心可移动的范围）
    cx = max(half_w, min(face_cx, vw - half_w))
    cy = max(half_h, min(face_cy, vh - half_h))

    return cx, cy


# ─── 照片墙布局算法（treemap-style）─────────────────────

def treemap_layout(canvas_w, canvas_h, n):
    """
    用 slice-and-dice treemap 算法把画布分成大大小小的矩形块。
    返回 [(x, y, w, h), ...] 每个视频在画布上的位置与尺寸。
    """
    # 给每个视频分配一个 "权重"，用来决定它占多大面积
    # 前 ~1/3 的视频权重大（大块），其余小
    weights = []
    for i in range(n):
        if i < max(1, n // 3):
            weights.append(random.uniform(1.8, 3.0))
        else:
            weights.append(random.uniform(0.8, 1.5))
    random.shuffle(weights)

    total_weight = sum(weights)
    total_area = canvas_w * canvas_h
    areas = [total_area * w / total_weight for w in weights]

    # 用 slice-and-dice treemap 算法
    result = [None] * n

    def _slice(indices, x, y, w, h, horizontal=True):
        if len(indices) == 0:
            return
        if len(indices) == 1:
            idx = indices[0]
            # 留出间距
            pw = max(w - 2 * PADDING_GAP, 100)
            ph = max(h - 2 * PADDING_GAP, 100)
            # 确保宽高为偶数
            pw -= pw % 2
            ph -= ph % 2
            # 居中 + 微小随机偏移
            px = x + (w - pw) / 2 + random.uniform(-3, 3)
            py = y + (h - ph) / 2 + random.uniform(-3, 3)
            result[idx] = (int(px), int(py), pw, ph)
            return

        sub_weights = [weights[i] for i in indices]
        sub_total = sum(sub_weights)

        # 找到接近一半的切分点，确保两边都不为空
        acc = 0
        split = 1
        for k in range(len(sub_weights) - 1):  # 最多取到 len-1，保证 right 非空
            acc += sub_weights[k]
            if acc >= sub_total / 2:
                split = k + 1
                break

        left_indices = indices[:split]
        right_indices = indices[split:]
        left_ratio = sum(weights[i] for i in left_indices) / sub_total

        # 防止递归过深：如果切分后尺寸太小，直接平铺分配
        if w < 2 * PADDING_GAP + 150 or h < 2 * PADDING_GAP + 150:
            # 直接给剩余所有索引分配当前区域（平铺）
            cols = max(1, int(len(indices) ** 0.5))
            rows = (len(indices) + cols - 1) // cols
            cell_w = max(50, (w - (cols - 1) * PADDING_GAP) // cols)
            cell_h = max(50, (h - (rows - 1) * PADDING_GAP) // rows)
            cell_w -= cell_w % 2
            cell_h -= cell_h % 2
            for idx_i, idx in enumerate(indices):
                c = idx_i % cols
                r = idx_i // cols
                px = x + c * (cell_w + PADDING_GAP)
                py = y + r * (cell_h + PADDING_GAP)
                result[idx] = (int(px), int(py), cell_w, cell_h)
            return

        if horizontal:
            lw = max(1, int(w * left_ratio))
            _slice(left_indices, x, y, lw, h, not horizontal)
            _slice(right_indices, x + lw, y, w - lw, h, not horizontal)
        else:
            lh = max(1, int(h * left_ratio))
            _slice(left_indices, x, y, w, lh, not horizontal)
            _slice(right_indices, x, y + lh, w, h - lh, not horizontal)

    indices = list(range(n))
    # 随机打乱索引，让大小分布不按文件顺序
    random.shuffle(indices)
    _slice(indices, 0, 0, canvas_w, canvas_h, horizontal=True)

    return result


def add_wall_jitter(layout, canvas_w, canvas_h):
    """给布局中的每个矩形添加微小随机偏移，增强错落感"""
    result = []
    for (x, y, w, h) in layout:
        dx = random.randint(-6, 6)
        dy = random.randint(-6, 6)
        x2 = max(0, min(canvas_w - w, x + dx))
        y2 = max(0, min(canvas_h - h, y + dy))
        result.append((int(x2), int(y2), w, h))
    return result


# ─── 核心：构建 ffmpeg 滤镜图 ────────────────────────────

def build_wall_filter(videos, dims, layout, rotations, crop_centers, durations, max_dur,
                      audio_mask, cut_times, canvas_w, canvas_h):
    """
    构建照片墙风格的 ffmpeg filter_complex。

    策略：
    1. 用 color 滤镜生成墙壁背景色画布
    2. 对每个视频：按格子宽高比裁剪（填满格子不拉伸，人脸居中）→ 加白边框阴影 → 可选旋转 → overlay
    3. 用 overlay 滤镜逐层叠加
    """
    n = len(videos)
    filters = []

    # 生成背景色画布 (BGR -> RGB)
    bg_r, bg_g, bg_b = BG_COLOR[2], BG_COLOR[1], BG_COLOR[0]
    filters.append(
        f"color=c=0x{bg_r:02x}{bg_g:02x}{bg_b:02x}:s={canvas_w}x{canvas_h}"
        f":d={max_dur:.3f}:rate=30[bg]"
    )

    # 处理每个视频
    for i in range(n):
        x, y, cell_w, cell_h = layout[i]
        vw, vh = dims[i]
        rot = rotations[i]
        ccx, ccy = crop_centers[i]

        pad_dur = max(0.0, max_dur - durations[i])
        trim_pfx = f"trim=end={cut_times[i]:.3f},setpts=PTS-STARTPTS," if cut_times[i] else ""

        # ── 第一步：按格子宽高比裁剪 → 缩放到格子尺寸（不拉伸，人脸居中）──
        target_ar = cell_w / cell_h
        video_ar = vw / vh
        if video_ar > target_ar:
            # 视频比格子宽 → 裁掉左右
            crop_h = vh
            crop_w = max(2, int(vh * target_ar))
            crop_w = min(crop_w, vw)
        else:
            # 视频比格子高 → 裁掉上下
            crop_w = vw
            crop_h = max(2, int(vw / target_ar))
            crop_h = min(crop_h, vh)
        crop_w -= crop_w % 2
        crop_h -= crop_h % 2
        crop_x = int(_clip(ccx - crop_w / 2, 0, vw - crop_w))
        crop_y = int(_clip(ccy - crop_h / 2, 0, vh - crop_h))
        crop_str = f"crop={crop_w}:{crop_h}:{crop_x}:{crop_y},"
        scale_str = f"scale={cell_w}:{cell_h}:flags=lanczos,"

        # ── 第二步：处理时长不足的情况（用最后一帧填充）──
        common_suffix = f"{crop_str}{scale_str}setsar=1"

        if pad_dur <= 0.001:
            filters.append(
                f"[{i}:v]{trim_pfx}{common_suffix}[v{i}raw]"
            )
        else:
            filters.append(
                f"[{i}:v]{trim_pfx}split=2[{i}A][{i}B];"
                f"[{i}A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[{i}Fof];"
                f"[{i}B]setpts=PTS-STARTPTS[{i}M];"
                f"[{i}M][{i}Fof]concat=n=2:v=1:a=0[{i}C];"
                f"[{i}C]trim=end={max_dur:.3f},setpts=PTS-STARTPTS,{common_suffix}[v{i}raw]"
            )

        # ── 第三步：给视频加白色边框 + 阴影 ──
        bordered_w = cell_w + 2 * BORDER_PX
        bordered_h = cell_h + 2 * BORDER_PX
        shadowed_w = bordered_w + SHADOW_PX
        shadowed_h = bordered_h + SHADOW_PX

        filters.append(
            f"[v{i}raw]pad={bordered_w}:{bordered_h}:-1:-1:color=white,"
            f"pad={shadowed_w}:{shadowed_h}:-1:-1:color=0x3c3c3c@0.35[v{i}bordered]"
        )

        # ── 第四步：可选旋转 ──
        if abs(rot) > 0.1:
            bg_hex = f"0x{bg_r:02x}{bg_g:02x}{bg_b:02x}"
            filters.append(
                f"[v{i}bordered]rotate={rot}*PI/180:c={bg_hex}"
                f":ow=rotw({rot}*PI/180):oh=roth({rot}*PI/180)[v{i}rot]"
            )
        else:
            filters.append(f"[v{i}bordered]copy[v{i}rot]")

        # ── 第五步：overlay 到画布上 ──
        overlay_x = max(0, x - BORDER_PX)
        overlay_y = max(0, y - BORDER_PX)

        if i == 0:
            filters.append(
                f"[bg][v{i}rot]overlay={overlay_x}:{overlay_y}:eof_action=repeat[v{i}out]"
            )
        else:
            prev = i - 1
            filters.append(
                f"[v{prev}out][v{i}rot]overlay={overlay_x}:{overlay_y}:eof_action=repeat[v{i}out]"
            )

    # 最终输出标签
    final_label = f"v{n - 1}out"

    # ─── 音频部分 ───
    audio_parts = []
    for i in range(n):
        if not audio_mask[i]:
            continue
        a_trim = f"atrim=end={cut_times[i]:.3f},asetpts=PTS-STARTPTS," if cut_times[i] else ""
        pad_a = max(0.0, max_dur - durations[i])
        apad = f",apad=whole_dur={max_dur:.3f}" if pad_a > 0 else ""
        audio_parts.append(f"[{i}:a]{a_trim}aresample=44100{apad}[a{i}]")

    audio_suffix = ""
    if audio_parts:
        amix_in = "".join(f"[a{i}]" for i in range(n) if audio_mask[i])
        audio_suffix = ";" + ";".join(audio_parts) + \
            f";{amix_in}amix=inputs={len(audio_parts)}:duration=first:normalize=0[aout]"

    filter_str = ";".join(filters) + audio_suffix

    # 把最终视频标签改名为 vout
    filter_str = filter_str.replace(f"[{final_label}]", "[vout]")

    has_a = bool(audio_parts)
    return filter_str, has_a


# ─── 主函数 ─────────────────────────────────────────────

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="照片墙风格合并视频（大大小小错落有致，保留人脸）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名")
    ap.add_argument("-d", "--dir", default=None, help="视频所在目录（默认脚本所在目录）")
    ap.add_argument("--seed", type=int, default=None, help="随机种子（用于复现布局）")
    ap.add_argument("--no-trim-logo", action="store_true", help="跳过尾部 logo 检测与截断")
    ap.add_argument("--noise", type=float, default=0.01, help="freezedetect 噪声阈值")
    ap.add_argument("--width", type=int, default=DEFAULT_CANVAS_W, help=f"画布宽度（默认 {DEFAULT_CANVAS_W}）")
    ap.add_argument("--height", type=int, default=DEFAULT_CANVAS_H, help=f"画布高度（默认 {DEFAULT_CANVAS_H}）")
    ap.add_argument("--no-face", action="store_true", help="跳过人脸检测（使用中心偏上裁剪）")
    args = ap.parse_args()

    if args.dir:
        here = os.path.abspath(args.dir)

    canvas_w = args.width - (args.width % 2)
    canvas_h = args.height - (args.height % 2)

    if args.seed is not None:
        random.seed(args.seed)
        if _CV2_AVAILABLE:
            np.random.seed(args.seed)

    videos = scan_videos(here)
    if not videos:
        print("❌ 目录里没有视频文件:", here, file=sys.stderr)
        print("支持扩展名:", ", ".join(VIDEO_EXTS), file=sys.stderr)
        sys.exit(1)

    n = len(videos)
    print(f"找到 {n} 个视频:")
    dims = []
    for v in videos:
        w, h = get_dimensions(v)
        dims.append((w, h))
        print(f"   {os.path.basename(v)}  {w}x{h}  比例={w/h:.2f}")

    # ── 1. 计算照片墙布局 ──
    layout = treemap_layout(canvas_w, canvas_h, n)
    layout = add_wall_jitter(layout, canvas_w, canvas_h)

    # 为每个视频分配随机旋转角
    rotations = [round(random.uniform(-ROTATION_MAX, ROTATION_MAX), 1) for _ in range(n)]

    print(f"\n照片墙布局 (画布 {canvas_w}x{canvas_h}):")
    for i, ((x, y, w, h), rot) in enumerate(zip(layout, rotations)):
        print(f"   视频 {i}: 位置({x},{y}) 尺寸{w}x{h} 旋转{rot}°")

    # ── 2. 人脸检测 & 安全裁剪中心 ──
    crop_centers = []
    haar_available = _CV2_AVAILABLE and HAAR_PATH is not None and os.path.isfile(HAAR_PATH)
    need_face = not args.no_face and haar_available

    if need_face:
        print("\n检测人脸中...")
    elif not args.no_face and not haar_available:
        print("\n⚠️  Haar 级联文件不存在，跳过人脸检测（使用偏上裁剪）", file=sys.stderr)
    else:
        print("\n已跳过人脸检测（使用偏上裁剪）")

    for i in range(n):
        vw, vh = dims[i]
        _, _, cell_w, cell_h = layout[i]

        # 计算 cover 裁剪尺寸（与格子宽高比一致）
        target_ar = cell_w / cell_h
        if vw / vh > target_ar:
            crop_h = vh
            crop_w = max(2, int(vh * target_ar))
            crop_w = min(crop_w, vw)
        else:
            crop_w = vw
            crop_h = max(2, int(vw / target_ar))
            crop_h = min(crop_h, vh)
        crop_w -= crop_w % 2
        crop_h -= crop_h % 2

        if need_face:
            faces = detect_faces(videos[i])
            bbox = get_face_bbox(faces)
            if bbox:
                fx_min, fy_min, fx_max, fy_max = bbox
                print(f"   {os.path.basename(videos[i])}: "
                      f"人脸 ({fx_min},{fy_min})-({fx_max},{fy_max})  "
                      f"裁剪框 {crop_w}x{crop_h}")
            else:
                print(f"   {os.path.basename(videos[i])}: 未检测到人脸，使用偏上裁剪")
            cx, cy = compute_safe_crop_center(vw, vh, faces, crop_w, crop_h)
        else:
            cx, cy = compute_safe_crop_center(vw, vh, [], crop_w, crop_h)

        crop_centers.append((cx, cy))

    # ── 3. 时长 & 音频 ──
    durations = [get_duration(v) for v in videos]
    audio_mask = [has_audio(v) for v in videos]

    cut_times = [None] * n
    if not args.no_trim_logo:
        print("\n检测尾部 logo（静止片段）：")
        for i, v in enumerate(videos):
            cut = detect_logo_cut(v, noise=args.noise)
            if cut is not None:
                cut_times[i] = cut
                print(f"   {os.path.basename(v)}  原时长 {durations[i]:.2f}s → 截断至 {cut:.2f}s")
            else:
                print(f"   {os.path.basename(v)}  原时长 {durations[i]:.2f}s → 未检测到尾部静止 logo")
    else:
        print("已跳过 logo 检测")

    eff_durations = [cut_times[i] if cut_times[i] else durations[i] for i in range(n)]
    max_dur = max(eff_durations)
    print(f"\n有效时长: {[round(d, 2) for d in eff_durations]}  以最长 {max_dur:.2f}s 为准")
    print(f"音频: {['有' if a else '无' for a in audio_mask]}")

    # ── 4. 构建滤镜 & 运行 ffmpeg ──
    output = args.output or os.path.join(here, "output_wall.mp4")

    cmd = ["ffmpeg", "-y"]
    for v in videos:
        cmd.extend(["-i", v])

    vf, has_a = build_wall_filter(
        videos, dims, layout, rotations, crop_centers,
        eff_durations, max_dur, audio_mask, cut_times,
        canvas_w, canvas_h
    )

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

    print(f"\n运行:\n  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"\n✅ 完成: {output}")


if __name__ == "__main__":
    main()
