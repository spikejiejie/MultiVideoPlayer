# 多开播放器 (MultiVideo Player)

一款轻量级安卓本地视频多开播放器，支持多视频并行播放、双模式切换、全屏手势控制。

## 功能特性

### 双模式播放

- **应用内播放**：视频在APP内垂直堆叠显示，支持滚动查看
- **悬浮窗播放**：视频以悬浮窗形式显示在其他应用之上
  - 竖屏：垂直堆叠，宽度100%屏幕
  - 横屏：水平并排，高度100%屏幕
  - 自动跟随屏幕方向切换布局

### 视频管理

- 支持多选视频文件批量添加
- 支持单个关闭和全部关闭
- 自动加载同目录下同名字幕文件

### 播放控制

- 播放/暂停
- 进度条拖动快进/快退
- 视频区域左右滑动拖动进度
- 循环播放
- 点击视频区域隐藏/显示控制栏

### 全屏播放

- 横屏全屏播放
- 屏幕左侧上下滑动调节亮度
- 屏幕右侧上下滑动调节音量
- 双击左侧快退10秒，双击右侧快进10秒
- 全屏时自动暂停其他视频

### 字幕支持

- 支持格式：SRT、VTT、ASS、SSA、TTML
- 自动加载同名字幕
- 手动选择字幕文件

### 状态保存

- 自动保存窗口布局和位置
- 自动保存播放位置
- 重启APP后自动恢复

## 系统要求

- Android 7.0 (API 24) 及以上
- 支持手机、平板、折叠屏

## 支持格式

MP4、AVI、MKV、MOV、FLV 等主流视频格式

## 权限说明

| 权限 | 用途 |
|------|------|
| 存储权限 | 读取本地视频文件 |
| 悬浮窗权限 | 悬浮窗播放模式 |

## 项目结构

```
app/src/main/java/com/multivideo/player/
├── MainActivity.kt              # 主界面
├── FullscreenPlayerActivity.kt  # 全屏播放
├── adapter/
│   └── VideoPlayerAdapter.kt    # 视频列表适配器
├── model/
│   └── VideoItem.kt             # 视频数据模型
├── player/
│   └── VideoPlayerWrapper.kt    # ExoPlayer封装
└── window/
    ├── FloatingWindow.kt        # 悬浮窗
    └── FloatingWindowManager.kt # 悬浮窗管理
```

## 技术栈

- **播放器**：ExoPlayer 2.19.1
- **UI**：RecyclerView + 自定义Adapter
- **悬浮窗**：WindowManager + TYPE_APPLICATION_OVERLAY
- **手势识别**：GestureDetector

## 构建

1. 克隆项目
```bash
git clone https://github.com/spikejiejie/multivideo.git
```

2. 用 Android Studio 打开项目

3. 同步 Gradle 依赖

4. 运行到设备或模拟器

5. 自己build apk

## 使用说明

1. 启动APP后，点击右上角箭头展开菜单
2. 选择「应用内播放」或「悬浮窗播放」模式
3. 点击「添加视频」选择本地视频文件
4. 支持多选视频批量添加

### 手势操作

| 操作 | 功能 |
|------|------|
| 点击视频区域 | 隐藏/显示控制栏 |
| 左右滑动视频 | 拖动播放进度 |
| 全屏左侧滑动 | 调节亮度 |
| 全屏右侧滑动 | 调节音量 |
| 双击左侧 | 快退10秒 |
| 双击右侧 | 快进10秒 |

## 许可证

MIT License
