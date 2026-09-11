# Delta Force Screen Vision — 进度文档

## 一、项目概述

三角洲行动 (Delta Force) 局内元素实时检测系统。在 root 过的 Android 手机上运行，通过 YOLOv8nano 以 30fps 检测屏幕中的游戏元素并返回中心坐标，支持通过 root 权限注入触控事件。

---

## 二、架构总览（来自 Grill-Me 决策树）

### 2.1 决策树

```
目标类型 ── 三角洲行动 (Delta Force) FPS 游戏元素
               │
帧率要求 ── 30fps (33ms/帧)
               │
权限模型 ── Root（全部能力）
  ├─ SurfaceFlinger Native 守护进程截图 (minicap 思路, C 实现)
  ├─ Unix Socket 推帧到 Java 层
  └─ Root 注入触控事件 (su -c input tap)
               │
推理引擎 ── TFLite + GPU Delegate
  ├─ GPU 可用 → GPU Delegate (最快 ~15ms)
  └─ GPU 不可用 → XNNPACK CPU 回退
               │
模型架构 ── YOLOv8nano
  ├─ 输入尺寸: 640×640 LetterBox
  ├─ 输出: [1, 4+1+numClasses, numDetections]
  └─ NMS: IoU ≥ 0.5, 置信度 ≥ 0.45
               │
训练环境 ── macOS (CPU/MPS)
               │
SDK 形态 ── Android 前台 Service
  ├─ 前台通知保活
  ├─ Kotlin SharedFlow 异步发射结果
  └─ 调用方 observe() 订阅
               │
模型更新 ── 内置 APK assets + 远端热更新
```

### 2.2 四层架构

```
┌─────────────────────────────────────────────────────┐
│ Layer 4: SDK API                                    │
│ ScreenVisionSDK                                     │
│                                                      │
│   start(context, classNames)   启动检测引擎          │
│   observe() → Flow<DetectResult>  订阅检测结果       │
│   tap(x, y)                   Root 触控点击          │
│   swipe(x1,y1,x2,y2)         Root 触控滑动          │
│   stop(context)               停止检测               │
├─────────────────────────────────────────────────────┤
│ Layer 3: Android Service                            │
│ DetectionService (前台 Service)                      │
│                                                      │
│   30fps 检测主循环:                                   │
│   ┌─────────┐  ┌──────────┐  ┌───────────┐         │
│   │ Socket  │→ │ TFLite   │→ │ PostProc  │         │
│   │ 收帧     │  │ 推理      │  │ NMS+映射   │         │
│   └─────────┘  └──────────┘  └───────────┘         │
│        ↓              ↓              ↓               │
│   ~2ms            ~15ms           ~2ms              │
│                                                      │
│   保活: 前台通知 + START_STICKY                       │
│   协程: Dispatchers.Default + SupervisorJob           │
├─────────────────────────────────────────────────────┤
│ Layer 2: Native Daemon (C, root)                    │
│ screen-visiond                                       │
│                                                      │
│   截帧 → 推帧 循环:                                   │
│   ┌──────────────┐  ┌──────────────────────────┐    │
│   │ SurfaceFlinger│→ │ Unix Socket Server       │    │
│   │ Screenshot    │  │ (逐帧 push 到 Java 层)    │    │
│   └──────────────┘  └──────────────────────────┘    │
│        ↓                       ↓                     │
│   ~8ms                    ~2ms                       │
│                                                      │
│   通信协议:                                           │
│   [4bytes: width][4bytes: height][RGBA pixels]       │
│   Socket: /data/local/tmp/screen-vision.sock          │
│   保活: 客户端断连自动等待重连                          │
│   崩溃恢复: Android 层 killall + 重启                  │
├─────────────────────────────────────────────────────┤
│ Layer 1: System/Root                                 │
│                                                      │
│   su -c /data/local/tmp/screen-visiond &             │
│   adb push → chmod 755 → launch                      │
│                                                      │
│   依赖:                                              │
│   - Android 10+ (API 28+)                            │
│   - Root 权限 (Magisk / KernelSU)                    │
│   - SurfaceFlinger 可访问 (libgui.so)                 │
│   - GPU Delegate: OpenCL/OpenGL ES 3.1+              │
└─────────────────────────────────────────────────────┘
```

### 2.3 数据流 (30fps, 每帧 ~33ms)

```
┌──────┐   Socket    ┌──────────┐   ByteBuffer   ┌──────────┐
│ Daemon │───RGBA──→│  Kotlin   │───float32────→│  TFLite  │
│ (C)    │  ~2ms    │ Service   │   ~2ms preproc │  GPU Inf │
└──────┘           └──────────┘                 └──────────┘
                                                     │ ~15ms
┌──────┐   List<     ┌──────────┐   float[][]        │
│  SDK  │←─Result──│ PostProc │←────────────────────┘
│ Flow  │  ~2ms    │  NMS     │
└──────┘           └──────────┘
```

---

## 三、已完成工作

### Phase 0 — 项目脚手架 ✅

| 文件 | 状态 |
|------|------|
| `AGENTS.md` + `CLAUDE.md` symlink | 已完成 |
| `README.md` | 已完成 |
| 完整目录结构 | 已完成 |

### Phase 1 — 训练管线 (代码层完成，数据层未开始)

| 文件 | 功能 | 行数 |
|------|------|------|
| `training/train.py` | YOLOv8nano 训练入口 (MPS/CPU 自适应) | ~60 |
| `training/export_tflite.py` | PyTorch → TFLite 导出 (fp16/int8) | ~45 |
| `training/visualize.py` | 标注可视化检查 (从 dataset.yaml 动态读取) | ~90 |
| `training/data/dataset.yaml` | 12 类元素定义 | 16 |

### Phase 2 — Native 守护进程 (代码层完成，未编译)

| 文件 | 功能 | 行数 |
|------|------|------|
| `native-daemon/main.c` | 30fps 主循环 + 帧率统计 + 断连重连 | ~160 |
| `native-daemon/screencap.c` | SurfaceFlinger dlopen 方案 + screencap 回退 | ~170 |
| `native-daemon/socket_server.c` | Unix Socket Server, writev 零拷贝 | ~120 |
| `native-daemon/CMakeLists.txt` | CMake arm64 构建配置 | ~25 |
| `native-daemon/Android.mk` | NDK 备选构建 | ~12 |
| `native-daemon/build.sh` | 一键编译 + adb 推送 | ~40 |

### Phase 3 — Android Service (代码层完成，未编译)

| 文件 | 功能 | 行数 |
|------|------|------|
| `api/ScreenVisionSDK.kt` | SDK 唯一入口 (start/observe/tap/stop) | ~120 |
| `service/DetectionService.kt` | 前台 Service, 30fps 检测循环 | ~155 |
| `detection/YOLODetector.kt` | TFLite + GPU Delegate, 自动推导 numClasses | ~80 |
| `detection/Preprocessor.kt` | Bitmap → 640×640 LetterBox → normalized float32 | ~70 |
| `detection/PostProcessor.kt` | NMS + 模型→原始坐标映射 | ~115 |
| `socket/UnixSocketClient.kt` | LocalSocket AF_UNIX 连接守护进程 | ~80 |
| `update/ModelUpdater.kt` | 远端模型检测 + 下载替换 | ~115 |
| `model/DetectResult.kt` | 数据类 (elementId, x, y, confidence) | ~17 |
| `VisionApp.kt` | Application 入口 | ~15 |
| `AndroidManifest.xml` | 前台 Service 声明 + 权限 | ~17 |
| `build.gradle.kts` | TFLite + GPU Delegate + Coroutines 依赖 | ~50 |

---

## 四、后续待建设

### Phase 1 — 数据采集与训练 (第 1-3 天)

| 任务 | 说明 | 预估 |
|------|------|------|
| 截取三角洲行动局内截图 | 不同地图/模式/光照, 200-500 张 | 2h |
| 用 LabelImg 标注 12 类元素 | 每张图标注可见元素, 不去标不可见的 | 4-6h |
| 运行 `python train.py` | M1 MPS 约 20min/80epochs, CPU 约 1-2h | 1-2h |
| 导出 TFLite 并放入 assets | `export_tflite.py` → `android-app/app/src/main/assets/model.tflite` | 10min |
| 训练数据检查与补标 | `visualize.py` 检查, 补漏标/误标 | 1h |

### Phase 2 — 编译 Native 守护进程 (第 3-5 天)

| 任务 | 说明 | 预估 |
|------|------|------|
| 安装 Android NDK r26+ | 通过 Android Studio SDK Manager | 30min |
| 编译 `screen-visiond` | `cd native-daemon && ./build.sh` | 10min |
| adb 推送 + root 启动 | `adb push && adb shell su -c ...` | 5min |
| 验证截图 + Socket 通信 | 看 logcat `sv-main` `sv-socket` | 30min |

### Phase 3 — 编译 Android App (第 5-6 天)

| 任务 | 说明 | 预估 |
|------|------|------|
| Android Studio 打开 android-app/ | 等待 Gradle sync + 下载依赖 | 10min |
| 编译 debug APK | 确保 tflite + gpu 依赖正确 | 10min |
| 安装到设备 + 授权前台通知 | `adb install` | 5min |
| 验证 30fps 检测循环 | logcat `DetectionService` 检查帧率 | 30min |

### Phase 4 — 集成联调 (第 6-8 天)

| 任务 | 说明 | 预估 |
|------|------|------|
| 端到端跑通: 守护进程→Socket→推理→结果 | 全链路验证 | 2h |
| 模型精度调优: 收集 bad case → 增量标注 → 继续训练 | 迭代优化 | 持续 |
| 不同手机上兼容性测试 | 不同 SoC / Android 版本 | 持续 |
| GPU Delegate 回退测试 | 在不支持 GPU 的设备上验证 CPU 推理 | 1h |

### Phase 5 — 模型热更新 (第 8-10 天)

| 任务 | 说明 | 预估 |
|------|------|------|
| 搭建模型更新 API 服务 | 任意 HTTP 服务器, 返回 `{version, url, md5}` | 2h |
| 集成 `ModelUpdater.checkAndUpdate()` | SDK 启动时异步调用 | 30min |
| 游戏版本适配流程 | 大版本更新 UI → 标注 → 推送新模型 | 持续 |

---

## 五、已知风险

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| SurfaceFlinger API 随 Android 版本变化 | 截图失败 | screencap.c 内置 fallback 方案 (`screencap -p` 命令) |
| GPU Delegate 部分机型不支持 | 掉帧到 10-15fps | 自动回退 XNNPACK CPU |
| 游戏大版本更新 UI 布局 | 模型全部失效 | ModelUpdater 热更新机制 |
| Root 权限非普遍可用 | 无法启动守护进程 | 项目定位即为 Root 方案, 不妥协 |
| 游戏截图可能触发安全检测 | 账号封禁风险 | **用户自行承担风险, 项目仅提供技术方案** |
| 后台 Service 被系统杀死 | 检测中断 | 前台通知保活 + START_STICKY 重启 |

---

## 六、文件统计

```
总计 28 个文件
├─ 训练管线:    5 文件  (~210 行)
├─ Native 守护:  8 文件  (~530 行)
├─ Android SDK:  12 文件 (~830 行)
└─ 项目文档:    3 文件  (~370 行)
```

---

*最后更新: 2026-09-11*
*状态: 脚手架完成, 等待 Phase 1 数据采集与训练*