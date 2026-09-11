# AGENTS.md

Delta Force (三角洲行动) 屏幕元素实时检测系统。在 root 过的 Android 手机上通过 YOLOv8nano 逐帧检测游戏内元素并返回中心坐标。

## Project status

Prototype phase. The full pipeline comprises: data collection → YOLOv8nano training → TFLite export → C Native daemon (SurfaceFlinger screenshot, root required) → Android Service (30fps inference loop) → SDK API. Each phase is independently buildable and testable.

## Repository layout

```
training/              macOS 训练管线
  data/
    images/train/      截图 (手动采集, 200+ 张起步)
    labels/train/      YOLO 格式标注 (.txt, class_id cx cy w h)
    dataset.yaml       类别定义 (当前 12 类, 修改 nc + names)
  train.py             YOLOv8nano 训练入口
  export_tflite.py     PyTorch → TFLite (fp16/int8)
  visualize.py         标注可视化检查 (从 dataset.yaml 读类别)

native-daemon/         C 守护进程 (root)
  screencap.c/h        SurfaceFlinger 截图 (优先 dlopen libgui, 回退 screencap 命令)
  socket_server.c/h    Unix Socket 推帧 (协议: [width:4][height:4][RGBA pixels])
  main.c               30fps 主循环 + 帧率统计
  CMakeLists.txt       CMake 构建 (arm64-v8a, API 28+)
  Android.mk           NDK 备选构建
  build.sh             NDK 交叉编译 + adb 推送

android-app/           Android SDK
  app/src/main/java/com/screen/vision/
    api/
      ScreenVisionSDK.kt      SDK 唯一入口 (start/observe/tap/stop)
    service/
      DetectionService.kt     前台 Service, 30fps 检测循环
    detection/
      YOLODetector.kt         TFLite + GPU Delegate (自动检测 numClasses)
      Preprocessor.kt         Bitmap → 640×640 LetterBox → normalized float32
      PostProcessor.kt        NMS + 模型坐标→屏幕坐标映射
    socket/
      UnixSocketClient.kt     连接 Native 守护进程接收帧
    update/
      ModelUpdater.kt         模型热更新 (远端检查 + 下载替换)
    model/
      DetectResult.kt         数据类 (elementId, x, y, confidence)
```

## Current 12 detection classes

```
0: joystick       移动摇杆
1: fire_btn       开火按钮
2: aim_btn        瞄准/开镜
3: reload_btn     换弹
4: jump_btn       跳跃
5: crouch_btn     下蹲
6: minimap        小地图
7: health_bar     血条
8: operator_skill 干员技能
9: pickup_btn     拾取/交互
10: backpack_btn  背包
11: enemy         敌人
```

## Commands

```sh
# Training (macOS)
cd training
pip install -r requirements.txt
python train.py                           # YOLOv8nano 训练
python export_tflite.py --weights runs/hok_detector/weights/best.pt  # 导出 TFLite
python visualize.py --dir data/images/train  # 标注检查

# Native daemon (need Android NDK r26+)
cd native-daemon
./build.sh                                # 编译 + adb 推送
adb shell su -c /data/local/tmp/screen-visiond &  # 启动守护进程

# Android app (Android Studio)
# open android-app/ → Build → Run
```

## Performance budget (30fps target)

| Stage | Budget | Implementation |
|-------|--------|----------------|
| Capture | ~8ms | SurfaceFlinger via Native daemon |
| Socket transfer | ~2ms | Unix Socket, writev zero-copy |
| Preprocess | ~2ms | 640×640 LetterBox, RGBA→float32 |
| TFLite inference | ~15ms | GPU Delegate (fallback XNNPACK CPU) |
| NMS + coord mapping | ~2ms | IoU ≥0.5 filter, de-letterbox |
| **Total** | **~29ms** | **30fps ✅** |

## Conventions

- **YOLO labels**: normalized `class_id cx cy w h` (0–1 range), one box per line
- **Class order must match**: `dataset.yaml` names order = PostProcessor classNames order = TFLite model class output order. Changing one requires changing all three.
- **TFLite export**: always export with `nms=True` (built-in NMS). Prefer fp16 over int8 unless frame budget is tight.
- **Socket protocol**: `[width:4LE][height:4LE][RGBA:width*height*4]` — big-endian header, native-endian pixels. Modifying protocol requires updating both `socket_server.c` and `UnixSocketClient.kt`.
- **Daemon lifecycle**: daemon is started/stopped by `ScreenVisionSDK`. Kill dangling daemons with `killall screen-visiond && rm -f /data/local/tmp/screen-vision.sock`.
- **Root dependency**: all paths in `/data/local/tmp/`. Daemon needs `su`. Taps need `su -c input tap`. No root = no detection.
- **Model fallback**: APK bundles `model.tflite` in assets. `ModelUpdater` downloads new versions to `filesDir/models/`. Server returns `{version, url, md5}`.
- **Training data scaling**: 200 images to start, target 1000+ per class for production. Collect across varied maps/lighting/loadouts.
- **Keep comments local**: do not restate code. explain WHY not WHAT.

## Editing these instructions

Edit `AGENTS.md` directly. Keep each section self-contained. Add new detection classes to both `dataset.yaml` and the table above. When the TFLite model changes, update `export_tflite.py` defaults and verify `DetectionService.DEFAULT_CLASS_NAMES` matches.