# CODEMAP — 代码导航与接口边界

核对日期：2026-09-15。基于当前仓库源码；这是实现地图，不是功能验收证明。当前缺陷与进度见 [PROGRESS.md](PROGRESS.md)，完整未来设计见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)，协作规则见 [AGENTS.md](AGENTS.md)。

## 1. 范围与入口

目标是 root Android 上的单类 `enemy` 检测，训练候选为 YOLOv8s-P2 / 960 输入，检测目标为 15fps。当前候选数据为 Ultralytics Platform 合并集（`head` / `person` 分开存放于 `training/data/head_body/`），尚无批准的正式训练数据、权重、TFLite 资产或端到端运行记录。

| 要处理的任务 | 首先阅读 | 同步检查 |
|---|---|---|
| 类别、数据和训练 | [dataset.yaml](training/data/dataset.yaml)、[train.py](training/train.py) | 标注工具、导出配置、Service 默认类别 |
| 模型导出与加载 | [export_tflite.py](training/export_tflite.py)、[YOLODetector.kt](android-app/app/src/main/java/com/screen/vision/detection/YOLODetector.kt) | Preprocessor、PostProcessor、模型资产和更新契约 |
| 截图和帧格式 | [screencap.c](native-daemon/screencap.c)、[screencap.h](native-daemon/screencap.h) | Socket 两端、像素格式、stride、旋转 |
| Socket 协议 | [socket_server.c](native-daemon/socket_server.c)、[UnixSocketClient.kt](android-app/app/src/main/java/com/screen/vision/socket/UnixSocketClient.kt) | main 循环、取消/重连、最大帧尺寸 |
| v2 帧协议编解码 | [frame_protocol.c](native-daemon/frame_protocol.c)、[frame_protocol.h](native-daemon/frame_protocol.h)、[contracts/fixtures](contracts/fixtures/README.md) | 64 字节 LE 头、校验、黄金字节；尚未接入 socket_server |
| 生命周期与结果订阅 | [ScreenVisionSDK.kt](android-app/app/src/main/java/com/screen/vision/api/ScreenVisionSDK.kt)、[DetectionService.kt](android-app/app/src/main/java/com/screen/vision/service/DetectionService.kt)、[ResultBus.kt](android-app/app/src/main/java/com/screen/vision/api/ResultBus.kt) | Manifest、daemon 就绪与退出、结果通路 |
| 屏幕中心移动 | [ScreenCenterController.kt](android-app/app/src/main/java/com/screen/vision/center/ScreenCenterController.kt)、[TouchInjector.kt](android-app/app/src/main/java/com/screen/vision/center/TouchInjector.kt)、[ScreenCenterConfig.kt](android-app/app/src/main/java/com/screen/vision/center/ScreenCenterConfig.kt) | 最近人 + 大小框 80/20、粘滞跟踪、注入方向/灵敏度、DetectionService 接入；图片测试用 VirtualReticleInjector |
| 多模态一致性诊断 | [MultimodalConsistencyAnalyzer.kt](android-app/app/src/main/java/com/screen/vision/motion/MultimodalConsistencyAnalyzer.kt)、[SlidingWindowConsistencyEngine.kt](android-app/app/src/main/java/com/screen/vision/motion/SlidingWindowConsistencyEngine.kt)、[MotionDiagnosticsSession.kt](android-app/app/src/main/java/com/screen/vision/motion/MotionDiagnosticsSession.kt) | 真实触控/IMU/独立相机观测、设备标定、时间与坐标 generation |
| 应用内双模态仿真 | [InteractiveSimulationEngine.kt](android-app/app/src/main/java/com/screen/vision/simulation/InteractiveSimulationEngine.kt)、[ImuPhysicsSynthesizer.kt](android-app/app/src/main/java/com/screen/vision/simulation/ImuPhysicsSynthesizer.kt)、[TouchPathSynthesizer.kt](android-app/app/src/main/java/com/screen/vision/simulation/TouchPathSynthesizer.kt) | 确定性配置/seed、自有 View 回放、与系统输入和传感器隔离 |
| 坐标与置信度 | [Preprocessor.kt](android-app/app/src/main/java/com/screen/vision/detection/Preprocessor.kt)、[PostProcessor.kt](android-app/app/src/main/java/com/screen/vision/detection/PostProcessor.kt) | 模型输出单位、DetectResult、原图尺寸 |
| 模型更新 | [ModelUpdater.kt](android-app/app/src/main/java/com/screen/vision/update/ModelUpdater.kt) | SDK 选择路径、detector 加载方式、回滚 |
| 构建与打包 | [CMakeLists.txt](native-daemon/CMakeLists.txt)、[build.sh](native-daemon/build.sh)、[app/build.gradle.kts](android-app/app/build.gradle.kts) | 根 Gradle 配置、NDK、二进制资源路径 |

## 2. 当前文件树

下面只列已存在文件；“预期产物”另列，避免把计划目录写成已完成。

```text
.
├── AGENTS.md                         Agent 协作规则
├── CLAUDE.md -> AGENTS.md             同一规则的符号链接
├── CODEMAP.md                        本文件：代码、调用链、当前契约
├── IMPLEMENTATION_PLAN.md            完整目标规格入口（待实现）
├── docs/plan/                        CONTRACTS/TRAINING/NATIVE/ANDROID/TASKS/VALIDATION
├── docs/research/                    独立研究方案；不代表现有实现
├── README.md                         项目入口与当前使用条件
├── PROGRESS.md                       建设评估、缺陷、里程碑与验证记录
├── .gitignore                        数据与构建产物忽略规则（尚不完整）
├── contracts/
│   └── fixtures/                     T07 黄金帧基准：README + 生成器 + .bin + manifest
├── training/
│   ├── train.py                      训练入口 train()
│   ├── remap_and_prepare.py          默认保留 0=head/1=person，写出 data/head_body/；--merge-enemy 才合成 enemy
│   ├── export_tflite.py              导出入口 export_tflite()
│   ├── visualize.py                  标注显示与坐标反归一化
│   ├── requirements.txt              Python 依赖下限，未锁定环境
│   └── data/
│       ├── dataset.yaml              nc=1, names={0: enemy}（进行中的单类训练仍用这套）
│       ├── head_body/dataset.yaml    nc=2, names={0: head, 1: person}；images/labels git 忽略
│       └── incoming/                    Git 忽略的候选数据区
│           └── ultralytics-yolo-candidate-2026-09-14/
│                                        公开 YOLO 候选集；14,820 张；Roboflow v1 本地副本已删
├── native-daemon/
│   ├── main.c                        进程入口、30fps 目标循环、信号与统计
│   ├── screencap.c / screencap.h      截图后端与 FrameBuffer
│   ├── socket_server.c / .h          单客户端 AF_UNIX 流式传输（仍 v1，未接 frame_protocol）
│   ├── frame_protocol.c / .h         v2 64 字节 LE 编解码与校验（host 已测，未接入）
│   ├── control_server.c / .h         App→daemon 反向控制 socket
│   ├── input_inject.c / .h           uinput 虚拟触控；失败回退 exec input
│   ├── tests/test_frame_protocol.c   frame_protocol host 测试（gcc C11 严格警告 + UBSan）
│   ├── CMakeLists.txt                C11、严格警告；Android/log/dl 链接
│   ├── Android.mk                    备选配置；未固定 ABI/API；-llog -landroid -ldl
│   └── build.sh                      arm64/API28 构建、复制资源为 screen_visiond、发现设备自动推送
└── android-app/
    ├── gradlew / gradlew.bat         Gradle Wrapper 入口（8.7）
    ├── gradle/wrapper/               wrapper jar + properties（gradle-8.7-bin）
    ├── settings.gradle.kts           仓库和 :app；工程名为 yolo-research
    ├── build.gradle.kts              AGP 8.2.0 / Kotlin 1.9.20
    ├── gradle.properties             Gradle、AndroidX 配置
    └── app/
        ├── build.gradle.kts          application 模块；min 28 / target 34 / JDK 17
        └── src/main/
            ├── AndroidManifest.xml  Application、Activity、前台服务声明
            ├── res/raw/screen_visiond  arm64 daemon 二进制（NDK 交叉编译产物）
            └── java/com/screen/vision/
                ├── VisionApp.kt                 仅保存 Application 实例
                ├── MainActivity.kt              打开即拉起前台服务：全屏检测 + 屏幕中心移动；切走后继续跑
                ├── testbench/ImageTestActivity.kt 选图检测，模拟镜头把锁定目标送到中心（不注入系统触控）
                ├── api/ScreenVisionSDK.kt       start(moveCenter) / observe / tap / swipe / stop
                ├── api/ResultBus.kt             进程级结果总线（Service→SDK/屏幕中心移动）
                ├── service/DetectionService.kt  收帧、推理、发布 ResultBus；瞄准走独立最新槽
                ├── detection/YOLODetector.kt    C02 契约推理；专用线程 GPU，失败回退 XNNPACK CPU
                ├── detection/Preprocessor.kt    C03 逐轴 LetterBox、复用画布/ByteBuffer
                ├── detection/PostProcessor.kt   C02 归一化 xyxy_score_class → 原图中心
                ├── center/ScreenCenterConfig.kt 屏幕中心移动参数
                ├── center/TouchInjector.kt      daemon 不可用时的 su 回退注入
                ├── center/VirtualReticleInjector.kt 图片测试准星，不注入系统触控
                ├── center/ScreenCenterController.kt 最近人、大小框 80/20、增益+死区+步长
                ├── motion/MotionSamples.kt      真实触控/陀螺仪/相机事件与设备标定模型
                ├── motion/ConsistencyModels.kt  诊断配置、finding、指标与报告
                ├── motion/MultimodalConsistencyAnalyzer.kt 窗口对齐、积分、残差与运动学诊断
                ├── motion/SlidingWindowConsistencyEngine.kt 线程安全、有界的三通道滑动窗口
                ├── motion/MotionDiagnosticsSession.kt Activity触控与硬件陀螺仪实时采集
                ├── simulation/InteractiveSimulationEngine.kt 双通道仿真统一入口
                ├── simulation/ImuPhysicsSynthesizer.kt S曲线、噪声、微震、耦合与运动学限幅
                ├── simulation/TouchPathSynthesizer.kt 贝塞尔轨迹与动态接触属性
                ├── simulation/SpatiotemporalConsistencyCoordinator.kt 粗细通道、死区与Alpha协调
                ├── simulation/InAppMotionEventDispatcher.kt 仅向本进程指定View实时回放
                ├── simulation/SimulationPadView.kt Activity内自有触控回放画布
                ├── socket/UnixSocketClient.kt   读满帧头和像素，创建 Bitmap
                ├── update/ModelUpdater.kt       查询版本、下载缓存
                └── model/DetectResult.kt        elementId / x / y / confidence
```

无 Root 目录 `android-app-noroot/` 已按 2026-09-16 用户要求删除；后续只维护 Root 版 `android-app/`（daemon 截图 + su/uinput 注入，不走无障碍）。

Gradle Wrapper（8.7）与 `MainActivity` 诊断入口已落地，debug APK 可构建（M1）。`remap_and_prepare.py` 默认把 Ultralytics incoming 候选集整理为 `data/head_body/` 的 `0:head` / `1:person`，不再合成 `enemy`。正在使用的单类 `data/images`+`data/labels` 未覆盖。Roboflow v1 本地副本已删。这仍不是正式语义复核。

## 3. 调用链与断点

### 3.1 离线训练到模型资产

```text
经 `enemy` 语义复核并按来源分组的 YOLO 标注（缺失；incoming 候选不可直接替代）
  → dataset.yaml
  → train.py:train()
      YOLO("yolov8s-p2.pt")                ← 来源/可加载性待验证
      MPS 可用则 MPS，否则 CPU             ← 没有 CUDA 分支
      960 / batch 16 / 150 epochs
  → training/runs/yolo_research/weights/best.pt（预期，尚无产物）
  → export_tflite.py:export_tflite()
      format=tflite / imgsz=960 / nms=True
      默认 half=True；--int8 时 int8=True
  → 导出工具实际返回路径（脚本尚未消费）
  → Android assets/model.tflite（尚未复制/集成）
```

`visualize.py` 辅助读取标签，但目前把 `data/images/train/x.jpg` 映射到 `data/images/labels/train/x.txt`；正确配对位置应是 `data/labels/train/x.txt`。脚本仅抽样显示，不承担完整数据集校验。

### 3.2 在线帧与检测结果

```text
调用方 / MainActivity.onCreate → ScreenVisionSDK.start(appContext, classNames, modelPath, moveCenter=true)
  ├─ 后台 launchDaemon()：从 res/raw/screen_visiond 解到 filesDir，su cp 到 /data/local/tmp，nohup 拉起
  │    main() → screencap_init() → socket_server_init() → accept()
  │      循环：screencap_capture() → socket_server_send_frame() → 节流
  │      screencap_capture() 用 popen 调 screencap 读 raw 流（12 字节头→RGBA8888）
  ├─ ModelUpdater.checkAndUpdate()         ← 更新结果未用于选择加载路径
  ├─ startForegroundService(intent)        ← 前台服务，切到其他应用后仍采集整屏
  │    onCreate() → 通知、Socket
  │    onStartCommand() → initModel() → YOLODetector / Preprocessor(detector.inputSize) / PostProcessor
  │      runDetectionLoop() → connect 短重试 → 就绪后再挂 ScreenCenterController
  │        → ResultBus.publish(detections) → centerController?.onFrame(...)
  └─ observe() 返回 ResultBus.results（进程级单例，Service 发布、调用方订阅）
       （SDK 仍保留延时 bindService，但结果不再经 Binder 转发，Binder 路径已冗余）
```

`setResultSource()` 存在但没有调用点。`stop()` 会解绑、停止 Service 并执行 `killall`，没有保存和回收结果收集任务；启动链路也没有 daemon 安装解包、就绪握手或可靠的实例管理。

`tap()` / `swipe()` 是独立的 root shell 调用。`moveCenter` 开启时，ScreenCenterController 按检测结果移动屏幕中心。

## 4. 跨模块契约：当前事实与修复边界

### 4.1 类别与数据

唯一类别为 `0: enemy`。标签每行 `class_id cx cy w h`，坐标相对于原始截图归一化。`dataset.yaml` 与 `DetectionService.DEFAULT_CLASS_NAMES` 当前一致；SDK 的调用方仍必须显式传入类别列表。

训练产物目录目前是 `yolo_research`。重命名时同时修改训练保存位置、导出默认权重路径和使用说明。不要为了让路径名好看而把未发生的代码变更写入地图。

### 4.2 帧传输

| 项目 | 当前实现 |
|---|---|
| 地址/连接 | `/data/local/tmp/screen-vision.sock`，AF_UNIX / SOCK_STREAM / 单客户端 |
| 帧头 | 两个连续 `uint32_t`，width 后 height；C 直接写主机字节序，Kotlin 用 `nativeOrder()` |
| arm64-v8a 上的实际字节序 | little-endian，即 `[width:4LE][height:4LE]`；不存在 big-endian 编码 |
| 负载假设 | `width * height * 4` 字节，紧密排列的 RGBA；没有格式/stride 字段 |
| 缓冲区所有权 | `FrameBuffer.pixels` 由截图后端持有，发送方借用；不能擅自跨帧异步持有 |
| 当前缺失 | magic/version、帧编号、采集时间戳、旋转、完整尺寸校验、短写重试和超时 |

`frame_protocol.c/.h` 已实现 C01 的 64 字节 v2 编解码与校验（T07/T08-C，host 严格警告 + UBSan 通过，黄金字节与 `contracts/fixtures/frame_v2_rg_2x1.bin` 逐字节一致），但 `socket_server.c` 尚未迁移，线上仍发送上表的 v1 帧头。接入 v2 属于 T14，须同时替换 Kotlin 读取端并拒绝旧协议。

`FrameBuffer.stride` 已定义但发送端忽略。`writev` 只是聚集写入，不能据此称为零拷贝。源码注释中的 PING/PONG 没有实现。任何帧头/像素布局升级都必须同时修改 C 发送端与 Kotlin 读取端，并验证旧协议如何拒绝或迁移。

### 4.3 模型输入、输出与坐标

| 环节 | 当前假设 | 必须核实/修复 |
|---|---|---|
| Preprocessor | C03：居中 LetterBox，填充 114，逐轴 scale，`max(1,floor)`，RGB/255 float32 NHWC；画布/像素/输入缓冲实例内复用 | 由 `detector.inputSize` 驱动；resize 用 Android bilinear，与 Ultralytics resize 的算子级差异待黄金输入固定容差 |
| YOLODetector | 专用线程 AUTO：GPU delegate 失败回退 CPU；加载时校验 `[1,S,S,3]`/`[1,N,6]` 或 raw 5 列；输出 ByteBuffer 复用 | 以真实导出 tensor 核验 shape/dtype/量化/NMS；K70 GPU 可用性未测 |
| PostProcessor | C02：解析归一化 `x1,y1,x2,y2,score,classId`，score≤0 padding、中心落 padding 丢弃，回映原图中心 | 真实模型坐标单位/NMS 状态仍需核验；坏输出判帧失败的诊断路径未接 SDK 错误码 |
| 坐标回映 | `(x-left)/scaleX`、`(y-top)/scaleY`，`floor` 后夹取 `[0,W-1]×[0,H-1]` | 横竖屏、奇数 padding、越界、纯 padding 的边界覆盖待真机 |
| DetectResult | 类别名、中心 x/y、置信度 | 无 bbox、frameId、时间戳或稳定目标 ID；`elementId` 是类别而非实例 ID |

导出 fp16 权重不代表输入 tensor 为 fp16；int8 选项也不能证明模型 I/O 类型。先检查真实模型的 shape、dtype、量化参数、输出布局、坐标单位、类别和 NMS 状态，再实现解析。当前仓库没有可宣称为正式契约的模型文件。

### 4.4 更新、权限与资源路径

| 路径/配置 | 当前用途与状态 |
|---|---|
| `/data/local/tmp/screen-visiond` | SDK 启动的设备二进制；依赖人工/脚本部署 |
| `/data/local/tmp/sv_frame.raw` | 已移除：screencap 改由 `popen` 读 stdout，不再落临时文件 |
| `native-daemon/build/screen-visiond` | NDK arm64 构建产物（M1 已生成，约 32K） |
| `android-app/app/src/main/res/raw/screen_visiond` | build.sh 的复制目标（M1 已生成，资源名已去除连字符） |
| `android-app/app/src/main/assets/model.tflite` | 预期内置模型；不存在 |
| `context.filesDir/models/{model.tflite,version.txt}` | ModelUpdater 缓存；不是 `/data/local/tmp/` |
| `{version,url,md5}` | 当前远端接口草案；默认 `.example.com` 地址为占位，md5 参数未校验 |
| Gradle / Manifest | minSdk=28（Android 9），targetSdk=34；已声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`，Service `foregroundServiceType=specialUse`；不再声明 mediaProjection（root 采集方案不需要） |

`getModelPath()` 可以返回下载文件的绝对路径，但 SDK 未使用它，detector 也只支持 assets；因此当前不具备有效热更新。root 进程、普通 App UID、文件权限和 SELinux 访问应在同一目标设备上联调，不能由 `chmod` 成功推断可连接。

## 5. 地图维护

新增/移动模块时更新文件树与“首先阅读”表；改接口时更新调用链和契约。地图描述当前代码，拟建接口维护在 [CONTRACTS.md](docs/plan/CONTRACTS.md) 和模块规格中，任务与验收分别维护在TASKS/VALIDATION；PROGRESS只记录实际进展。缺陷修复并验证后，再把这里的断点描述改为实际行为，避免文档提前宣布完成。

规划已定稿但未实现的主要变化：带sidecar的NMS模型包、六态VisionSnapshot、双运行profile、校准配置导入、精确daemon所有权和原子模型更新。64字节v2帧头的编解码/校验（C 端 `frame_protocol.c/.h` + 黄金 fixture）已按 T07/T08-C 落地并通过 host 测试，但尚未接入 `socket_server.c`/Kotlin 接收端，线上仍为旧 v1 协议；不得把目标规格直接改写为本图的现有行为。

视角控制的后续研究入口为 [VIEW_CONTROL_RESEARCH.md](docs/research/VIEW_CONTROL_RESEARCH.md)。`motion/` 已实现离线分析、有界滑动窗口、Activity 触控和硬件陀螺仪采集；独立视角观测须由自有渲染器或获授权遥测调用 `submitViewOrientation` 提交。该能力不读取其他 App 触控。演示 APK 的实时路径是 DetectionService 全屏截图 + `center/` 注入，打开主界面即自动启动，不依赖本 App 留在前台。

应用内仿真引擎见 [INTERACTIVE_SIMULATION_ENGINE.md](docs/research/INTERACTIVE_SIMULATION_ENGINE.md)。`simulation/` 只生成内存样本并向调用方传入的本进程 View 回放；它未连接 `center/`、DetectionService、系统 InputManager、UiAutomation、root 或传感器 HAL。
