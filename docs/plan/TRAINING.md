# 训练、数据与模型交付规划

本文件是待实施规格，当前没有新增训练代码、数据、权重或模型。现有实现仍以 [CODEMAP](../../CODEMAP.md) 为准；跨端接口以 [CONTRACTS](CONTRACTS.md) 为权威，任务顺序见 [TASKS](TASKS.md)。这里把训练模块的默认决策、文件级工作和验收条件定清楚，后续实现不需要重新猜标注语义、输出布局或量化策略。

初版保留单类 `enemy`、YOLOv8s-P2、960×960、内置 NMS、fp16 权重与 float32 I/O。下面的精度和误差数字是**建议采用的 v1 设计验收目标，均未达到或验证**，作为可用性设计基线，不是行业标准；验收数值及完成状态以 [VALIDATION](VALIDATION.md) 为最终权威。达不到时按第 10 节处理，不修改测试集来美化结果。集成里程碑不因精度不足而阻塞，但集成原型完成不等于产品验收通过。

## 1. 标注语义：初版直接采用的规则

检测对象是当前画面中能够可靠确认的敌方干员身体。框采用**可见部分的紧致外接矩形**，不推测遮挡后的全身；框坐标是原始截图连续像素边界，右下边界可等于图片宽高。写入 YOLO 时转换成 `0 cx cy w h`，归一化到 0–1。

| 场景 | 初版规则 | 复核要求 |
|---|---|---|
| 正常站立、移动、蹲伏、趴伏的敌方干员 | 标 `enemy`；瞄准镜内可见身体同样标注 | 画面必须提供可靠敌我依据，记录判断依据 |
| 敌方干员倒地但仍可救援/存活 | 标 `enemy`，仅框可见身体 | 不能区分倒地与死亡时，整帧进入待复核区 |
| 明确死亡的尸体、战利品盒、人物雕像/海报 | 不标，作为困难负样本 | 不把尸体与倒地活人混用同一规则 |
| 友军、自己可见的手臂/武器 | 不标；保留有队友标识的原画面上下文 | 不裁掉用于区分敌我的标记后继续声称标签可靠 |
| 部分遮挡、只露头部或肢体 | 能确认属于敌方干员则标可见部分；多个可见碎片属于同一人时用一个框包围 | 无法判断两处碎片是否同一人时复核，不拼接猜框 |
| 敌人被完全遮挡，仅有名字、红点、轮廓提示或位置标记 | 不以标记替代身体标注 | 清晰的实际身体轮廓可标；纯 HUD 图标是负样本 |
| 5–10px 甚至更小目标 | 能确认敌方身份和可见范围就标，尺寸本身不是排除条件 | 放大只能帮助看像素，不能创造细节；至少第二人复核 |
| 极小不确定点、敌我不明、死亡状态不明 | 不猜标，也不当作背景 | 进入 `review`；初版 YOLO 流程不支持忽略区域，未解决前整帧不进入 train/val/test |
| 训练场靶、非干员 NPC、菜单/结算/观战 UI | 不进入初版正样本；有代表性时作为独立负样本标签域 | 初版正式测试以实际对局第一人称画面为主，训练靶不能代替敌方干员 |

每个可靠可见敌人都必须标注。漏标一人会把他训练成背景，不能只标最显眼的一个。框不包含飘浮名字、血条、阴影和独立枪口闪光；贴身装备计入身体轮廓，伸出的长枪管不扩大身体框。画面边缘截断目标按可见范围标，记录 `truncated`。

空标签文件只表示“已检查、确认无 enemy”。缺标签文件表示工作未完成，数据预检必须失败。已解决为友军/尸体的争议样例保留复核记录；争议未解决的帧不作为负样本。初版从已确认画面学习敌我区分，不承诺在敌友外观完全相同且没有视觉上下文时仍可正确区分。

## 2. 数据布局、清单与分组划分

以下目录和文件是拟建布局，本轮不创建样本或清单内容：

```text
training/data/
  incoming/<sessionId>/                  原始采集，保持原文件
  review/                               未确定样例，禁止参与训练
  manifests/<datasetVersion>/
    frames.csv                          逐帧来源、身份、哈希与划分
    annotations.csv                     逐框属性与复核结果
    split-report.md                     分组分布、覆盖与泄漏审查
  images/{train,val,test}/               已批准的图片，文件名全局唯一
  labels/{train,val,test}/               与图片同名的 YOLO .txt
  dataset.yaml                          路径由脚本相对 YAML 所在目录解析
```

`frames.csv` 每帧一行，至少包含以下字段：

| 字段组 | 必填信息与约束 |
|---|---|
| 唯一身份 | `frameId`、`sessionId`、`groupId`、`datasetVersion`；`frameId` 永不复用 |
| 来源与时间 | `sourcePath`、`sourceType`、`captureTimestampMs` 或原视频帧号；缺时间时写明缺失原因 |
| 文件与尺寸 | 数据根目录相对 `imagePath`、`labelPath`、`imageSha256`、`labelSha256`、`width`、`height`；禁止绝对路径和 `..` 逃逸 |
| 场景覆盖 | `map`、`gameMode`、`lighting`、`motionBlur`、`enemyCount`、`negativeTags`、`device`、`gameVersion`；未知值显式标 `unknown` |
| 质量与归属 | `annotationStatus`、`reviewer`、`split`、`duplicateClusterId`；只有 `approved` 能进入训练数据 |

`annotations.csv` 按 `frameId + boxIndex` 与 YOLO 文件行对应，记录可见框原始像素坐标、敌我依据、`occluded`、`truncated`、`downed`、`tiny`、标注人和复核人。距离无法可靠取得时不编造米数，使用原图框尺寸和部署输入框尺寸作为客观分桶依据。

分组默认规则：同一对局的全部帧使用同一 `groupId`；对局 ID 无法取得时，将同一次连续录屏作为一个组。不能把同一录屏切成多个片段后跨集合。同一帧派生的裁剪、缩放、增强图与原图保持同组；跨文件重复/近重复簇也必须同组。

划分器按组分配，默认 seed `42`，目标比例 train/val/test 为 `70/15/15`，在不拆组的条件下兼顾地图、尺寸桶、负样本比例；可为达到测试覆盖调整比例并记录原因。清单必须记录实际组数与帧数比例；比例偏离不通过拆组修饰。首次划分要求每个集合至少 3 个独立组；不足时只允许带 `--purpose smoke` 的开发验证，不得签发精度验收。

冻结测试集后，新样例默认进入 train 或独立新增评估集，已有 test 不回流 train。一个模型系列持续针对同一测试集调参时，该测试集已经参与研发决策，下一次正式发布必须另采独立对局组成新的封存测试集并升级数据版本。INT8 校准只取 train 中的批准样例，不借用 test。

## 3. 数据预检、质量与覆盖

计划的 `validate_dataset.py` 在导入 PyTorch 之前完成下列检查；错误必须给出文件、行号与原因，并以非零退出码结束。

1. 解析 YAML、manifest 和 train/val/test 路径，核对单类名称，确认每个清单条目可解码、尺寸与 SHA-256 一致。重名、缺图、缺标、孤儿标签、清单中不存在的文件都是错误；明确空标是合法负帧。
2. 标签每行必须恰有 5 项、class 为整数 0、坐标有限、中心在 0–1、宽高大于 0，四条边在图片边界内，容许浮点写入误差 `1e-6`。重复框直接报错；同一帧高重叠框作为人工复核项，不自动删除近距离的两个人。
3. `groupId`、原始来源 ID、SHA-256 不能跨集合。感知哈希近重复是待复核候选，不作为自动判定不同内容的证据；近重复跨集合未解决前禁止正式训练。
4. 复核全部 `<8px` 输入短边目标、倒地/尸体争议和困难负帧；其余正帧至少随机复核 20%。任何抽查发现漏标后，回查该组全部帧，再重新计算标签哈希和数据版本。
5. 输出按组、地图、敌人数、遮挡、光照、负样本和输入尺寸桶的数量。已批准空帧建议占 20%–35%；明确包含友军、植被、建筑/人形纹理、尸体等混淆样例，不能只采干净背景。
6. 检查框在 960 部署预处理后的实际短边，保存 `<4px`、`4≤s<8px`、`8≤s<16px`、`s≥16px` 桶。尺寸计算使用第 7 节的实际横纵缩放；原图 5px 与模型输入 5px 必须区分。

200 帧可用于规则和闭环开发，1000+ 是持续迭代方向，均不自动满足第 9 节的测试覆盖。正式数据量由覆盖与独立测试要求决定，1000 帧未必足够；连续录屏多抽几百张近邻帧不能替代增加独立对局。

## 4. 模型加载与训练入口设计

不再把不存在的 `yolov8s-p2.pt` 当成默认可下载权重。初版从 Ultralytics 包内 `yolov8s-p2.yaml` 构建 small/P2 模型，按相同版本的官方 `yolov8s.pt` 迁移形状兼容权重。官方 [P2 YAML](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/cfg/models/v8/yolov8-p2.yaml) 定义了 P2/4–P5/32，包加载器根据模型名选择 `s` 规模；[官方下载列表](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/utils/downloads.py) 列出标准 YOLOv8 权重。初始化后须检查实际 scale、4 个检测层和 stride `[4,8,16,32]`，单类训练后的 head 必须为 `nc=1`。

迁移加载需要输出已加载/未加载参数数量，保存来源 URL、下载 SHA-256 与版本。P2 新增层及不兼容预测层按明确 seed 初始化，不能称为“完整 P2 预训练模型”。`--scratch` 显式选择随机初始化，不能在下载失败时静默回退。项目保留官方权重缓存；用户给本地权重时先校验其任务、结构和类别，禁止仅按扩展名判断兼容。

以下是拟实现的 CLI，当前脚本尚不支持这些参数。统一从仓库根目录调用；数据路径由配置文件位置解析，产物路径由显式参数解析，不能依赖终端恰好处于 `training/`。

| 入口 | 计划参数 / 默认值 | 行为 |
|---|---|---|
| `training/validate_dataset.py` | `--data`、`--manifest`、`--purpose smoke\|release`、`--report` | 质量、配对、分组、覆盖检查，区分错误和覆盖不足 |
| `training/split_dataset.py` | `--manifest`、`--seed 42`、`--ratios 70 15 15`、`--dataset-version`、`--output` | 按组生成划分提案与报告；已冻结版本禁止原地重划 |
| `training/train.py` | `--data`、`--manifest`、`--model yolov8s-p2.yaml`、`--pretrained yolov8s.pt` 或 `--scratch` | 数据预检通过后加载模型；记录模型结构与迁移报告 |
| 训练规模 | `--imgsz 960`、`--epochs 150`、`--batch 4`、`--workers 2`、`--device auto`、`--seed 42` | batch 4 是保守开发起点，不保证任意 Mac 内存均足够；OOM 时退出并建议降 batch |
| 实验管理 | `--project training/runs/delta_enemy`、`--run-id <UTC日期时间>_s42`、`--resume <last.pt>` | 默认独立 run；显式名称已存在就失败；resume 校验数据/模型/配置，禁止覆盖不同实验 |
| 基线优化器 | `--optimizer AdamW`、`--lr0 0.001`、`--patience 20`、`--amp auto` | 初版固定配置；CUDA 开 AMP，MPS/CPU 默认关闭并记录；不在失败后静默切换设备 |
| `training/visualize.py` | 保留 `--image/--dir/--yaml`，增加 `--output-dir`、`--seed 42` | 正确匹配 `images/<split>` 与 `labels/<split>`；支持保存图供无 GUI 环境复核 |

`--device auto` 顺序为可用 CUDA → MPS → CPU；显式设备不可用则报错。CUDA 初版只选单卡 `cuda:0`，多卡训练后置。固定 Python/NumPy/PyTorch seed、数据加载器 seed 和 deterministic 配置，但不承诺 MPS、CUDA、CPU 或不同库版本训练逐位相同；记录实际运行差异。

训练默认关闭随机空间变换：`degrees=0`、`translate=0`、`scale=0`、`shear=0`、`perspective=0`、`fliplr=0`、`flipud=0`、`mosaic=0`、`mixup=0`、`copy_paste=0`，且 `rect=False/multi_scale=False`；避免首版破坏敌我 UI 与极小目标语义。先启用轻量色彩扰动（建议 HSV `h=0.01/s=0.2/v=0.2`）并保存实际 Ultralytics 完整训练配置；后续一次只比较一个增强策略。随机增强只作用于 train，val/test 使用确定性部署预处理。

每个 run 计划保存：`args.yaml`、解析成绝对路径的 `dataset.resolved.yaml`、数据清单及哈希、模型 YAML/迁移报告、依赖锁文件与平台信息、Git revision/dirty 状态、seed、训练日志/曲线、`weights/{best,last}.pt`、评估报告。`best.pt` 根据 val 指标选择，test 不参与 early stopping。原有 `hok_detector` 不自动迁移或覆盖，改名后旧产物需显式 `--weights` 指定。

## 5. 可复现环境候选与导出兼容探针

初版候选固定 Ultralytics `8.3.200`，原因是它的导出源码已按明确版本核对；**这不是经过本项目实测的可用环境**。训练和导出分离，避免 TensorFlow/转换器升级影响训练环境。

| 环境 | 候选起点 | 晋升为可用基线的条件 |
|---|---|---|
| macOS 训练 | Python 3.11、Ultralytics 8.3.200、PyTorch 2.5.1 / torchvision 0.20.1、NumPy 1.26.4 | 解析依赖、`pip check`、MPS/CPU 模型加载、少量真实样例训练通过；保存 OS/架构/Python patch 与全部包精确版本 |
| Linux x86_64 CPU 导出 | Python 3.11、相同 Ultralytics/PyTorch 组合；TensorFlow 2.19.0、tf-keras 2.19.0、onnx2tf 1.26.3、ONNX 1.17.0、ONNX Runtime 1.20.1、ai-edge-litert 1.2.0 | 先完成依赖求解和导出探针，再生成含所有传递依赖/哈希的锁文件及镜像 digest；版本冲突时调整候选并留下原因 |
| Android 执行端 | 以 Android 规划的实际 TFLite runtime 为准，初版仓库为 2.14.0 | 新导出模型必须被该 runtime CPU 真正加载和执行；桌面 TensorFlow 可加载不能替代此检查 |

PyTorch 2.5.1/torchvision 0.20.1 是[官方发布配对](https://pytorch.org/get-started/previous-versions/)。转换器还依赖 tf-keras、ONNX 工具及其他传递包，不能仅固定表中几项就宣称完整锁定。安装和探针分别记录；导出任务运行时关闭自动安装依赖，缺包应明确失败，避免同一命令跨天产生不同环境。

官方 `8.3.200` exporter 在 `nms=True` 时**拒绝 ARM64 Linux 的 TFLite 导出**，故 Apple Silicon 上默认 `linux/arm64` 容器不作为首选路径；首选明确的 Linux x86_64 导出机/环境，macOS 原生导出只有探针通过后再支持。此限制和 TensorFlow/转换器要求来自[固定版本 exporter](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/engine/exporter.py)，不通过切换 `nms=False` 绕开当前契约。

拟建 `probe_export.py` 的检查流程：

1. 校验版本白名单、平台、依赖完整性和 `torchvision.ops.nms` 可执行；记录 exporter/head 源码版本与文件哈希。未知版本拒绝正式交付，先单独审查与探针；不从一个同名 tensor 猜兼容。
2. 加载 small/P2 配置，验证 stride 与类别；使用开发权重试导出。如果仅随机初始化，产物只能用于兼容探针，报告标 `untrained`，不进入正式模型包。
3. 明确传入 `format=tflite, imgsz=960, batch=1, dynamic=False, half=True, int8=False, nms=True, conf=0.001, iou=0.5, max_det=300`；检查 exporter 未覆盖这些值。
4. 验证真实文件存在、TFLite 单输入单输出、静态形状和 float32 I/O；CPU 分配/执行后检查有限值。单次全零输入成功只证明可执行，不能证明布局、类别、精度或 NMS 正确。
5. 使用已知非空真实截图及第 8 节的对照适配器验证 normalized xyxy、score、class 和 NMS；验证空结果与 padding。没有可确认输出的样例时，语义探针未完成。
6. 在 Android 目标 runtime 上 CPU 加载/执行；若遇到不支持的 op/op-version，记录并调整转换配置或 Android 依赖后重新跑全部检查，禁止把依赖失败描述为“模型没检测到人”。

## 6. 导出产物与元数据

拟实现入口 `training/export_tflite.py` 接收 `--weights`、`--model-version`、`--sdk-min-version`、`--imgsz 960`、`--output-dir`、`--reference-manifest`；`--int8` 额外要求 `--calibration-manifest`。默认 fp16 权重，I/O 必须 float32。`modelVersion` 和 `sdkMinVersion` 是正整数；发布版本显式提供，不能从文件修改时间猜版本。

实际导出在每个 run 的独立暂存目录执行，避免上游工具清空同名 `_saved_model` 目录时覆盖旧发布产物。消费 `model.export()` 的实际返回路径，检查文件确实属于本次暂存区；不能扫描目录后取“第一个 .tflite”。官方版本可能返回 `best_saved_model/best_float16.tflite`；这只是源码示例，不成为查找规则。

正式输出目录计划为 `training/runs/delta_enemy/<run-id>/exports/<modelId>/`，仅在检查通过后包含 `model.tflite`、`model.tflite.json`、检查与一致性报告、环境/权重/数据来源记录。复制到 Android assets 是独立显式参数或交付步骤，模型与 sidecar 必须作为一对校验后复制；不直接覆盖正在验证的旧版。Android 首版打包目标仍为 `android-app/app/src/main/assets/`。

sidecar v1 以 [CONTRACTS](CONTRACTS.md) 为权威，训练交付端必须提供：

| 字段 | 初版值 / 规则 |
|---|---|
| `schemaVersion` | 整数 `1` |
| `modelId` | 与最终模型内容绑定、不可复用或原地修改；统一采用 `enemy-v<modelVersion>-<SHA256前16位>`；完整SHA-256另存sha256字段，同版本不可变 |
| `modelVersion` / `sdkMinVersion` | 显式正整数；同一版本禁止指向不同内容 |
| `sha256` / `labels` | 最终模型全部字节的 SHA-256；类别严格为 `["enemy"]` |
| `input` | `shape=[1,H,W,3]`、`dtype=float32`、`colorSpace=RGB`、`normalization=zero_to_one` |
| `input.letterbox` | `padValue=114`、`resize=bilinear`、`sizeRounding=floor`、`padAlignment=center` |
| `output` | `shape=[1,300,6]`、`dtype=float32`、`layout=xyxy_score_class`、`coordinates=normalized`、`nms=true` |
| `export` | 实际 `ultralyticsVersion`、`conf=0.001`、`iou=0.5`、`maxDet=300` |

初版 H/W 均为 960。导出阈值 0.001 用于保留评估与端上阈值调整空间，**不是显示阈值**；端上默认工作阈值为 0.45，实际阈值根据 val 选择并冻结。NMS 已在模型内执行，Android 不二次 NMS；其 IoU 和 maxDet 已固化于模型。

布局语义的依据必须同时包括固定 exporter 行为和实际对照测试：[v8.3.200 detection head](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/nn/modules/head.py) 在 TFLite 路径归一化框坐标；相同版本 exporter 启用 xyxy 并包装 NMS，每行输出框、分数、类别并补零。一般 raw 输出 `4+nc` 与这种 `[1,300,6]` 不同。尺寸与 dtype 可由 interpreter 检查，布局/NMS/坐标语义不能只靠 shape 宣称成立。

候选 sidecar 只在本次真实产物通过桌面 tensor 检查和语义对照后生成，放在暂存包内；随后 Android 用这一对实际模型/sidecar 验证目标 runtime，成功才晋升正式输出目录。这样不会形成“Android 必须有 sidecar 才能加载，但加载前又不允许生成 sidecar”的循环依赖。不能为尚未导出的模型生成假 sidecar。dtype 不兼容、标签不同、动态形状、输出数不同、NMS 状态不符或包含目标 runtime 不支持的 op 时明确拒绝正式交付，保留失败报告；诊断包与可发布 assets 分开。

## 7. 部署共用预处理与坐标

输入原图宽高为 `origW/origH`，模型宽高为 `inputW/inputH`。使用以下确定性规则，Python 部署参考、离线评估和 Android 必须一致：

- 先验证原图尺寸为正且在合同上限内；`r = min(inputW/origW, inputH/origH)`，`newW=max(1,floor(origW*r))`，`newH=max(1,floor(origH*r))`。极窄图的最小 1px 保护与 CONTRACTS 保持一致，随后使用真实缩放率。
- `padX=(inputW-newW)//2`，`padY=(inputH-newH)//2`；奇数余量留在右侧/底部。填充 RGB 114，缩放为 bilinear，输入按 RGB 顺序除 255 后为 NHWC float32。
- 保存真实 `scaleX=newW/origW`、`scaleY=newH/origH`；不要在坐标回映时都使用理想 `r`。原图尺寸、缩放尺寸和 padding 跟随每帧处理结果。
- bilinear 参考采用像素中心采样、边缘 clamp；颜色输出四舍五入为 uint8 再归一化。Python 与 Android 使用各自库时须通过像素对照，不因同名“bilinear”就认定完全一致；超过第 8 节容差则实现明确的公共参考算法。
- 模型输出坐标先分别乘 `inputW/inputH`，排除中心位于有效画面外、落在纯 padding 的框，再减 padding、除对应实际 scale。框用于评估时保留连续原图坐标，裁剪到 `[0,origW]×[0,origH]`；SDK 取裁剪后框中心的 floor，并限制到合法像素范围，不将 width/height 当可点击的最后像素。

注意 Ultralytics 8.3.200 的 [LetterBox](https://github.com/ultralytics/ultralytics/blob/v8.3.200/ultralytics/data/augment.py) 默认对缩放尺寸使用 `round`。正式一致性测试应注入同一个已预处理 tensor，不能直接拿其默认 `predict(path)` 当端上逐像素真值。训练 pipeline 的无随机增强路径、val/test 评估适配器也应接入部署共用预处理；内置训练 mAP 可作诊断，正式门槛由部署评估器输出。

| 原图 → 960 输入 | resize | 左/上 padding | 实际 scaleX / scaleY |
|---|---|---|---|
| 3200×1440 | 960×432 | 0 / 264 | 0.3 / 0.3 |
| 3199×1439 | 960×431 | 0 / 264；底部为 265 | 960/3199 / 431/1439；两者不同 |
| 1440×3200 | 432×960 | 264 / 0 | 0.3 / 0.3 |

归一化坐标的合同样例：3200×1440 原图，模型框 `[0.3,0.425,0.5,0.575]` 的模型输入像素框为 `[288,408,480,552]`，回映原图为 `[960,480,1600,960]`，中心为 `(1280,720)`。该样例只验证公式，不是检测结果或伪造训练样本。

K70 若实际截图为 3200×1440，原图 5–10px 仅约 1.5–3px 输入；增加 P2 不会恢复缩放丢失的信息。输入短边分桶必须基于实际截图，不能用手机标称分辨率代替每帧尺寸。

## 8. 离线一致性测试设计

拟建共享输入集至少覆盖：明确非空、明确空帧、极小目标、相互重叠目标、边界截断、奇数宽高、横竖屏、padding 区域。真实截图须来自批准的数据清单；几何单元测试可以用人工构造坐标，但不能把它们计入模型精度样本。

按三层验证，避免把模型误差、预处理差异和坐标错误混为一谈：

| 层级 | 参考与做法 | 初版验收目标 |
|---|---|---|
| 几何/像素 | Python 与 Android 分别对同一原图处理，比较 resize/padding/实际 scale 和 RGB tensor | 几何参数完全一致；padding 值精确；像素最大误差 ≤2/255、平均绝对误差 ≤0.25/255。错误色序不能靠均值掩盖 |
| 引擎输出 | 向 PyTorch、桌面 TFLite CPU、Android CPU 注入同一 float32 tensor；PyTorch 参考适配器将 raw 像素 cxcywh 转 normalized xyxy，使用相同 conf/IoU/maxDet 的 NMS | 按类一对一匹配稳定框；CPU 坐标最大差 ≤0.5 模型输入像素、score 差 ≤0.02，稳定框匹配率 ≥99%。这些是待验证预算 |
| Android GPU | 在相同模型、tensor、工作阈值下对照 Android CPU，再检查预处理和原图回映的完整输出 | GPU 坐标最大差 ≤1 模型输入像素、score 差 ≤0.03，稳定框匹配率 ≥99%；初始化或执行不支持则清晰记录 CPU 回退 |

稳定框指分数距离工作阈值至少 0.05，相关候选 IoU 距离 NMS 阈值至少 0.02，且不存在等分数竞争的框。临界框单独计数与保留原始输出，不能简单丢弃后宣称精度不下降。正式测试集仍评价**全部**结果，并执行第 9 节总体门槛和 fp16 相对退化门槛。

空结果的补零行必须被过滤；有效行应具有有限的坐标和 0–1 分数、合法 classId，`x2>x1/y2>y1`。非法 class/非有限值的非 padding 行按 CONTRACTS 使本帧失败，不能伪装成一次正常空结果。归一化空间允许原始预测轻微超边界，按合同处理并记录；不能拿中心已在纯 padding 的框直接裁剪成一个高置信度目标。对框排序不作输出逐行恒等要求，NMS 离散选择用匹配和临界报告评估。

输出报告保留模型与图像哈希、输入 tensor 哈希、各引擎输出、匹配对、归一化差值、输入像素差值、原图中心差值、运行后端和时间。若 CPU 已不一致，先停止 GPU 调优；若共用 tensor 一致而端到端不一致，优先查 RGB/缩放/取整/坐标映射。

## 9. 初版精度门槛与量化决策

正式测试建议至少 300 帧、10 个独立对局，其中至少 100 帧为已确认无 enemy；各输入短边桶至少 100 个已复核框且分布在至少 3 个对局。样本不足标“证据不足”，不能以少量全对样本签发通过。比例与组数限制可能要求扩大总体采集量。

默认匹配采用单类一对一 IoU≥0.5；按置信度降序匹配，每个 GT 最多匹配一次。各尺寸桶的 recall 按 GT 尺寸归属；每帧误检和全量 precision 使用全部预测。预测框尺寸桶的 precision 另列，说明其分母基于预测尺寸，避免把无匹配误检错误归到 GT 桶。

| 指标 | v1 建议目标（以 VALIDATION 为权威） | 使用方式 |
|---|---|---|
| 全量 precision / recall | ≥0.95 / ≥0.80 | 使用 val 冻结的单一工作阈值，test 不重新选阈值 |
| `s≥16px` recall | ≥0.90 | 近/较大目标基线 |
| `8≤s<16px` recall | ≥0.85 | 小目标 |
| `4≤s<8px` recall | ≥0.75 | 很小目标 |
| `s<4px` recall | ≥0.60 | 极远/信息受限目标；覆盖或召回不足时不能宣称全距离 v1 完成 |
| 明确空帧平均误检数 | ≤0.05 框/帧 | 与置信度阈值一起报告 |
| 含误检的空帧比例 | ≤5% | 防止少数空帧大量误检被平均值隐藏 |
| 匹配目标中心误差 | 按模型输入像素计算 `p95[e/max(2px,0.1×GT对角线)]≤1` | 另报原图像素与各桶分位数；未匹配目标不能混入“零误差” |
| 全量 mAP50 / mAP50–95 | 均必须报告 | 暂不单独设总 mAP 门槛，防止大目标表现掩盖远距召回 |

每项报告 numerator/denominator 与按对局重采样的 95% 区间；v1 以点估计作门槛，同时披露不确定性。任一必须桶覆盖不足或门槛未达，禁止声称“全距离检测通过”；可以交付明确标注的开发基线并说明已支持范围。集成链路已经跑通时记“集成原型完成 / 产品验收未通过”，不回退或虚构集成状态。

fp16 必须先通过第 8 节，且相对同 checkpoint 的 PyTorch 部署参考，全量 precision/recall 下降各不超过 1 个百分点，各小目标桶 recall 下降不超过 2 个百分点；同时满足上表绝对门槛。误差超限先查流程，不能立即归咎于半精度。

INT8 进入条件为 fp16 已建立完整正确性、精度和 K70 性能基线，且存在可量化的内存或延迟瓶颈。要求显式校准清单：从 train 分层抽取至少 300 帧、至少 5 个对局，覆盖全部尺寸桶、光照和至少 20% 明确负帧；不足则拒绝 release INT8。校准输入必须经过同一部署预处理，不能让上游默认 calibration data、round 或直接拉伸替代清单对应的 tensor；必要时新增独立、受测的校准适配器并记录版本。

INT8 首版仍只接受 float32 I/O 的产物，不因文件名含 int8 就改变 Android 输入。除绝对门槛外，相对 fp16 的全量 precision/recall 最多下降 1 个百分点，任何尺寸桶 recall 最多下降 2 个百分点，空帧误检最多增加 0.02 框/帧；K70 同一后端/运行条件下还须取得至少 15% 的推理 p95 改善，或至少 25% 的实际模型常驻内存下降。两类收益至少一项达成且持续性能无回退才替换 fp16；模型文件更小本身不等于常驻内存更小。

## 10. 未达标时的迭代路线

按问题定位推进，保持同一测试口径：

1. **样本或语义不可靠**：先复核错误样例，补齐敌我、倒地/尸体和极小框规则；修标签/划分后升级数据版本，重新运行基线。不要先堆模型容量。
2. **同图跨引擎不一致**：固定 tensor 先排除形状、dtype、NMS、RGB 和 normalized 坐标问题；再看转换 op 与数值误差。此阶段不以重新训练掩盖解析错误。
3. **友军/植被误检高**：按错误类型采集新的独立对局负样本，扩大完整上下文，复核遗漏的敌人；只用 val 选择阈值。提高阈值必须同时报告各小目标桶召回损失。
4. **4–16px 桶召回低**：优先补该桶独立样例、遮挡/运动模糊覆盖与标注质量，再比较 n/s P2、训练采样和单项增强；每个实验改变一个主要因素。
5. **<4px 桶持续失败**：先复核标注并补充独立难例，再确认信息损失是否来自全屏压缩，比较 960 与 1280 输入和 n/s P2 及其 K70 延迟。若仍不足，单独做 ROI/切片实验，保留全图上下文用于敌我判断，并记录额外帧龄、重叠框融合和边界漏检。所有对照使用相同误检预算与 15fps 评估条件；无法同时达标时交付精度/延迟 Pareto 结果，记“集成原型完成 / 产品验收未通过”，不能悄悄降低验收门槛。此策略需扩展合同/端上 pipeline 后才能用于正式模型，初版不预先堆入。
6. **精度通过、持续速度不足**：使用同组数据比较 n-P2/不同尺寸、采集策略和后端；先保持 float32 I/O/fp16，再按门槛比较 INT8。不得只报告静态单帧推理而忽略游戏并行时的持续性能。

原图 1.5–3px 等效输入的小目标可能没有足够视觉证据，低于门槛时应明确能力边界并保留失败证据。继续采集和提高分辨率是实验方向，不能保证一定恢复信息。

## 11. 文件级实施顺序与模块验收

以下全是拟建/拟改内容，本轮仅编写本规划：

| 顺序 | 文件 | 计划工作 | 验收证据 |
|---|---|---|---|
| T1 | `training/data/README.md`、`dataset.yaml` | 落实标注语义、路径解析、manifest 与版本约定 | 清单字段/目录一致，不创建伪造游戏数据 |
| T2 | `dataset_tools.py`、`validate_dataset.py`、`split_dataset.py`；修 `visualize.py` | 配对、严格标签校验、按组划分、覆盖报告、可视化导出 | 纯 Python 合法/非法标签、空标/缺标、路径、重复、跨组泄漏测试；真实样例人工复核 |
| T3 | `train.py`、`requirements-train.txt`、平台锁文件 | CLI、YAML/权重策略、设备/seed、run 不覆盖、训练记录 | 不依赖重型包的 `--help`；缺数据预检失败；P2 加载与少量真实样例 smoke run |
| T4 | `preprocess_reference.py`、`tests/` | 部署 floor LetterBox、实际 scale、颜色与归一化、坐标黄金样例 | 奇数宽高与横竖屏几何精确；与 Android 共享样例对照 |
| T5 | `probe_export.py`、`requirements-export.txt`、导出锁文件 | 固定版本/平台/依赖检查，CPU/目标 runtime 兼容探针 | 依赖求解与完整锁定、真实导出/加载/执行报告 |
| T6 | `export_tflite.py`、`model_contract.py` | 暂存导出、返回路径、实际张量与语义校验、sidecar、原子模型包 | 不兼容形状/类型/版本明确失败；成功才有匹配 SHA-256 的模型包 |
| T7 | `evaluate.py`、`compare_backends.py` | 同 tensor 对照、尺寸分桶/空帧/中心误差、量化比较 | 固定清单/阈值/匹配规则，报告覆盖和所有门槛，不只输出 mAP |

T1–T3 可与 Android 构建并行；T4 必须和 Android 预处理同时对齐；T5–T6 先交付可追溯的真实 smoke 模型，之后 T7 完成跨引擎与精度验收。没有数据时可完成数据工具和几何测试，但不能完成训练/导出语义/精度门槛。没有 root 不阻止离线工作；K70 实际游戏采集和持续性能仍按主计划等待设备条件。

模块完成记录必须列出：已实施文件、执行命令、环境锁版本、输入数据/模型哈希、测试结果及仍未通过的门槛。候选环境、兼容探针成功、正式精度通过、K70 持续性能通过是四个不同结论，不合并为一句“训练完成”。
