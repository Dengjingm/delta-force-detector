# 完整实施规划 v1.0

规划日期：2026-09-14。对象：红米 K70 上的三角洲行动单类敌方干员检测。**本文及配套规格全部是待开发设计；本轮仅编写文档，未修改源码、构建、训练或部署。**

本计划决定开发时采用的默认方案、跨端接口、失败处理和交付门槛。实现按任务卡逐项进行，不再由开发者从缺陷清单重新推导方案。设备或模型相关结论必须由指定验证任务得出；每个未知项都给出探测方法、分支和产物。

## 1. 从哪里开始、按什么顺序阅读

| 文档 | 用途 |
|---|---|
| [AGENTS.md](AGENTS.md) | 开发协作约束、现状与规划的区别 |
| [CODEMAP.md](CODEMAP.md) | 当前源码在哪里、哪些链路尚未接通 |
| [PROGRESS.md](PROGRESS.md) | 当前问题 B01–B12 与实际进度；仅验收后更新状态 |
| [CONTRACTS.md](docs/plan/CONTRACTS.md) | **唯一权威跨端合同**：帧协议、模型包、坐标和结果语义 |
| [TRAINING.md](docs/plan/TRAINING.md) | 数据语义、采集/划分、训练、导出和迭代规格 |
| [NATIVE.md](docs/plan/NATIVE.md) | 截图、帧归一化、进程、传输、构建和部署规格 |
| [ANDROID.md](docs/plan/ANDROID.md) | App/SDK、线程、状态机、权限、模型更新和交付规格 |
| [TASKS.md](docs/plan/TASKS.md) | 按依赖排序的文件级任务卡、产物和完成条件 |
| [VALIDATION.md](docs/plan/VALIDATION.md) | 测试输入、预期结果、数值门槛和交付检查 |

发生文档冲突时：当前行为以源码和 CODEMAP 为准；未来跨端行为以 CONTRACTS 为准；验收数值以 VALIDATION 为准；任务执行以 TASKS 为准。修改一份契约必须同步消费者规格，不能悄悄实现另一个版本。

建议首次开发从 **T01 构建基线、T03 数据规则、T07 合同测试样例** 开始；三者可并行。具体依赖见 TASKS，不需要先完成大规模训练，也不需要等手机 root。

## 2. 交付范围与完成定义

首个可交付版本应包含：

1. 可复现的数据校验、训练、TFLite 导出与模型包生产流程。
2. K70 上可启动的诊断 App：导入图片离线推理、选择 CPU/GPU、检测启停、状态/错误/性能显示、诊断报告导出。
3. root daemon：正确采集当前主显示画面、规范化 RGBA、v2 Socket 发送、可取消和重连、明确版本与设备适配信息。
4. 本地同进程 SDK：状态和逐帧结果订阅、有效期、原图框与中心坐标；可单独发布 AAR 并由示例 App 集成。
5. 安装、启停、模型选择、显式模型更新、兼容性检查、原子切换和失败回滚流程。
6. 固定独立测试集的精度报告、K70 持续运行报告、版本清单及可复现交付包。

检测循环只产生观察结果。现有 `tap`/`swipe` 作为调用方显式使用的独立接口保留，不接入自动开火、瞄准、跟踪或自动操作策略。稳定目标 ID、多类别、无 root 实时采集、远端推理、全机型覆盖不属于 v1 必交范围。

区分两个完成层级：

- **集成原型完成**：M1–M4 验收通过，可在诊断模式看到正确结果和可靠启停；不等于精度或 15fps 达标。
- **v1 交付完成**：M1–M6 对应必交项通过，包含全距离精度和 REALTIME 持续性能。任一硬门槛失败，继续按失败路线迭代，不能只改标签宣布完成。

## 3. 已确定的技术选择

| 决策 | v1 采用的方案 | 原因与改变条件 |
|---|---|---|
| 检测目标 | 单类 `enemy`；具体语义按 TRAINING | 保留项目焦点，先解决敌我/小点标签一致性 |
| 初始模型 | YOLOv8s-P2，960×960；从官方 P2 YAML 建结构并记录兼容预训练权重迁移 | 不依赖来源不明的 `yolov8s-p2.pt`；精度/延迟失败时按对照矩阵调整 |
| 导出接口 | fp16 权重、float32 I/O、内置 NMS、规范化 `xyxy/score/class` | 单一明确端上解析；不支持的产物在导出阶段拒绝 |
| 模型载体 | `model.tflite` + `model.tflite.json` + SHA-256，版本不可变 | 消除猜 shape、猜文件名和缓存兼容性问题 |
| 预处理 | RGB、0–1、居中 LetterBox、114 填充、floor 缩放尺寸、按实际 sx/sy 反变换 | Python 与 Android 共用合同和黄金输入验证 |
| 截图路线 | 首先 CLI raw 正确性基线；高帧率使用经设备验证的版本化 SurfaceFlinger C++ 适配层 | CLI 只用于诊断；私有 ABI 不能凭符号字符串猜实现 |
| 帧协议 | 新开发直接采用 CONTRACTS 的 v2 64 字节 LE 头；v1 明确拒绝 | 当前未发布，可一次同步修正并加入可观测性，不维护双协议 |
| 数据通道 | daemon 和 App 各一个 latest-frame 槽，完整帧交付；固定资源上限 | 避免 FIFO 越积越旧；只可替换尚未发送/推理的完整帧 |
| 线程 | 生命周期协调器串行、Socket 独立 I/O、GPU/解释器专用单线程 | 消除并发销毁、GPU 线程限制和阻塞主线程问题 |
| 状态与结果 | `VisionSnapshot` 为唯一权威状态；真实空帧与错误/过期不同 | 消除“上一帧有敌人一直残留”的错误语义 |
| App/SDK | 先在现有 app 打通，再拆 `:vision-sdk` library；本地 Binder，不做远程 IPC | 最小闭环先行，最终交付 AAR 可集成 |
| 更新 | 默认关闭自动联网；用户显式启用配置后检查，完整验证后切换，可回滚 | 内置模型足以建立基线，更新失败不影响可用旧模型 |
| 首台平台 | 用户提供的 K70；保留 minSdk28/targetSdk34 基线，arm64-v8a | Android/ROM/root 信息在设备探针记录，兼容性逐台验收 |

### 两种运行配置

| 项目 | DIAGNOSTIC | REALTIME（默认运行配置） |
|---|---|---|
| 用途 | CLI 截图、链路正确性、故障定位 | v1 持续性能验收和正常使用 |
| 采集/消费目标 | 5fps | 15fps；不继续使用原代码固定 30fps 采集 |
| 最大结果帧龄 | 5000ms | 250ms |
| 单帧读/写截止 | 5s | 1s |
| 通过的含义 | 能正确定位且可靠启停 | 同时满足 VALIDATION 的精度、时延、吞吐和稳定性 |

SDK start默认REALTIME并显式将profile传给daemon；手工直接运行daemon不传profile时默认DIAGNOSTIC，二者默认值不能混用。运行配置必须在状态、UI 和日志中可见。诊断模式不会被自动升级为 REALTIME，也不能用其 5 秒有效期规避实时验收。

## 4. 目标模块地图（均为拟建或拟调整）

```text
training/
  data/{images,labels}/{train,val,test}/
  data/manifests/<datasetVersion>/{frames,annotations}.csv
  data/README.md / data/manifests/<datasetVersion>/split-report.md
  validate_dataset.py / split_dataset.py / model_contract.py
  train.py / export_tflite.py / visualize.py / evaluate.py
  tests/ / requirements-{train,export}.lock / runs/delta_enemy/<run-id>/
native-daemon/
  main.c / screencap.c,h / socket_server.c,h
  frame_protocol.c,h / capture_raw.c,h / capture_backend.h
  capture-sf/                         设备版本适配层，独立验证和构建
  build.sh / deploy.sh / device_probe.sh / tests/
android-app/
  app/                               示例与诊断 UI
  vision-sdk/                        M6 从已验证 app 代码迁移
    api/ service/ model/ detection/ socket/ update/
  gradle/wrapper/ / gradlew / gradlew.bat
contracts/fixtures/                  后续 T07 生成的两端共同黄金样例
scripts/                            后续统一校验与发布入口
.github/workflows/                  后续 host/NDK/Android 构建检查
artifacts/<release-id>/              后续本地/CI交付产物，不默认提交大文件
```

这是目标职责图，不代表已经存在。任务卡可以将小型辅助职责保留在现有文件，但模块边界、接口和测试责任不可省略；核心计划中的 CLI、fixture 名称和错误码应一致。实际新增文件后才更新 CODEMAP 的“现有文件树”。

## 5. 开发阶段与交接

| 阶段 | 开发完成后的交接物 | 阶段门槛 |
|---|---|---|
| M0 规划 | 本文、合同、三个模块规格、任务和验收文档 | 无悬空关键接口、每个未知项有解决路线；本轮交付 |
| M1 构建 | 版本清单、Wrapper、NDK binary、可启动诊断 APK、host CI | 无模型/无 root 仍可启动并明确反馈；具体测试见 G1 |
| M2 数据/模型 | 数据规范与版本、加载/导出探针、合法模型包 | 精确合同和可复现来源，不用假模型冒充训练结果；G2 |
| M3 离线推理 | 黄金样例、跨引擎差异报告、K70 CPU/GPU 本地图片结果 | 数值/坐标/空结果、后端与资源生命周期通过；G3 |
| M4 在线集成 | 设备探针、诊断截图、v2 帧、SDK 启停与恢复报告 | 诊断闭环可靠，无旧结果和残留进程；G4 |
| M5 性能/精度 | 模型对照、独立测试集报告、REALTIME 30 分钟记录 | 全距离指标及实时门槛均通过；G5 |
| M6 交付/更新 | AAR、示例APK、daemon、模型包、校验清单、更新/回滚与安装文档 | 可复现安装/升级/恢复；G6 |

依赖：M1 与 M2 并行，随后 M3；M4 需要 root；M3 后可以独立推进精度训练和模型基准，M4 后再做整链路性能；M6 不阻塞前期正确性验证。每个任务的预估规模用于安排顺序，不作为未经验证的工期承诺。

## 6. 环境基线与兼容性决策

Android 第一轮保持 AGP 8.2.0、Kotlin 1.9.20、TFLite Java/GPU 2.14.0，新增 Gradle 8.2 Wrapper、JDK17、SDK34/Build Tools34.0.0；Native 明确选择 NDK r26d（26.3.11579264）、CMake3.22.1、arm64/API28。记录来源、校验和及 `--version` 输出。上述是计划选择，尚未安装或构建。AGP 8.2 对应 Gradle8.2/JDK17 的支持见 [Android 官方兼容表](https://developer.android.com/build/releases/agp-8-2-0-release-notes)。

训练和导出环境分别管理，候选与锁定方法见 TRAINING。TFLite+NMS 不能默认在 Linux ARM64 上导出；macOS 训练后使用 Linux x86_64 导出环境，Apple Silicon 容器须明确 amd64，记录是否模拟执行。不以训练包安装成功代替模型转换成功。

**版本兼容分支固定如下**：

1. 先跑候选导出环境及现有 Android runtime 的 CPU 模型加载/黄金输入探针；通过才冻结依赖锁和模型生产配置。
2. 失败时记录缺失算子、tensor、转换日志及最低 runtime 要求；先消除错包/错导出选项/平台问题。需要 Select TF Ops 时明确加入与 runtime 同版本的依赖并重测，不静默改为 raw 输出。
3. 若算子确需更高 runtime，按转换工具官方支持要求选择**同版本 Java/GPU/Select TF Ops**，保存新版本锁及决策记录，重跑全部合同/离线/GPU检查。依旧失败则该导出组合淘汰，保持当前模型合同，更换经过同套探针的转换组合。
4. 仅实际通过的组合进入发布清单。合同变化属于显式新模型/SDK版本任务，不能在后处理里尝试多种猜测布局。

## 7. 未知项的固定处理路线

| 未知项 | 获取证据的任务 | 通过后的动作 | 不通过后的动作 |
|---|---|---|---|
| K70 Android/ROM/root/SELinux | T16 设备探针 | 生成设备指纹档案，选择匹配 capture adapter | 无 root 时继续 M1–M3；访问被拒绝保留诊断，定位具体路径，不把全局放宽策略作为修复 |
| CLI raw 布局/格式/方向 | T17 静态校准页 raw/PNG 对照 | 固定该指纹的 parser layout 与方向 | 不支持则报明确错误；增加有测试样例的 parser，禁止按长度猜坏帧 |
| SurfaceFlinger 私有 API | T21 与目标 ROM 匹配的 AOSP/厂商接口验证 | 独立 adapter 通过色彩/旋转/性能测试后启用 | 保留 CLI 诊断；记录不支持，继续模型离线优化；REALTIME门槛不得宣布通过 |
| GPU/NMS 算子可运行性 | T11/T13 | 专用线程GPU并记录委派覆盖及真实耗时 | 完整销毁失败实例，CPU基线；如果吞吐不达标，进入模型/转换组合对照 |
| tiny 召回与15fps能否共存 | T22/T23 | 固定最佳满足全部门槛的模型 | 补样复核→尺寸/结构→ROI实验；记录精度—延迟前沿，全部失败则保留原型、v1未通过 |
| 热更新来源/地址 | T25 配置与本地测试服务器 | 有明确配置时显式检查更新 | 未配置时使用本地模型，无占位网络请求，无阻塞启动 |

这里的“未验证”是实施输入或试验结论，不是把方案选择留空；对应任务已经指定证据、默认动作与失败终态。

## 8. 交付纪律

开发一次完成一张可验收任务卡，将证据写入 PROGRESS；不要把整个阶段一次性标完成。跨端改动用同一合同版本和同一批 fixture 验证。发布包必须可由第三人按文档重新构建/安装，并能从报告追溯模型、数据、runtime、daemon和设备版本。

开始实施前无需再讨论总体架构；按 TASKS 开工即可。设备版本和模型可行性在指定探针中填入实测记录，不能以缺这些信息为由跳过当前可离线完成的任务，也不能以计划完整代替实际验收。
