# 按依赖执行的开发任务卡

所有任务均为**待实施**，不是本轮代码改动。读完对应规格再动手；任务完成必须提交指定证据。文件路径中的新增脚本/目录是拟建项，规划命令在实现前不可直接运行。

## 1. 排序与并行安排

```text
构建：T01 → T02
数据：T03 → T04 → T05
共同合同：T07 → T08；T02 + T07 → T09
模型：T05 + T09 → T06 → T10；T02 + T06 + T09 + T10 → T11
运行时：T02 + T07 → T12；T11 + T12 → T13
采集：T08 → T14 → T15 → T16 → T17
在线：T11 + T12 + T13 + T16 + T17 → T18 → T19 → T20
优化：T16 + T17 + T20 → T21；T04 + T05 + T06 + T11 → T22
联合验收：T13 + T20 + T21 + T22 → T23
交付：T20 → T24；T10 + T12 + T19 → T25；T23 + T24 + T25 → T26
```

先并行三条线：构建（T01–02）、数据（T03–06）、合同与纯逻辑（T07–10）。没有数据时T05/06真实训练导出不能通过，但可以完成CLI和负路径测试；没有root时T16–20的真机部分不能通过，其余继续。不要用mock或synthetic输入把这些依赖伪装成完成。

## 2. 构建、数据与模型基础

### T01

**构建环境与版本锁；M1；依赖：无。** 修改现有Gradle配置，新增Wrapper及校验记录；Native固定NDK r26d/CMake3.22.1/arm64/API28。初始Android版本见主计划，不借任务无关升级依赖。

实施：列清Python训练/导出、JDK/Gradle/SDK/NDK环境→建立可复现安装说明与版本锁→记录源码/发行包来源和hash→在干净环境重跑。产物：环境清单、Wrapper、构建入口；验收ENV01，依赖不可用明确失败，无隐式本机路径。

### T02

**最小可构建工程；M1；依赖：T01。** 涉及 `DetectionService.kt`、`ScreenVisionSDK.kt`、Manifest、Native C/CMake/Android.mk、拟建 `MainActivity.kt` 与CI。

实施：修Kotlin/C语法与类型/链接错误→合法Binder骨架→最小诊断入口与正确前台服务声明→缺模型/无root不会在Application启动时崩溃→补host/NDK/Android构建检查。此处不伪造推理或结果桥接完成。产物：debug APK、arm64 binary、构建日志；验收ENV02/03，B01/B02构建部分关闭。

### T03

**数据语义与清单；M2；依赖：无。** 拟建 `training/data/README.md`、标注规则及manifest模板，修 `dataset.yaml`路径约定。

按TRAINING固定敌人/友军/倒地/尸体/遮挡/小点/负样本规则；定义group、review状态、宽高/hash、数据版本；列采集覆盖矩阵。产物是规范与真实样本入库流程，不生成虚假游戏数据。验收DATA04，随机挑10个边界案例能由两位标注者按同规则判定；分歧记录复核结果。

### T04

**数据预检、分组与可视化；M2；依赖：T03。** 拟建 `dataset_tools.py`、`validate_dataset.py`、`split_dataset.py`、tests；修 `visualize.py`。

实现严格配对/范围/腐坏图检测、空标与缺标区分→按group划分与重复/近重复报告→冻结清单/hash→修可视化目录映射并支持保存审核图。验收DATA01–05；新增或修改数据不会悄悄污染test。

### T05

**可复现实验训练；M2；依赖：T04。** 修改 `train.py`，拟建训练依赖文件/锁、配置和run报告。

从官方P2 YAML构建并显式迁移权重，记录匹配/未匹配参数；CLI提供data/model/pretrained/device/imgsz/batch/epochs/seed/run-id/resume；自动设备顺序CUDA→MPS→CPU，用户显式设置优先。运行目录 `runs/delta_enemy/<run-id>` 不覆盖；先少量批准样例smoke再正式训练。验收DATA06；没有数据时只完成工具，真实训练门禁仍待测。

### T06

**导出环境探针与候选模型包；M2；依赖：T05、T07、T09。** 修改 `export_tflite.py`，拟建 `probe_export.py`、`model_contract.py`、导出锁文件。

先在本任务实现最小桌面PyTorch/TFLite语义比较适配器，复用T09部署预处理，不依赖下游T11的Android比较工具；再在明确Linux x86_64候选环境验证依赖→真实导出 `nms=True/conf0.001/iou0.5/maxDet300/默认fp16`→消费返回路径→检查实际tensor/语义→生成候选sidecar/hash和转换报告。Android核验后才晋升正式包，避免sidecar与端上验证循环等待。验收MODEL01/02；未知布局/量化I/O拒绝，不能换 `nms=False` 绕过。

### T07

**共同合同fixture；M1/M2；依赖：无，按已冻结CONTRACTS。** 拟建 `contracts/fixtures/`、manifest与各语言测试读取辅助。

逐字节定义v2正常/异常帧→定义NMS六字段/LetterBox黄金例→定义可控BOOTTIME事件序列→两端读取同一fixture。此任务只验fixture定义、长度/hash与独立数学期待值；生产编解码/解析测试分别由T08/T09执行。源和期待结果独立核对，不复制实现输出充当golden。

## 3. 无root的Android与传输核心

### T08

**v2协议编解码与尺寸安全；M1/M3；依赖：T07。** 修改Native发送与Kotlin接收，拟建 `frame_protocol.c/h`、`FrameHeader.kt` 及对应测试。

实现64字节LE显式偏移→分配前64位边界/长度校验→短读短写状态→版本/ID/时钟拒绝规则。先host合成帧，不依赖截图；后续真机仍需验收。产物：两端合同测试；验收FRAME01–04/08的host部分。

### T09

**预处理与坐标纯逻辑；M2/M3；依赖：T02、T07。** 修改 `Preprocessor.kt`、`PostProcessor.kt`、`DetectResult.kt`，拟建Python部署参考预处理。

由合同S驱动输入，固定floor/实际sx/sy/padding/RGB→解析NMS六字段和无效行→原图连续bbox、裁剪中心、边界→缓冲复用接口。验收MODEL03/06的预处理和纯解析部分（真实模型对照在T11），黄金例中心1600/720；不猜YOLO raw/objectness。

### T10

**模型存储与合同加载；M3；依赖：T06、T07。** 修改 `YOLODetector.kt`、模型路径相关代码，拟建 `detection/ModelContract.kt`、`update/ModelRepository.kt`。

统一assets与文件路径→校验sidecar/hash/SDK/schema→CPU加载核对真实输入输出tensor→缓存只选完整active，缺失时回内置→失败不进入live。验收MODEL01/02；候选模型包用于runtimeprobe，加载成功记录实际后端/模型id。

### T11

**CPU离线推理闭环；M3；依赖：T02、T06、T09、T10。** 拟建离线图片入口、`compare_backends.py`、测试样例和结果导出。

系统选择器选图→C03输入→CPU真实模型→框/中心/置信度显示→导出保存输入tensor与结果→同图Python/TFLite/Android比较。验收MODEL04、SDK10；无root不阻断；随机输入只算加载smoke。

### T12

**SDK状态、Binder与结果桥接；M3；依赖：T02、T07。** 修改SDK/Service，拟建 `RuntimeCoordinator.kt`、状态数据类、Clock抽象与测试。

一个Coordinator串行写snapshot→合法LocalBinder返回同一store/命令接口→start/stop幂等与session/revision→frames事件和旧observe投影→typed错误与空结果→TTL定时器。先fake帧源验证，再T18接真实流。验收SDK01–05/09的host或instrumentation部分，不能只验证“Flow被调用”。

### T13

**专用推理线程、GPU与CPU回退；M3；依赖：T11、T12。** 修改YOLODetector/Service，拟建 `InferenceWorker.kt`。

模型创建/invoke/close同线程→GPU init与invoke失败完整释放后CPU重建一次→actual backend记录→stop不并发close→20轮加载/释放→K70离线性能。验收MODEL05/08/09；GPU_REQUIRED模式用于防止回退误记GPU成绩。

## 4. Native、设备与在线集成

### T14

**Native主循环与传输生命周期；M1/M4；依赖：T08。** 修改main/socket/screencap接口，拟建backend抽象和host测试。

capture/latest/sender所有权→单客户端/有界槽→EINTR/EAGAIN/SIGPIPE处理→cancel与期限→正确BOOTTIME统计→单实例和结构化退出原因。用合成backend验证，不等待私有API。验收FRAME04/07/09/12的host部分。

### T15

**构建、安装与版本发布路径；M1/M4；依赖：T14、T02。** 重构build.sh，拟建deploy.sh/manifest、Android assets打包入口。

build只构建→显式deploy+serial→版本目录中完整安装binary/manifest→校验后原子切换执行入口→安装锁与运行实例锁协调→失败保留旧版。保持设备执行地址 `/data/local/tmp/screen-visiond`，不生成非法raw资源名。ENV04在host模拟验证；实际root部署在T16/18验收。

### T16

**K70设备探针与显示控制；M4；依赖：T15；需要设备/root。** 拟建 `device_probe.sh`、`DisplayStateWriter.kt`，接入NATIVE/ANDROID规定的身份/控制访问。

采集ROM/API/ABI/内核/SELinux/root/屏幕信息→验证binary执行、pidfd能力、App文件只读访问/Socket peer身份→原子写C04 display-state→生成设备指纹档案。输出每项能力PASS/FAIL和恢复步骤，未知能力不可默认成功。无root时设备项NOT_RUN，继续离线任务。

### T17

**CLI截图正确性基线；M4；依赖：T14、T16。** 替换不可用libgui骨架的默认行为，拟建capture_raw解析与设备profile；细节按NATIVE。

按C08生成candidate/profile/校准报告并验证后产出verified，再通过App导入；静态校准页raw/PNG对照锁12/16字节layout→显式支持像素格式/SDR→正确尺寸/stride/旋转→capture子进程超时与回收→DIAGNOSTIC5fps输出v2。验收FRAME05/06/10/11；不把CLI速度称作15fps截图后端。

### T18

**root启动、前台Service与首帧就绪；M4；依赖：T11、T12、T13、T16、T17。** 接通SDK→daemon→v2→推理→snapshot全链路。

可见用户入口→前台Service/通知与合法Binder→一次root/模型检查→更新显示控制文件→已校验实例启动→完整首帧ready→profile期限与typed错误→显式停止。首轮人工/显式按钮安装daemon，之后可以配置安装策略。验收SDK02/06，DIAGNOSTIC真实结果与模型/帧版本均可追溯。

### T19

**断流、显示变化、TTL和启停恢复；M4；依赖：T18。** 修改Coordinator、UnixSocketClient、Native控制/退出和缓存释放路径。

显示generation变更清帧重连→失效旧session/stream结果→有界30s恢复→自有daemon一次受控重启→App崩溃后用C08持久化旧owner记录精确清理→50轮start/stop→锁屏/Service退出→故障与正常空帧区分。验收SDK01–09和FRAME04/08–12；残留/过期目标即失败。

### T20

**诊断闭环集成验收；M4；依赖：T19。** 不新增功能，整理G4验证脚本/报告和诊断包。

执行全部DIAGNOSTIC场景→记录真实截图/输出/错误时间线→复核像素与坐标→修复失败再重跑受影响项。G4全通过才标“集成原型完成”；所有性能/精度未测项仍明确保留。

## 5. 精度、性能与最终交付

### T21

**高帧率截图适配；M5；依赖：T16、T17、T20。** 拟建独立 `native-daemon/capture-sf/` C++适配器和设备构建profile。

按设备指纹匹配AOSP/厂商接口→用真实头/签名/链接构建隔离shim→捕获并归一化到同一FrameBuffer→先重复全部正确性用例→再持续性能。任何符号/布局不匹配快速失败并保留CLI诊断。验收FRAME与VALIDATION采集后端指标；失败不能把没验证的私有ABI写为支持。

### T22

**数据扩充、模型对照与独立精度；M5；依赖：T04、T05、T06、T11。** 拟建 `evaluate.py`、`compare_backends.py`、错误样例清单和报告。

按val查错补样→记录n/s-P2与输入尺寸候选→固定阈值→封存test一次正式评估→报告所有桶、空帧、中心误差和置信区间。硬门槛见VALIDATION；样本不足不算通过。tiny失败时走X02前先证明是缩放瓶颈，不随意改变单类语义。

### T23

**REALTIME持续性能与联合选型；M5；依赖：T13、T20、T21、T22。** 增加必要的计时/内存/丢帧观测、缓冲复用与模型选择。

按REALTIME15fps驱动→定位主瓶颈→每次只改变能解释瓶颈的变量→两轮K7030分钟+游戏独立基线→选择同时满足精度与性能的候选。验收G5；仅改fps常量、单帧benchmark或CPU回退日志不能算完成。

### T24

**SDK library与示例交付；M6；依赖：T20。** 新增 `:vision-sdk`，将已验证核心从app迁入library；app只依赖AAR接口。

保持包/API/合同→配置consumer ProGuard、Manifest合并、assets/raw打包策略→全新示例只通过公开API集成→重跑离线/在线/停机核心回归。产物AAR、示例APK与集成说明；验收RELEASE01，不能只生成空壳AAR。

### T25

**模型更新、激活与回滚；M6；依赖：T10、T12、T19。** 完善ModelUpdater/ModelRepository，拟建明确更新配置、stage/activate接口与本地测试服务fixture。

C07清单/下载期限/大小/hash→不可变暂存版本→显式stage→worker帧边界activate/3次warmup/首帧→active原子提交→故障回滚与进程中断恢复。默认无网络，更新失败不覆盖旧active。验收UPDATE01–06，不把下载成功当模型生效。

### T26

**发布候选验收与可复现交付；M6；依赖：T23、T24、T25。** 拟建release打包/校验入口、清单与使用文档，更新CODEMAP/PROGRESS到真实实现。

从干净环境构建→全门禁与目标机复验→生成同批APK/AAR/daemon/模型/checksums/toolchain/reports→第二次按安装文档部署→版本不匹配/回滚/卸载验证。G1–G6全部PASS才标v1交付完成；不得提交“剩余root/模型/性能待验证”的伪完成包。

## 6. 条件触发任务（路线已定，未触发时无需实现）

| ID | 触发条件 | 具体实施与停止条件 |
|---|---|---|
| X01 INT8 | fp16完整基线通过，存在已量化的推理/内存瓶颈 | ≥300train校准帧/≥5对局；仍float32 I/O；任何桶recall下降≤2个百分点、全量precision/recall下降≤1个百分点，同时满足绝对门槛；p95改善≥15%或实际常驻内存下降≥25%，否则淘汰 |
| X02 ROI/切片 | 补样/复核/全图尺寸对照后，仍证实缩放导致tiny失败 | 首个对照采用原图960×960瓦片、20%重叠，边缘补114；全屏网格按768步长覆盖、末片贴边去重；每个tile坐标先回原图，再按同类IoU0.5合并；完整扫描轮输出一组结果，captureStart沿用原帧，必须报告全屏更新周期/帧龄；不能只报单tile15fps。失败则对照较小P2/更高全图分辨率，记录前沿；不加入跟踪或自动瞄准 |
| X03 更多ROM/机型 | K70 v1已通过且明确需要扩展支持 | 每个指纹重复设备探针、截图适配、权限/SDK恢复、精度一致性及持续性能；未覆盖版本显示不支持，不仅修改支持列表 |

X02全屏多tile在K70可能不满足250ms/15fps，这是需测量的候选；规划包含验证方法和淘汰条件，不能因为规划了切片就承诺远距实时能力已解决。

## 7. 缺陷到任务追踪

| 原问题 | 关闭任务 | 必须保留的证据 |
|---|---|---|
| B01 Android编译 | T01/T02 | ENV02/03构建日志 |
| B02 Native/打包 | T02/T15 | NDK、资源与部署检查 |
| B03 截图 | T16/T17/T21 | raw/PNG校准、版本适配和性能 |
| B04 模型布局 | T06/T07/T09/T10/T11 | MODEL01–07 |
| B05 结果通道 | T12/T18/T19 | SDK02–05 |
| B06 权限/GPU | T02/T13/T18 | FGS启动、GPU/CPU真实回退 |
| B07 训练/配对/导出 | T03–T06 | 数据预检与实际模型包 |
| B08 帧安全/断流 | T08/T14/T19 | FRAME01–12 |
| B09 启停恢复 | T12/T15/T18/T19 | 身份、期限、50轮启停 |
| B10 可复现与划分 | T01/T03–T06/T22 | 环境/数据/实验锁与报告 |
| B11 性能 | T14/T21/T23 | G5持续性能、帧龄、丢帧和内存 |
| B12 更新 | T10/T25/T26 | UPDATE01–06、完整发布清单 |

## 8. 未来执行入口约定

以下仅是拟建CLI的使用说明，开发完成前不存在，不在本轮执行。各脚本 `--help` 应不要求导入大型训练依赖；失败返回非零并输出可读原因。

| 工作 | 计划入口 | 工作目录/输出 |
|---|---|---|
| 数据检查/划分 | `python training/validate_dataset.py --manifest …` / `python training/split_dataset.py --manifest … --seed 42` | 仓库根；固定manifest与质量报告 |
| 训练 | `python training/train.py --data training/data/dataset.yaml --manifest … --model yolov8s-p2.yaml --imgsz 960 --seed 42 --run-id …` | 仓库根；training/runs/delta_enemy/run-id |
| 导出 | `python training/export_tflite.py --weights … --output-dir …` | 导出环境；候选模型包+sidecar，固定合同选项 |
| 评价 | `python training/evaluate.py --model … --manifest … --split test --threshold …` | 仓库根；覆盖/精度报告 |
| Native构建 | `./native-daemon/build.sh --abi arm64-v8a --api 28` | 仓库根；不推送设备 |
| 部署/探针 | `./native-daemon/deploy.sh --serial … --binary …` / `./native-daemon/device_probe.sh --serial …` | 仓库根；明确设备、版本、能力档案 |
| Android检查 | `./android-app/gradlew -p android-app :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | 仓库根；Wrapper实现后可用；AAR阶段加入library任务 |
| 模型一致性 | `python training/compare_backends.py --manifest … --reference … --candidate …` | 仓库根；保存相同输入tensor与两端输出 |

未来CLI统一从仓库根目录执行，路径按显式参数或配置文件位置解析；模型目录固定 `training/runs/delta_enemy/<run-id>/`。字段/目录与模块规格如有差异，以CONTRACTS语义为准并在实施前同步命令参数文档。执行某任务遇到前置条件缺失，标明哪个门禁未过并继续独立任务；不能省略任务或把未运行勾选为完成。
