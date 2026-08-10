# VideosMerge

视频合并工具，支持网格拼贴、画中画、照片墙三种模式。自动检测并去除抖音尾部 logo 片段。

## 项目结构

```
videos/
├── VideoMergeAndroid/    # Android 应用
│   ├── app/              # 主模块
│   ├── py/               # Python 参考脚本
│   └── ...
├── collage_merge.py      # 画中画合并脚本
├── grid_merge.py         # 网格合并脚本
└── photo_wall_merge.py   # 照片墙合并脚本
```

## Android 应用

### 功能

- 三种合并模式：网格拼贴、画中画、照片墙
- 自动检测并截断抖音尾部静止 logo 片段
- 合并后预览视频，确认后手动保存到相册
- 本地历史记录，随时查看已生成的视频
- FFmpeg 实时日志滚动显示

### 编译

```bash
cd VideoMergeAndroid
./gradlew assembleDebug
```

### 打包

```bash
./gradlew packageApk
```

生成的 APK 输出到 `VideoMergeAndroid/outputs/` 目录，文件名格式 `VideoMerge_yyyyMMdd_HHmmss.apk`。

## Python 脚本

```bash
python grid_merge.py -d /path/to/videos
python collage_merge.py -d /path/to/videos
python photo_wall_merge.py -d /path/to/videos
```
