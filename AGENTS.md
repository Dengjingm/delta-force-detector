# AGENTS.md

## 项目目标与协作边界

Delta Force（三角洲行动）敌方干员视觉检测原型。在 root Android 设备上采集画面，检测单类 `enemy`，返回屏幕中心坐标和置信度。当前训练候选为 YOLOv8s-P2、960×960 输入，15fps 是待验证目标。

按用户当次明确范围开展工作：文档审查任务只修改文档，发现代码问题先记录到 `PROGRESS.md`，不据此自动扩展为代码实现。后续收到实现任务，再按计划推进对应里程碑。

## 开始工作前

1. 阅读本文件，再读 [CODEMAP.md](CODEMAP.md) 的入口表、调用链和跨模块契约。
2. 实施前阅读 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)，再按 [TASKS](docs/plan/TASKS.md) 选择任务；跨端字段以 [CONTRACTS](docs/plan/CONTRACTS.md) 为准，验收数值以 [VALIDATION](docs/plan/VALIDATION.md) 为准。[PROGRESS.md](PROGRESS.md) 记录当前证据与剩余问题；[README.md](README.md) 是概览。
3. 检查 `git status --short`，保留用户已有改动；修改前阅读相关实现，不能仅依据旧注释或进度描述判断功能可用。
4. 涉及多模块时先确定契约及双方修改位置。第三方 API/导出行为按明确版本的官方文档或源码核实。

`CLAUDE.md` 是指向本文件的符号链接，直接维护 `AGENTS.md`；不要另建 `agent.md` 或复制第二份规则。源码决定“当前实现”，本文件规定协作约束，CODEMAP 负责现有代码导航，IMPLEMENTATION_PLAN及docs/plan负责目标规格，PROGRESS记录进度与证据；矛盾应显式记录并同步修订。

## 当前基线与首台设备

- 当前是未完成集成的脚手架。训练、Native、Android 的主要文件存在，但没有端到端验收记录；已发现的编译和接口问题见 PROGRESS。
- 仓库没有训练截图、标注、训练权重、TFLite 资产、Gradle Wrapper、自动化测试或 CI。不要将预期目录、下载缓存或构建产物写成已经存在。
- 用户指定首台验证设备：**红米 K70、第二代骁龙 8、最高 3.19GHz、12GB 内存 + 4GB 扩展内存、3200×1440 屏幕**。这些是用户提供的信息，未做真机核验。
- 用户计划稍后 root。Android 版本、HyperOS/ROM 版本、root 方案、实际游戏渲染/截图分辨率仍待记录；不能假定已 root、adb 已连接或 SurfaceFlinger/GPU 兼容。
- 构建配置 `minSdk=28` 对应 Android 9，`targetSdk=34`；这只是配置，不是 API 28+ 全版本兼容证明。扩展内存不等同于额外物理 RAM，也不构成性能保证。

## 模块职责

| 位置 | 职责 | 必须联动的边界 |
|---|---|---|
| `training/` | 数据定义、标注检查、训练、TFLite 导出 | 类别、输入输出张量、NMS、模型产物 |
| `native-daemon/` | root 截图、帧规范化、Socket 发送、进程退出 | 字节序、像素格式、stride、帧尺寸、断连 |
| `android-app/` | 接收帧、预处理、推理、坐标转换、Service/SDK | 模型契约、Binder/Flow、权限与生命周期 |
| `AGENTS.md` / `CODEMAP.md` | 协作规则 / 实现导航 | 文件和接口变化后同步维护 |
| `PROGRESS.md` / `README.md` | 验收与计划 / 项目入口 | 状态、命令、限制保持一致 |

现有文件树、关键函数和调用链只维护在 CODEMAP；未来模块地图在IMPLEMENTATION_PLAN，必须标注拟建，避免将计划写成实现。Android 当前是 `application` 模块；不要称已有独立 AAR SDK。

## 数据与模型约定

- 唯一类别 `0: enemy`，YOLO 标签为 `class_id cx cy w h`，坐标相对于原始截图归一化到 0–1，宽高必须大于 0。类别变更必须同步 `dataset.yaml`、Service 默认类别、调用方类别及模型元数据。
- 标注所有能可靠确认的可见敌方干员，包括远处 5–10px 小目标。先定义队友、遮挡、倒地、尸体和不确定小点的处理规则；无法判断的目标进入复核，不强行猜标。
- 数据采集同时覆盖距离、地图、光照、运动模糊、敌人数和困难负样本。按对局或连续视频片段划分 train/val/test，避免相邻帧泄漏；保留独立测试集与数据版本。
- 200 张可作为采集起点，1000+ 是后续规模方向，不是精度验收门槛。采集数量、标注质量与实际远距召回分开报告。
- 保留当前 `nms=True`、默认 fp16 的导出方向；任何 NMS 策略变化必须同时修改导出和端上解析并提供一致性证据。内置 NMS 输出不能再按旧式 raw/objectness 布局解释。
- 以**真实导出模型**检查的 shape、dtype、量化参数、布局、坐标单位、类别和 NMS 状态为准。不能靠固定维度猜类别数；fp16 权重不代表 fp16 输入，int8 选项也不代表端上已兼容量化 I/O。
- 目标模型包和元数据已经在CONTRACTS C02定稿，开发时直接按该合同实现，并随模型保存版本化元数据。验证 PyTorch、TFLite、Android 对同组截图的结果；导出脚本应消费实际返回路径，不能靠猜文件名定位产物。
- 当前训练保存与导出默认路径仍是 `training/runs/hok_detector/weights/best.pt`。以后改为 `delta_enemy` 时同时修改两个脚本和使用说明。

## 帧、坐标与生命周期约定

- 当前 v1 帧协议为 `[width:uint32][height:uint32][RGBA:width*height*4]`。C 和 Kotlin 使用主机字节序，在目标 arm64 上实际为 little-endian，**不是 big-endian**。新开发按CONTRACTS C01直接升级为64字节LE的v2，拒绝静默兼容旧头；当前代码仍是v1，迁移必须同步 `socket_server.c` 与 `UnixSocketClient.kt`。
- 负载必须是紧密排列的 RGBA；有行 padding 或不同格式时先转换或显式传递元数据。尺寸、长度和分配要检查上下界及整数溢出；流式 Socket 要处理短读/短写、断流、超时和取消。
- `writev` 是聚集写入，不能称为零拷贝。`USE_SCREENCAP_FALLBACK` 当前只是编译分支，不是已实现的运行时自动回退。
- daemon 与 Socket 位于 `/data/local/tmp/`；下载模型位于 `context.filesDir/models/`，内置模型预期位于 APK assets。不要笼统写“所有路径都在 /data/local/tmp/”。
- root 授权、daemon 安装/启动/就绪、App 前台服务权限、Socket 可访问性是不同条件，需要分别验证。不要用固定延时或 `isStarted=true` 代替就绪证明。
- 预处理和坐标反变换使用同一组实际缩放和 padding 参数。结果坐标相对于采集帧；横竖屏、分辨率变化、模型归一化坐标及像素边界必须覆盖。
- SDK 的无目标、断流、错误和停止要能清除过期结果。GPU delegate 的创建、运行、关闭应处于符合其要求的同一线程；CPU 回退须覆盖解释器初始化失败。
- 模型热更新目前只是未闭环骨架。缓存选择、校验、兼容性检查、原子切换和失败回滚验收前，不能称为可用热更新。

## 命令与验证边界

下面是现有入口，不代表已经可运行。训练、导出及真机部署需要先满足 PROGRESS 中的前置条件。

```sh
# 从仓库根目录进行不依赖训练包的语法检查
python3 - <<'PY'
import ast
from pathlib import Path
for path in sorted(Path('training').glob('*.py')):
    ast.parse(path.read_text(), filename=str(path))
    print(f'AST OK: {path}')
PY
bash -n native-daemon/build.sh

# training/ 下现有入口：尚需数据、可加载模型和验证过的依赖环境
cd training
python -m pip install -r requirements.txt
python train.py
python export_tflite.py --weights runs/hok_detector/weights/best.pt
python visualize.py --dir data/images/train
```

`train.py` 当前只有 MPS/CPU 自动选择，尚无 CUDA 分支；`visualize.py` 已知标签配对路径错误，修复前不能把“显示零框”当作标注为空。依赖只有版本下限，首次成功跑通后再记录或锁定实际验证组合。

Native 现有入口为在 `native-daemon/` 执行 `./build.sh`，需要 CMake 和 Android NDK；它会清空自己的 `build/` 并在发现设备后**自动 adb 推送**。构建与部署分离是待办，不要把它当纯构建命令运行。Android 可由 Android Studio 打开 `android-app/`；仓库尚无 `./gradlew`，不要给出不存在的可执行命令。

修改后的验证按风险选择：

| 修改范围 | 必要验证 |
|---|---|
| 文档 | 文件路径/链接、术语与状态一致、`git diff --check`；无需新增代码测试 |
| 数据/训练 | 标签合法性与配对、无划分泄漏、少量样本训练/加载检查 |
| 导出/推理/坐标 | 真实 tensor 检查、同图跨引擎对照、空结果/小框/越界/LetterBox |
| Native/Socket | 两端协议样例、异常尺寸、分段读写、断连重连、停止、NDK 编译 |
| Android/SDK | 实际 Gradle 构建、生命周期/权限/结果通道、GPU 与 CPU 回退、真机安装 |

缺少工具、数据或设备时，说明未执行的检查与前置条件；不要把静态审查或 host 语法检查当作 Android 构建/真机通过。不为低风险文档修改引入无关测试或依赖升级。

## 性能与完成标准

15fps 对应约 66.7ms 周期，仅是目标预算；截图 8ms、传输 2ms、预处理 3ms、推理 50ms、后处理 3ms 都没有真机测量支持。P2/960 是待比较的方案，不能保证极小目标一定可见或一定达到目标帧率。

验收采用VALIDATION的G1–G6与v1数值目标；DIAGNOSTIC只验证正确性，REALTIME才参加持续性能验收。记录设备/ROM、实际帧尺寸、模型与数据版本、后端、各阶段 p50/p95、采集到结果的帧龄、实际 FPS、丢帧、内存及持续运行温升。按模型输入中的目标像素尺寸分桶报告召回与误检，并记录中心坐标误差。先验证正确性，再依据实测优化；不提前承诺训练时长或固定天数交付。

完成说明必须区分：文件存在、源码审查、host 验证、Android 构建、真机联调、精度/性能验收。更新 PROGRESS 的证据和剩余项，并同步 CODEMAP 中相关断点。注释解释原因和约束，避免重复代码、遗留 12 类/nano/30fps 等过时描述。

## 完整规划的使用规则

- 从TASKS的26项核心任务按依赖推进；默认技术选择与失败分支已给出，不再次自行猜测协议、NMS布局、模型路径、错误code或状态语义。
- Native、Android、训练分别读取对应模块规格；字段/时钟/期限以CONTRACTS为唯一权威，CLI统一从仓库根目录调用（现有脚本旧用法与未来CLI必须分开标注）。
- 设备/ROM、模型转换和高帧率后端的未知项在指定探针任务获取证据；失败按已给路线处理，不能伪造通过或静默降门槛。
- 只有相关门禁PASS才关闭PROGRESS问题。“完整规划交付”“集成原型完成”“v1精度/实时交付通过”是不同状态。
- 修改规划中的接口或验收标准时，同步合同、模块规格、任务与测试；记录变更原因及影响，不只改一处默认值。
