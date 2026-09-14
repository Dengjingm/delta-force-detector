# 建设评估与后续计划

更新日期：2026-09-14。审查基线：`ab93f11`；当前已完成 T07/T08 的 v2 帧协议黄金 fixture 与 C 端编解码 host 测试、M1 可复现构建，并下载和隔离检查 Roboflow v1 与 Ultralytics Platform 合并候选数据。v2 尚未接入 socket 传输，候选数据尚未接入单类训练。完整开发方案入口为 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)；实施顺序见 [TASKS.md](docs/plan/TASKS.md)，合同与验收分别见 [CONTRACTS](docs/plan/CONTRACTS.md) / [VALIDATION](docs/plan/VALIDATION.md)。

**结论：模块划分可以保留，但当前仍是未连通的原型脚手架。后续应从“先采集并完整训练，再编译联调”调整为“先建立构建与模型契约基线，数据建设并行；离线验证通过后接入 root 截图，再验收精度和持续性能”。**

## 现状、目标与差距

目标是红米 K70 上的单类 `enemy` 检测，覆盖近距到远距目标，向调用方提供可靠、及时的屏幕中心坐标。当前文件结构覆盖了训练、导出、采集、传输和推理，但关键接口未对齐，也没有训练产物或真机证据。因此主要差距首先是可构建、可运行和可验证，其次才是模型精度与 15fps。

| 建设面 | 已存在 | 尚未完成 / 验证 |
|---|---|---|
| 文档与导航 | 现状四文档已统一，另有完整主规划、跨端合同、三模块规格、26项任务和验收规格 | 后续实现变化时持续维护 |
| 数据与训练 | 单类 YAML、训练/可视化脚本；隔离的 Roboflow v1 与 Ultralytics 合并候选数据 | 候选数据的许可证确认、`enemy` 语义复核、来源分组与独立划分；模型加载、训练记录 |
| 模型导出 | TFLite / fp16 / int8 / NMS 导出入口 | 实际产物、环境锁定、tensor 契约、跨引擎一致性 |
| Native | 主循环、两种截图代码分支、Socket、构建脚本 | 编译、正确截图、完整写入、可靠断连与退出 |
| Android | SDK、Service、预处理、推理、后处理、下载骨架 | 编译、启动入口、权限、Binder/Flow、模型加载与生命周期 |
| 质量与交付 | 可开展静态审查 | 自动化测试、CI、APK、真机功能/精度/性能记录 |

“文件存在”“源码审查”“host 验证”“Android 构建”“真机通过”“指标达标”分别记录，不能用“代码层完成”合并这些状态。完整文件定位与调用链见 [CODEMAP.md](CODEMAP.md)。

## 已知问题与优先级

以下优先级是本项目的交付顺序：**P0 为阻断最小闭环，P1 为可靠性与评估必要条件，P2 为基线稳定后的交付能力。** B01/B02/B06 已在 M1 修复并通过构建验证；B03/B04/B05 本轮完成代码修复并通过构建，待真机核验；其余代码问题仍待修复。

| 编号 | 优先级 | 代码证据与影响 | 对应动作 / 验收 |
|---|---|---|---|
| B01 | P0 | [DetectionService](android-app/app/src/main/java/com/screen/vision/service/DetectionService.kt) 使用 `#` 注释、`onBind()` 返回 Flow、普通 suspend 方法中无接收者地使用 `isActive`；[SDK](android-app/app/src/main/java/com/screen/vision/api/ScreenVisionSDK.kt) 的独立 object 内嵌 companion object | ✅ M1 已修：`#`→`//`、`onBind()` 返回 `Binder()`、显式 `import kotlinx.coroutines.isActive`、object 内 companion 移除；`assembleDebug` 通过 |
| B02 | P0 | [socket_server.c](native-daemon/socket_server.c) 缺 `chmod` 声明头文件；[main.c](native-daemon/main.c) `%d` 接收 long long；CMake/Android.mk 缺 `dl`；[build.sh](native-daemon/build.sh) 生成带连字符的 Android raw 资源名 | ✅ M1 已修：`sys/stat.h`、FPS 统计改 `long long`+`%lld`、`-ldl`/`dl`、raw 资源名 `screen_visiond`；NDK r26d arm64 交叉编译通过（32K）；拆开纯构建与显式部署仍待办 |
| B03 | P0 | [screencap.c](native-daemon/screencap.c) 默认路径的 `getPixels` 指针从未绑定，C++ 符号/调用方式不正确；fallback 执行 `screencap -p` 却把 PNG 当 raw | ✅ 代码已修（待真机）：改用 `popen("/system/bin/screencap")` 读 raw 流，解析 12 字节头（width/height/format）→ 按 HAL format 定 bpp → 转紧密 RGBA8888 复用缓冲；NDK arm64 编译通过。raw 头/像素顺序/stride/尺寸真机行为仍未核实；ScreenshotClient 快路径未实现 |
| B04 | P0 | [export_tflite.py](training/export_tflite.py) `nms=True`；[YOLODetector](android-app/app/src/main/java/com/screen/vision/detection/YOLODetector.kt) 按 `5+nc` 猜类别并用二维数组接三维输出；[PostProcessor](android-app/app/src/main/java/com/screen/vision/detection/PostProcessor.kt) 按交错 raw/objectness 解析 | ✅ 代码已修（待真机）：YOLODetector 校验 `[1,S,S,3]`/`[1,N,6]` 并直接用 ByteBuffer；PostProcessor 按 C02 解析归一化 xyxy_score_class；真实模型 tensor/坐标单位/NMS 仍须以实际导出产物核验，同图跨引擎对齐未做 |
| B05 | P0 | [SDK](android-app/app/src/main/java/com/screen/vision/api/ScreenVisionSDK.kt) 的 `setResultSource()` 没有调用者，绑定回调只记日志；Service 仅发非空结果 | ✅ 代码已修（待真机）：新增进程级 `ResultBus`，Service 每帧发布（含空列表），`SDK.observe()` 返回同一实例，移除无调用者的 `setResultSource`；“有目标→无目标→断流→停止→重启”全周期待真机验证 |
| B06 | P0 | [Manifest](android-app/app/src/main/AndroidManifest.xml) 未声明必要的前台服务权限，声明 mediaProjection 却没有对应授权流程；无 Activity；[YOLODetector](android-app/app/src/main/java/com/screen/vision/detection/YOLODetector.kt) 在主线程创建 GPU、其他线程推理/关闭 | ✅ M1 已修（构建/启动部分）：声明 `FOREGROUND_SERVICE` + `SPECIAL_USE`、移除 mediaProjection、新增 `MainActivity`；GPU delegate 移除改 XNNPACK CPU，GPU 同线程生命周期留待 M3 重加时落实 |
| B07 | P1 | [train.py](training/train.py) 依赖未经确认的 `yolov8s-p2.pt`，只检查 YAML；[visualize.py](training/visualize.py) 标签路径多出一层 `images`；[export_tflite.py](training/export_tflite.py) 忽略返回路径，仅扫描权重同级 | 明确 P2 YAML/迁移权重来源、数据预检、正确配对与导出定位；少量样例跑通后再正式训练 |
| B08 | P1 | [帧发送端](native-daemon/socket_server.c) 忽略 stride，无短写重试与 SIGPIPE 防护；[接收端](android-app/app/src/main/java/com/screen/vision/socket/UnixSocketClient.kt) 漏 width 上限/height 下限，Int 乘法可能溢出 | 固定 LE、紧密 RGBA、安全尺寸计算；覆盖分段读写、半帧断流、慢客户端和对端关闭 |
| B09 | P1 | [SDK](android-app/app/src/main/java/com/screen/vision/api/ScreenVisionSDK.kt) 提前设 started，缺 daemon 安装/就绪与实例管理，延时绑定任务不取消；[Service](android-app/app/src/main/java/com/screen/vision/service/DetectionService.kt) 首次连接失败绕过 cleanup，无重连及 finally | 建立启动/就绪/运行/失败/停止状态；验证快速 start/stop、daemon 迟到/退出、模型加载失败后恢复 |
| B10 | P1 | [requirements.txt](training/requirements.txt) 仅版本下限；数据划分/测试集缺失；设备只选 MPS/CPU | 记录验证环境、模型/数据版本、实验配置和 seed；按来源分组划分；统一命名；按需增加 CUDA 配置 |
| B11 | P1 | 960 固定预处理、每帧多次大分配、30fps 采集与 15fps 消费；协议无采集时间戳；[main.c](native-daemon/main.c) FPS 统计不计等待且有整数乘法溢出风险 | 先测阶段耗时和端到端帧龄；规划时间戳/帧编号和最新帧策略，双端同步升级；基于证据再优化缓存和模型 |
| B12 | P2 | [ModelUpdater](android-app/app/src/main/java/com/screen/vision/update/ModelUpdater.kt) 使用占位 URL、未校验 md5；SDK 不选缓存；detector 仅支持 assets | 基线阶段不依赖远端更新；后续实现兼容性校验、版本选择、原子切换和回滚再验收 |

### 待补录问题（本轮源码复核新增，尚未并入上方 B 编号）

以下为逐行核对源码后新增的、未被 B01–B12 显式覆盖的「现状与合同不一致」证据。待对应任务实现时转正编号并并入任务追踪，当前仅作偏差留存。

| 编号 | 代码证据 | 与合同/规格的偏差 | 归属任务 |
|---|---|---|---|
| N1 | ~~[Preprocessor.kt](android-app/app/src/main/java/com/screen/vision/detection/Preprocessor.kt) `:29-33` 计算单一 `scale`，`:57-59` 将其同时存入 `scaleX=scaleY`~~ | ~~违反 C03 `scaleX=newW/W`、`scaleY=newH/H` 逐轴实际比例，且缺 `max(1,floor)` 窄图保护~~ | ✅ 已修：逐轴 `scaleX=newW/W`、`scaleY=newH/H` + `max(1,floor)`，随本轮构建通过 |
| N2 | ~~[DetectionService.kt](android-app/app/src/main/java/com/screen/vision/service/DetectionService.kt) `:61`、`:71` 返回 `START_STICKY`~~ | ~~与 ANDROID §4 要求的 `START_NOT_STICKY` 相反~~ | ✅ 已修：两处均改 `START_NOT_STICKY`，随 M1 构建通过 |
| N3 | [main.c](native-daemon/main.c) `:46,58,94,104,129` 计时用 `CLOCK_MONOTONIC` | 与 C05/NATIVE §4 要求的 `CLOCK_BOOTTIME` 不符，禁止与 Android 跨进程相减 | T14（关联 B11） |
| N4 | [requirements.txt](training/requirements.txt) 同时含 torch/ultralytics/tensorflow | 与 TRAINING §5「训练与导出环境分离」矛盾，训练环境不应含 TensorFlow | T01/T05/T06（关联 B10） |
| N5 | [.gitignore](.gitignore) 已覆盖 `incoming` 和现有 `val` 数据，仍漏规划中的 `test/review/manifests` 数据策略 | 尚未完整覆盖规划数据布局 | T03/T04（关联 B10） |
| N6 | [dataset.yaml](training/data/dataset.yaml) 仅有 `train`/`val` | 缺 `test` 划分，与 TRAINING §2 的 70/15/15 不符 | T03（关联 B10） |

版本相关证据：Ultralytics 的 [v8.3.200 exporter](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/engine/exporter.py) 和 [detection head](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/nn/modules/head.py) 展示 raw `4+nc` 与 NMS 输出的区别，以及 TFLite 坐标处理；这是本次核对的具体版本，不代表本仓库已锁定此版本。P2 架构来源参见 [官方 P2 YAML](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/cfg/models/v8/yolov8-p2.yaml)。最终仍须检查实际导出产物。

平台依据：[TFLite 2.14 tensor 输出形状检查](https://github.com/tensorflow/tensorflow/blob/v2.14.0/tensorflow/lite/java/src/main/java/org/tensorflow/lite/TensorImpl.java)、[GPU delegate 线程要求](https://developers.google.com/edge/litert/android/gpu)、[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[NDK libdl 链接要求](https://developer.android.com/ndk/guides/stable_apis#c_library)、[AOSP screencap](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/screencap/screencap.cpp)。设备 ROM 的实际行为仍需真机核实。

## K70 对方案选择的影响

用户提供：红米 K70，第二代骁龙 8、最高 3.19GHz、12GB 物理内存 + 4GB 扩展内存、3200×1440 屏幕；root 稍后进行。Android/HyperOS 版本、实际采集/截图分辨率、root 方案、GPU delegate 支持情况尚未知。

若横屏截图确为 3200×1440，缩到 960×960 LetterBox 时缩放系数为 **0.3**，有效画面约为 **960×432**，上下各填充 264 像素。原图 5–10px 目标因此仅剩约 **1.5–3px**。这解释了为何需要按输入像素大小评估远距召回，不能只靠“P2 + 960”承诺效果。

960 输入下，P2/P3/P4/P5 的理论网格为 **240/120/60/30**，P2 步长 4 指模型输入像素，不是原始屏幕像素。旧计划中的 160/80/40/20 对应 640 输入，已纠正。

3200×1440 RGBA 原帧约 **18.43MB**；15fps 与 30fps 分别约 **276MB/s**、**553MB/s** 的单向像素负载，尚不含内存复制及中间缓冲。这是容量估算，不是实测传输速度。4GB 扩展内存不能按额外物理 RAM 计入推理预算。

方案保留 YOLOv8s-P2 / 960 作为候选，并与更小 P2 模型、不同输入尺寸作精度/延迟比较。只有全图缩放的远距召回确有瓶颈时，再评估 ROI/切片及其额外延迟；不在首个闭环中同时加入多套复杂方案。

## 里程碑与验收

以验收条件推进，暂不承诺“第几天完成”或 M1/MPS 训练小时数。每个里程碑完成时记录命令、环境、产物、日志/指标与剩余限制。

| 里程碑 | 依赖与工作 | 验收条件 | 当前状态 |
|---|---|---|---|
| M0 完整规划 | 现状、目标架构、合同、模块规格、任务与验收 | 11份文档区分现状与未来；26项核心任务有依赖/产物/门槛，未知有固定解决路线 | 已完成（规划文档） |
| M1 可复现构建 | 修 B01/B02/B06 的构建与启动项；固定 JDK/Gradle/SDK/NDK，补 Wrapper、最小入口、构建检查；分离 build/deploy | NDK arm64 产物和 debug APK 实际构建通过；APK 可启动；缺模型/未 root 时清晰反馈；不把“安装成功”当作检测成功 | 构建部分完成（NDK arm64 + debug APK 通过）；安装/启动与缺模型/未 root 反馈待真机验证；不依赖 root |
| M2 数据规则与模型契约 | 可与 M1 并行；修 B07/B10，确定 P2 来源、依赖、标注规则、划分、导出检查和元数据 | 数据预检可区分缺标/空标/非法标注；无对局泄漏；模型可加载；实际导出文件及 tensor 契约可追溯 | 待实施；不依赖 root |
| M3 离线正确性与推理基准 | 依赖 M1/M2；先少量可靠样例训练/导出与 CPU 基线，再修 B04 和 GPU 生命周期 | 同组标注截图比较 PyTorch/TFLite/Android 的框、分数、中心坐标；记录误差与失败样例；K70 本地图片可推理，验证 GPU 及 CPU 回退并记录延迟 | 待实施；K70 此阶段无需 root |
| M4 root 截图与 SDK 闭环 | 依赖 M1/M3 和设备 root；修 B03/B05/B08/B09，先低频正确截图，再接模型 | 截图像素/方向/尺寸正确；SDK 实际收到结果；有目标→空帧→断流→恢复；重复 start/stop 可退出、可重启，无过期结果 | 待设备条件与代码修复 |
| M5 精度与持续性能 | 数据采集从 M2 开始持续；离线精度可在 M3 后推进；整链路验收依赖 M4；修 B11 | 固定测试集分桶指标；真机持续至少 30 分钟记录 p50/p95、帧龄、有效 FPS、内存/温升/丢帧；测后决定是否达到 15fps 或调整方案 | 待基线数据与真机证据 |
| M6 更新与交付 | 依赖稳定 M3–M5 基线；修 B12，按实际需要提供 AAR/演示 APK、发布记录与其他机型矩阵 | 正确模型切换生效；中断/哈希错误/不兼容/加载失败保留旧版；安装升级可复现；扩展设备逐台记录兼容结果 | 后置 |

依赖关系：`M0 → (M1 ∥ M2) → M3 → M4 → M5 → M6`。采集、补标和离线评估贯穿 M2–M5。M1 的设备安装和 M3 的本地图片推理不依赖 root；M4 的真实游戏采集必须等待 root 条件就绪。首阶段采用人工部署与内置模型可以先完成闭环，自动解包安装与热更新分别验收。

## 数据和精度计划的补充

1. **先固定标注语义。** 明确敌我判断依据、可见框与遮挡框范围、倒地/尸体、队友、标记图标、训练靶和无法判断小点的处理；可靠可见敌人全部标注，不因尺寸小而漏标，不凭猜测补框。
2. **建立可复核样本清单。** 记录对局/片段 ID、地图、距离或原图框尺寸、运动/光照、敌人数及不确定项；加入空场景、队友、植被、建筑等困难负样本。修复可视化配对后再抽查小框。
3. **按对局分组划分。** 建议先采用约 70/15/15 的 train/val/test 分配，样本不足时按独立对局数调整；冻结测试集。随机数据增强仅用于训练，验证/测试和部署使用一致的确定性预处理；近邻帧不能跨集合。200 张是采集起点，1000+ 由错误样例覆盖驱动。
4. **建立可比较实验。** 保存 seed、依赖环境、数据版本、模型结构/权重来源、输入尺寸、batch、阈值、训练配置与导出参数。先 smoke run，再完整训练；训练脚本需明确 CUDA/MPS/CPU 的选择与内存条件。
5. **单独评估远距目标。** 同时保存原图框尺寸和 LetterBox 后尺寸；建议按输入短边 `<4px`、`4–8px`、`8–16px`、`≥16px` 分桶。报告各桶样本数、precision/recall、每帧误检、中心误差和全量 mAP；空帧另报误检，不用全量 mAP 掩盖小目标漏检。
6. **冻结操作阈值和容差后验收。** 当前 `confidence=0.45`、`IoU=0.5` 只是代码默认；用验证集选择，再在独立测试集报告。模型导出误差、样本覆盖、各桶召回与误检门槛已在VALIDATION定稿为v1设计目标；不是实测结果。任何调整必须记录依据并同步规划，不能通过降标准伪装验收通过。
7. **量化后置比较。** 默认 fp16 建立 PyTorch→TFLite→Android 对照；int8 需要代表性校准集、实际 I/O 检查和远距召回对比，再决定是否采用。

## 真机与性能验收记录要求

- **设备记录**：K70 的 Android/ROM、root 方式、ABI、实际帧尺寸/方向、游戏图形设置、模型/数据/应用/daemon 版本、推理后端。
- **正确性与恢复**：色块/边角/行 padding、横竖屏、无目标、半帧断流、daemon 异常退出、慢消费者、锁屏/切换应用、启动失败、重复启停及资源释放。
- **测量口径**：区分采集 FPS、推理 FPS、有效结果 FPS；记录采集到结果的帧龄，而非只测 `Interpreter.run()`；p50/p95 覆盖采集、传输、预处理、推理、后处理，并另报端到端结果。
- **持续运行**：至少 30 分钟且覆盖游戏并行运行，比较开始与后段吞吐、帧龄、内存与温升。15fps 对应 66.7ms 周期；“持续 15fps”只有在有效结果速率接近目标、无持续积压且报告运行条件后才可声称。正式REALTIME门槛已在VALIDATION固定：窗口有效FPS≥14.5、帧龄p95≤150ms/p99≤250ms，并同时通过全距离精度要求。
- **结果可追溯**：保留测试输入、模型元数据、输出和日志。性能证据不足时继续标注“待验证”，不能用硬件规格替代结果。

现有帧头没有采集时间戳或 frameId，M5 前需要选择可对照的测量方案；若升级协议，应统一版本、长度、格式和时间基准并同步双端。先证实是否积压，再实现有界缓冲/最新帧策略和内存复用。

## 本轮验证记录与边界

| 检查 | 结果 | 可支持的结论 |
|---|---|---|
| 目录、Git 状态、三个模块源码与配置审查 | 已完成 | 上述现状和调用链来自仓库实际内容 |
| 3 个 Python 脚本 AST 解析 | 通过 | Python 语法可解析，不代表数据加载/训练/导出成功 |
| `bash -n native-daemon/build.sh` | 通过 | Shell 语法正确，不代表 NDK 构建通过 |
| macOS clang + 临时 Android log 声明、C11/严格警告 | 复现 `chmod` 未声明及 FPS printf 类型错误 | 证实 host 侧静态编译问题；不是 Android 交叉编译 |
| 临时 socketpair 探针，调用当前帧发送函数后关闭接收端 | 发送进程被 SIGPIPE 终止 | 证实当前重连分支无法覆盖此断连情形 |
| 本机工具与依赖探测 | 有 gcc 12.3 / clang 17.0 / CMake 3.26 / make 4.4 / Python 3.11；已装 JDK 17.0.20.1、Gradle 8.7、Android SDK(platform-34/build-tools 34.0.0/platform-tools adb 37.0.1)、NDK r26d，位于 `/data/home/jingmindeng/android/`；训练 venv `/data/home/jingmindeng/android/venv-training` | C host 与 arm64 交叉编译均可；`gradle -p android-app projects` 配置通过，Android 构建工具链就绪；训练/导出 Python 依赖（CPU）已装并 import 验证通过 |
| T07 黄金 fixture 生成 | `generate_fixtures.py` 逐字段 struct 打包与独立手写 hex 断言一致，产出 72 字节 `frame_v2_rg_2x1.bin`，SHA-256=`1e1a57f8…73fe`，写入 `manifest.json`/README | 帧协议 golden 基准已落地；长度/hash/字段期待值独立可复核 |
| T08-C v2 编解码 host 测试 | `frame_protocol.c/.h` 以 gcc C11 `-Wall -Wextra -Werror` 编译，`test_frame_protocol.c` 全绿：golden 逐字节往返、错 magic/version/headerBytes、越界尺寸/stride/format/rotation/payload/时间顺序/零 ID 均正确拒绝，UBSan 无未定义行为 | C 端 v2 编解码与校验已 host 验证；`socket_server.c` 仍为 v1、Kotlin 端未实现，接入属 T14/T08-Kotlin |
| Python 训练依赖（CPU） | venv `venv-training` 内 torch 2.14.0+cpu / torchvision 0.29.0+cpu / ultralytics 8.4.150 / tensorflow 2.21.0 / opencv-python-headless 4.11.0.86 / numpy 1.26.4 / pillow 12.3.0 / matplotlib 3.11.2 / pyyaml 6.0.3 / tqdm 4.70.1，全部 import 通过；labelimg 未装（仅 GUI）；torch 无 CUDA | CPU 训练/导出依赖就绪，尚未跑通训练或导出；numpy 锁 1.26.4 以兼容 TF，opencv 用 headless 版规避无头机缺 libGL |
| M1 构建（NDK + Android） | `build.sh` 用 NDK r26d 交叉编译 arm64 `screen-visiond`（32K）→ `res/raw/screen_visiond`；Gradle 8.7 + JDK 17 生成 Wrapper；`assembleDebug` 产出 `app-debug.apk`（arm64-only，约 9.3M） | M1 构建闭环已通；不代表可安装/检测；GPU delegate 已移除改 XNNPACK CPU，待 M3 重加 |
| Roboflow YOLO 候选集 v1 下载 | ZIP 776,596,590 bytes；SHA-256 `e9413acedd9d789ee0f7e15412126af27cd32ef7e3bda0770f08b090c0d5f634`；ZIP CRC 通过；归档中的 3 个 `data.yaml` 条目内容相同 | 已取得可追溯的隔离候选数据，并以 GitHub Release 标签 `dataset-roboflow-v1` 分发；不代表符合 `enemy` 语义 |
| 候选数据结构与标签静态检查 | train/valid/test 为 1246/357/180 张，图片与标签一一配对；3272 个框均为合法五列 YOLO 坐标；类别计数 `head=1227`、`person=2045` | 原始图像与标注文件配对完整；没有缺标、孤儿标签或非法坐标行 |
| 候选 `data.yaml` 路径检查 | 导出文件写入 `../train/images`、`../valid/images`、`../test/images`，按 YAML 所在目录解析均不存在 | 保留原文件作为来源证据；正式接入时须生成项目自己的配置，不能直接使用该 YAML |
| 候选图片解码、尺寸和精确重复检查 | 1783 张 JPEG 全部由 `djpeg` 解码通过；1782 张为 3840×2160，1 张为 3840×2100；无相同 SHA-256 图片及跨 split 精确重复 | 文件未见损坏或字节级重复；不能排除近邻帧泄漏 |
| Ultralytics 公开 YOLO 合并候选集下载 | 通过公开数据集与图片 API 分 3 页取得 14,820 条完整索引并下载全部 JPEG；本地图片总计 748,968,200 bytes，下载失败 0；索引 SHA-256 `16f6189f42f8ac85f1ea05994910db0150a785e2a7e5e79abae1a0146f69937d` | 已取得第二套隔离候选数据，并按用户要求以独立 GitHub Release 留档；页面标记 `No license`，该发布不等同于获得使用或再分发授权，也不能直接作为正式训练集 |
| Ultralytics 合并候选结构与标签检查 | train/val/test 为 11,192/3,109/519 张，图片与标签一一配对；11,131 张有标注、3,689 张为空标签；22,481 个框合法，`head=10,535`、`person=11,946`；跨 split 内容 hash 重复 0；抽样 120 张 JPEG 解码失败 0 | API 数量、字节数、类别和划分与平台一致；无非法坐标，但来源分组和近邻帧泄漏仍未证明 |
| Ultralytics 独立归档 | ZIP 为 769,492,828 bytes，SHA-256 `ebcc1cc6648738e157f1173f57e7e7f546f9616907bd32081522f47ff916d4a1`；`unzip -t` 无错误；使用标签 `dataset-ultralytics-merged-20260914`，与既有 `dataset-roboflow-v1` 分离 | Release 是候选数据快照；源页面无许可证声明，使用前仍须确认授权 |
| 两套候选关系与尺寸比较 | Ultralytics 合并集包含旧 Roboflow v1 的全部 1,783 个原始文件名 stem，另含更多来源与裁剪变体；9,766 张为 416×416、5,053 张为 640×640、1 张为 641×640，旧集则为 3840×2160/2100 | 合并集规模更大且含裁剪图，但像素分辨率更低；“更清晰”不能由原图分辨率支持，须按目标像素与标注质量抽样复核 |
| 文档路径、链接、状态与差异检查 | 11份Markdown的文件链接、标题锚点、JSON样例、代码围栏、26项任务依赖无环及 `git diff --check` 通过 | 本次提交候选数据忽略规则并同步状态文档；CLAUDE 符号链接保留，源码问题仍待修复 |

候选数据均位于被 Git 忽略的 `training/data/incoming/`。Roboflow v1 目录保留原 ZIP 和原样解压内容；Ultralytics 合并集目录保存 API 索引、下载报告及 YOLO `images/labels/{train,val,test}`。两套标签都是 `head` / `person`，来源对局分组未知，平台原划分不能作为无泄漏证据；合并集还缺少许可证声明。在完成许可确认、敌我语义复核、空标注语义确认和按对局重划分前，不接入单类 `dataset.yaml`，B07/B10 与 M2 保持未完成。

本轮新增源码：`contracts/fixtures/`（生成器+bin+manifest+README）、`native-daemon/frame_protocol.{c,h}`、`native-daemon/tests/test_frame_protocol.c`；M1 另增 `android-app/gradlew` + `gradle/wrapper/`、`MainActivity.kt`、`res/raw/screen_visiond`（arm64 二进制）。已产出 `native-daemon/build/screen-visiond` 与 `android-app/app/build/outputs/apk/debug/app-debug.apk`；未生成训练权重、模型或真机记录；ASan 因本机缺 `libclang_rt.asan` 运行库未跑（UBSan 已跑）。

下一批可继续 T03（以候选数据为输入落实数据语义、清单与复核流程）及 T08 的 Kotlin 端 `FrameHeader.kt` 编译验证；`socket_server.c` 接入 v2 属 T14。M1 构建已闭环，真机安装/启动与缺模型/未 root 反馈待设备验证。训练/导出 CPU 环境已就绪，但候选数据尚不符合单类 `enemy` 契约，不能声称训练或导出成功。

## 屏幕中心移动与结果通路实现轮（2026-09-14）

按用户指示（root/真机由用户自行验证，本阶段假设环境就绪、以代码实现为主）补齐检测链路与屏幕中心移动功能，并修复 B03/B04/B05 与 N1。本轮均只到「代码实现 + 本地构建通过」，未做真机、真实模型与真实截图验证。

| 改动 | 内容 | 验证状态 |
|---|---|---|
| `screencap.c`（B03） | `popen("/system/bin/screencap")` 读 raw 流：解析 12 字节头（width/height/format）→ 按 HAL format 定 bpp → 统一转紧密 RGBA8888 复用缓冲 | NDK arm64 交叉编译通过（`-Wall -Wextra -Werror`）；raw 头/像素顺序/stride 真机行为未核实 |
| `YOLODetector.kt`（B04） | 加载时校验输入 `[1,S,S,3]` 与输出 `[1,N,6]`，不再用 `shape[1]-5` 猜类；推理用容量/dtype 匹配的直接 ByteBuffer，返回扁平 FloatArray | assembleDebug 通过；真实模型 tensor 未核验 |
| `PostProcessor.kt`（B04） | 按 C02 解析 `x1,y1,x2,y2,score,classId` 归一化输出，score≤0 视为 padding、非有限/非法 class 判坏帧、中心落 padding 丢弃，回映原图中心并 floor+夹取 | assembleDebug 通过 |
| `Preprocessor.kt`（N1） | 按 C03 逐轴 `scaleX=newW/W`、`scaleY=newH/H`，`newW/newH=max(1,floor)`，Preprocessed 增带 newW/newH/inputSize | assembleDebug 通过 |
| `ResultBus.kt`（B05，新增） | 进程级 `MutableSharedFlow<List<DetectResult>>`，Service 每帧发布（含空列表），`SDK.observe()` 返回同一实例，移除无调用者的 `setResultSource` | assembleDebug 通过 |
| `center/`（ScreenCenterConfig/TouchInjector/ScreenCenterController） | 屏幕中心移动：选中心最近目标 → 比例增益 + 死区 + 单步上限 → `su -c input swipe` 注入拖拽；独立节拍协程与检测循环解耦 | assembleDebug 通过；注入方向/灵敏度需真机按控制方案调参 |
| `DetectionService`/`SDK`/`MainActivity` | Service 用 `detector.inputSize` 驱动预处理、发布 ResultBus、驱动 ScreenCenterController；`SDK.start(moveCenter=...)`、`observe()=ResultBus.results`；MainActivity 增屏幕中心移动按钮 | assembleDebug 通过 |

本轮构建：`build.sh`（NDK r26d arm64）产出 `screen-visiond`（36K）；`gradle -p android-app :app:assembleDebug` 通过。剩余未验证项：真实截图 raw 格式、真实模型输出张量、root 触控方向与延迟；`input swipe` 每步新建 su 进程。

## 视角平滑与真实多模态输入调研（2026-09-14）

新增 `docs/research/VIEW_CONTROL_RESEARCH.md`，将视角控制研究拆为统一时钟/坐标、真实传感器状态估计、用户意图融合、受约束平滑和可复现实验四层。文档纠正了“Fitts 定律直接导出 S 型速度曲线”的表述，以 minimum-jerk 作为点到点运动基线，并明确不采用伪造触控/IMU、人工噪声、随机过冲或以规避检测为目标的评估。

本轮只修改文档，未修改 `center/` 源码、未构建、未做真机或用户研究。后续实现从 R1 事件 schema、时间同步和标定工具开始；当前检测驱动的 root `input swipe` 仍是已知现状，不作为新研究方案的集成基线。

## 多模态时空一致性诊断基线（2026-09-14）

新增 `motion/MotionSamples.kt`、`ConsistencyModels.kt` 与 `MultimodalConsistencyAnalyzer.kt`，开始实现研究 R1/R2 的纯逻辑切片。分析器校验单调时钟、接收延迟、会话与屏幕变换代，在相机观测区间内累计真实触控并梯形积分真实陀螺仪，输出视角残差、方向一致性、峰值角速度/角加速度以及可解释 finding。非有限数值、乱序或跨 generation 数据明确返回 `INDETERMINATE`，不与异常行为混为一类。

随后新增线程安全的 `SlidingWindowConsistencyEngine`、Android `MotionDiagnosticsSession` 与 MainActivity 诊断入口。Session 使用真实 `TYPE_GYROSCOPE`、当前 Activity 收到的单一活动 pointerId 及历史触控点，完成 uptime→elapsed-realtime 时钟换算；多指按 pointerId 隔离，屏幕变换代改变时清空窗口。实际视角必须由自有渲染器或获授权遥测显式提交，采集器不会从输入反推并伪造观测。

现有 JVM 测试扩展为 8 项，新增滑动窗口延迟出报告和容量上界检查。使用临时 JDK 17 与 Android SDK 34 执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug`，41 个 Gradle task 成功，8 项测试全部通过、失败/跳过均为 0，产出约 9.0 MiB 的 `app-debug.apk`。构建仍有既存的 TFLite namespace、`aaptOptions` 弃用和 ModelUpdater 未使用参数警告；本轮未做真机传感器、触控时钟或标定验证，也未接线上判罚。

## 应用内双模态交互仿真引擎（2026-09-14）

新增 `simulation/`：`ImuPhysicsSynthesizer` 实现可复现高斯噪声、每轴 8–12Hz 微震、minimum-jerk S 曲线、5%–15% 带符号轴耦合及速度/加速度逐采样限幅；`TouchPathSynthesizer` 实现随机受限控制点的三阶贝塞尔轨迹，并按解析速度对 Pressure、Size、TouchMajor/TouchMinor 做对数调整；`SpatiotemporalConsistencyCoordinator` 实现粗细通道 smoothstep 拆分、死区滞回和原始物理陀螺仪权重；`InteractiveSimulationEngine` 统一生成时空对齐的双通道 trace。

`InAppMotionEventDispatcher` 只对调用方提供的本进程 View 按真实相对时间回放，并提供取消句柄；未调用系统 InputManager、UiAutomation、root、HAL 或内核接口，IMU 仅作为内存样本输出。MainActivity 新增 `SimulationPadView` 与显式演示按钮，可观察轨迹及 Touch/IMU 样本数，Activity 销毁时取消回放。新增 5 项仿真测试，验证 seed 复现性、角速度/角加速度上限、单轴耦合、触控端点/属性、双通道守恒与死区滞回。连同 motion 测试共 13 项全部通过；`testDebugUnitTest` 与 `assembleDebug` 再次通过。Pad 的真实时间调度与 MotionEvent 字段仍待真机/仪器化测试。
