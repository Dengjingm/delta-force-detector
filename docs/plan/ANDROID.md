# Android 开发与验收计划

本文是未来实现规格，不表示列出的类、接口、测试或构建命令已经存在。本轮仅完善文档。当前实现导航见 [CODEMAP](../../CODEMAP.md)，现有缺陷见 [PROGRESS](../../PROGRESS.md)。跨模块格式以 [CONTRACTS](CONTRACTS.md) 为唯一权威；本文件规定 Android 如何消费这些格式及组织运行生命周期。

## 1. 交付目标与设备边界

首版交付可以独立安装的演示 APK，随后从验收通过的实现拆出 AAR。APK 提供模型检查、离线截图检测、显式启动/停止实时检测和诊断信息；SDK 输出原始采集帧上的框、中心坐标、置信度及帧时间，不自动点击、不自动滑动、不自动执行瞄准或射击动作。

首台设备为用户提供的 Redmi K70、Snapdragon 8 Gen 2、3200×1440、12 GB 物理内存 + 4 GB 扩展内存。root 后续进行。Android API、ROM/HyperOS、root 实现、实际显示/游戏/截图分辨率、GPU delegate 兼容性必须由设备探测记录确认。扩展内存不计作额外物理 RAM；型号不等于 15 fps 或持续运行温度已经达标。

先完成不依赖 root 的构建、模型契约和离线推理，再接入 root 截图链路。root 尚未完成不阻止前面几个交付阶段。

两个运行 profile 固定定义如下，不允许 UI 用同一个“运行中”文案掩盖差别：

| Profile | 采集目标 | 最大可用帧龄 | Socket 读帧超时 | 用途 |
|---|---:|---:|---:|---|
| `REALTIME`，SDK 默认 | 15 fps | 250 ms | 1 s | 正式实时性能验收 |
| `DIAGNOSTIC`，首次 root 联调显式选择 | 5 fps | 5000 ms | 5 s | CLI 截图和协议正确性检查 |

诊断模式界面持续显示“诊断模式，不用于 15 fps 验收”。正式验收目标为有效结果 FPS ≥ 14.5、运营帧龄 p95 ≤ 150 ms / p99 ≤ 250 ms；测试时长、温度及精度条件以 [VALIDATION](VALIDATION.md) 为准。这些是未来验收门槛，当前没有实测结果。

## 2. 目标结构与职责

```text
示例 Activity / 宿主 App
  └─ ScreenVisionSDK
      ├─ RuntimeCoordinator：唯一状态写入者、命令串行化、过期清理
      ├─ LocalBinder：连接 Service、返回同一只读 Flow 与命令接口
      └─ DetectionService：前台生命周期与本次运行资源
          ├─ DaemonController：root 探测、部署检查、启动、精确停止
          ├─ DisplayStateWriter：主显示状态、原子控制文件、显示世代失效
          ├─ FrameReader：可取消 Socket IO、v2 校验、完整帧交付
          ├─ LatestFrameSlot：最多一个待处理帧，替换时释放旧资源
          └─ InferenceWorker：单一专用线程
              ├─ ModelRepository / ModelContract：选择、校验、加载模型
              ├─ Preprocessor：共享 LetterBox 参数及 RGB float32 buffer
              ├─ YOLODetector：Interpreter 与 delegate 全生命周期
              └─ PostProcessor：已 NMS 记录解析、原图框与中心坐标
```

- `RuntimeCoordinator` 是进程级内部组件，所有状态变更通过其串行 reducer 执行。Service、SDK 不再分别维护 `isStarted`、`isRunning` 或独立的最新检测缓存。
- Service 和 SDK 同进程；`onBind()` 返回合法 `IBinder`，`LocalBinder` 暴露协调器的只读 `StateFlow` 和受控命令。Flow 本身不是 Binder。SDK 对绑定后的同一状态源提供稳定只读接口；不在第二个 `MutableSharedFlow` 中重建另一套检测状态。
- Service 的创建和连接是事件，不能用固定 500 ms 延时假定绑定成功。绑定失败、失联和取消必须进入同一状态机。
- 推理线程拥有 Interpreter、GPU delegate、预处理可复用 buffer；Socket 线程不能运行推理，Main 线程不能阻塞等待 root、Socket 或推理。
- 首版 AAR 继续要求 Service 同进程。本计划不加入跨进程 AIDL；若未来需要，作为独立接口版本设计，不把本地 Binder 强转当 IPC 支持。

## 3. 公开 API 与数据模型

下列 Kotlin 是目标签名示意，需在实现阶段变成编译通过的声明。当前原型尚无对外发布的稳定二进制；新版 `start/stop` 和结果类型需要迁移示例与文档。现有 `tap/swipe` 保留为独立显式调用，不由任何检测回调触发。

```kotlin
object ScreenVisionSDK {
    fun snapshots(): StateFlow<VisionSnapshot>
    fun frames(): SharedFlow<DetectionFrame>
    fun observe(): SharedFlow<List<DetectResult>> // 旧中心坐标接口的兼容投影

    suspend fun inspect(context: Context, includeRoot: Boolean = false): ReadinessReport
    suspend fun start(context: Context, config: StartConfig = StartConfig()): StartOutcome
    suspend fun stop(): StopOutcome
    suspend fun analyzeImage(context: Context, uri: Uri, config: OfflineConfig = OfflineConfig()): OfflineOutcome

    suspend fun stageModel(context: Context, bundle: ModelBundle): StageOutcome
    suspend fun activateModel(modelId: String): ActivationOutcome

    @WorkerThread fun tap(x: Int, y: Int): Boolean
    @WorkerThread fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 100): Boolean
}

data class StartConfig(
    val profile: EngineProfile = EngineProfile.REALTIME,
    val model: ModelSelection = ModelSelection.ActiveOrBundled,
    val backend: BackendPreference = BackendPreference.AUTO,
    val confidenceThreshold: Float = 0.45f,
)

enum class EngineState { STOPPED, STARTING, RUNNING, RECONNECTING, STOPPING, ERROR }
enum class EngineProfile { REALTIME, DIAGNOSTIC }
enum class BackendPreference { AUTO, CPU, GPU_REQUIRED }
enum class InferenceBackend { CPU, GPU }

data class VisionSnapshot(
    val revision: Long,
    val state: EngineState,
    val profile: EngineProfile,
    val sessionId: Long?,
    val latestFrame: DetectionFrame?,
    val error: VisionError?,
    val modelId: String?,
    val backend: InferenceBackend?,
    val updatedAtNs: Long,
)

data class DetectionFrame(
    val revision: Long,
    val sessionId: Long,
    val streamId: Long,
    val frameId: Long,
    val captureStartNs: Long,
    val captureEndNs: Long,
    val resultNs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val modelId: String,
    val backend: InferenceBackend,
    val profile: EngineProfile,
    val detections: List<Detection>,
)

data class Detection(
    val classId: Int,
    val className: String,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val centerX: Int, val centerY: Int,
    val confidence: Float,
)
```

补充类型的固定语义：

| 类型 | 目标语义 |
|---|---|
| `ModelSelection` | `ActiveOrBundled` 或按不可变 `modelId` 指定已验证的本地模型；不接收任意远端 URL 并自动加载 |
| `OfflineConfig` | 模型选择、后端偏好、阈值；复用实时的预处理和后处理，不包含 root、daemon、profile |
| `StartOutcome` | `Ready(snapshot)`、相同配置的 `AlreadyRunning(snapshot)`、`Rejected(error)`；不同配置对运行中的引擎返回 `BUSY`，不偷偷重启 |
| `StopOutcome` | `Stopped`、`AlreadyStopped` 或 `Failed(error)`；只有资源释放完成后返回 `Stopped` |
| `VisionError` | `code`、适合 UI 的 `message`、`recoverable`、`cause` 摘要，可附诊断 ID；`recoverable=true` 表示条件修复后可再次显式调用，不表示无限重试 |
| `ReadinessReport` | 模型/契约、API/ROM、前台启动条件、root、daemon、Socket 的逐项结果；未探测记 `NOT_CHECKED`，不伪报通过 |
| `OfflineOutcome` | 原图尺寸、图像标识、模型/后端、框与中心、预处理/推理耗时或明确错误；没有 Native 帧 ID 和采集时间 |
| `ModelBundle` | 用户显式选择的本地模型/metadata 文件对，或按 C07 获取的已知来源下载包；URI 只用于内容读取，不作为 shell 参数/目标路径 |
| `StageOutcome` | `Staged(modelId)` 仅表示文件完整、metadata schema/SDK 初检通过，明确标记尚未 runtime 验证；不改变 active |
| `ActivationOutcome` | `Activated`、`RolledBack(previousModelId, error)` 或无可用版本时 `Failed` |

`streamId` 的 Long 保存任意非零 wire `uint64` 位模式，仅比较等值，日志可用无符号字符串；不能将其最高位当成协议负值。`frameId` 与时间必须处于正 Long 范围，帧序按正 Long 单调比较。`revision` 是本 App 进程内递增的状态序号，`frame.revision` 等于发布它的 snapshot revision。每次接受新的 start 创建新的非零正 Long `sessionId`，由协调器分配并与旧会话区别；初始 STOPPED 的 sessionId 可为 null。旧会话事件一律丢弃，重连只换 stream、不换 session。Native 的 owner token 是另行生成的 32 hex 随机所有权标识，不拿 sessionId 文本充当 token。App启动前原子保存C08 daemon-owner记录；App崩溃后，下次用户显式start用旧记录核验并精确停止自有残留实例，再分配新token，不能永久BUSY或接管活跃的其他owner。

`classId=0`、`className="enemy"` 由模型契约校验后赋值，没有稳定 tracking ID，不把 `frameId` 或列表下标当目标身份。

`snapshots()` 是唯一权威的当前状态。`frames()` 仅广播真实完成且未过期的检测帧，采用 `replay=0`、容量 1 和 `DROP_OLDEST`，不能作为“最新目标永远有效”的缓存。慢消费者根据 session/revision 和 profile 的最大帧龄再次检查；队列不得反压推理主循环。

`observe()` 从 snapshot 投影 `latestFrame?.detections.orEmpty()` 并映射到既有 `DetectResult(elementId,x,y,confidence)`，分别取 className、centerX、centerY、confidence；通过共享投影提供原签名，不能反向写入协调器或维护独立有效期。新调用方使用 snapshot 获取框与错误；兼容接口的空列表同时可能表示无目标或当前无可用帧，不能靠它判断引擎健康。

## 4. 状态机、结果有效期与命令完成

| 当前状态/触发 | 下一状态 | 必须执行的动作 |
|---|---|---|
| `STOPPED/ERROR` + 显式 start | `STARTING` | 新 session、清错误和旧帧；顺序执行前台启动、模型/root/daemon 检查 |
| `STARTING` + 模型核验、worker 就绪、首个完整合法 v2 帧已收到 | `RUNNING` | start 返回 Ready；开始处理最新候选，当前可没有已发布结果 |
| `STARTING` + 缺模型/root/权限、超时或配置不支持 | `ERROR` | typed error、清 latestFrame、清理本次部分创建资源；允许修复后显式重试 |
| `RUNNING` + 完成新鲜推理 | `RUNNING` | 原子更新 snapshot；随后发布同 revision 的 frame event，包括真实空检测列表 |
| 任意有 latestFrame 的状态 + TTL 到期 | 状态不变 | latestFrame 置 null、revision 递增；不捏造 DetectionFrame |
| `RUNNING` + Socket 断连/读帧超时 | `RECONNECTING` | 立即清 latestFrame 和候选槽，废弃当前 stream 的未完成结果 |
| `RECONNECTING` + 新连接首个完整合法帧 | `RUNNING` | 新 streamId，从该连接首帧建立序列检查；旧 stream 结果不得回流 |
| `RECONNECTING` + 30 s 恢复截止 | `ERROR` | 保留最近一次 `SOCKET_CLOSED/FRAME_TIMEOUT/DAEMON_EXITED` 原因，停止本会话资源，错误供 UI 查看 |
| 非终态 + 显式 stop | `STOPPING` | 立即清 latestFrame，取消读帧/重试/启动任务，在 worker 安全点关闭模型并精确停止 daemon |
| `STOPPING` + 资源释放完成 | `STOPPED` | 清本次绑定与前台通知，stop 返回 Stopped |
| `STOPPING` + worker 停止超时 | `ERROR` | `STOP_TIMEOUT`，拒绝创建第二个并发引擎；不得并发关闭正在 invoke 的 Interpreter |
| 任意live状态 + 屏幕关闭/设备锁定 | `STOPPING` | 清帧并按同一stop流程释放；解锁保持STOPPED，用户显式start |
| `RUNNING` + 激活已 staged 模型 | `STARTING` | 清 latestFrame、暂停新推理，在同一 worker 执行安全切换；成功或成功回滚后回到 RUNNING |
| `STOPPED` + 激活已 staged 模型 | 保持 `STOPPED` | 协调器串行执行离线核验/预热，成功提交指针并关闭实例；不启动 Service/root/帧流 |

缺 root、模型或权限使用 `ERROR + typed code`，不另加 WAITING 状态。错误码及恢复策略直接采用 CONTRACTS C06，不另给同一语义改名：模型 hash 错用 `MODEL_CORRUPT`，schema/tensor 不支持用 `MODEL_INCOMPATIBLE`；root 缺失/拒绝用 `ROOT_REQUIRED/ROOT_DENIED`；协议版本错用 `PROTOCOL_MISMATCH`，帧尺寸/时间/头字段异常用 `INVALID_FRAME`，模型行异常用 `INVALID_OUTPUT`。Android 只补本地接口错误 `BIND_FAILED`、`START_TIMEOUT`、`BUSY`、`DAEMON_BUSY`、`SOCKET_PERMISSION_DENIED`，并保留原因摘要。显示控制访问失败用主合同的 `DISPLAY_STATE_UNAVAILABLE`；Native 设备能力错误使用 C06/NATIVE 定义的 code 和 cause；读取C08有界stdout JSONL控制记录，持续消费stderr，不解析自由日志猜原因。用户消息保留恢复动作，日志保留阶段和异常原因。

生命周期必须满足以下规则：

- `RUNNING` 表示工作资源和帧链路就绪，不表示存在敌人，也不保证最新结果当前有效。无目标的真实推理结果照常发布 `detections=[]`；未收到帧、错误和过期不能伪装成一次成功推理。
- `ageNs = nowElapsedRealtimeNanos - captureStartNs`。这是采集请求发起到结果/消费时刻的运营帧龄，不是显示器曝光时间；`captureEndNs` 另用于计算 Native 采集阶段耗时。
- 推理开始前及发布前分别检查 `now/resultNs - captureStartNs`；超出所选 profile 上限的帧丢弃并累计 stale-drop，不浪费推理或推送 frame event。有效结果到期的清理定时器运行在独立控制协程，不能被阻塞读帧或 GPU invoke 卡住；新帧/会话替换时取消旧定时任务。
- 断流、屏幕状态变化、模型切换、ERROR、stop 均立即清 latestFrame。消费者在执行自己的逻辑前仍检查 TTL，避免刚读到 snapshot 后跨过有效期。
- start 的整体 ready 预算直接采用 C06：REALTIME 10 s、DIAGNOSTIC 15 s。绑定/root/模型/首帧共用一个可取消截止，子操作只能消费剩余预算，不各自叠加超时；首次完整帧另受 profile 的 1 s/5 s 约束。未完成用户 root 授权也不得无限等待，到期提示显式重试。deadline 使用 elapsedRealtimeNanos。
- 重连退避为 200/400/800/1600/2000 ms，之后每 2 s，带 ±10% 抖动，总恢复截止 ≤30 s；stop 立即取消退避。自有 daemon 自动重启最多每 30 s 一次，仅重启匹配当前 owner 的实例。`PROTOCOL_MISMATCH` 直接 ERROR；`INVALID_FRAME` 允许一次重连，再次出现 ERROR；权限/模型契约问题不进入盲重试。
- stop 优雅阶段 2 s，随后仅对身份核验匹配的 daemon 强制终止并再等待 1 s。Socket 用关闭解除阻塞，推理在本次 invoke 安全点释放。若 Interpreter 仍未返回，隔离其 worker，报告 STOP_TIMEOUT 和未退出资源、拒绝新引擎并提示重启 App 进程；不能从另一线程强制 close 正在 invoke 的 native 对象来伪装成功。进程清理的强制步骤不等于获得强杀 JVM 推理线程的许可。
- 所有 start/stop/activate 命令由同一串行协调器排序；STARTING 中 stop 使原 start 取消，STOPPING 中 start 返回 BUSY。重复 stop 合并等待同一个停止任务。终态错误不能被随后的绑定断开回调覆盖成“已正常停止”。
- Service 使用 `START_NOT_STICKY`。进程退出后的首次进入为 STOPPED，App 启动不恢复 root 采集、不自动申请 su，不依赖丢失的 Intent 自动重启。Activity 重建只重新订阅，不创建第二个引擎。

## 5. 线程、背压与释放顺序

| 执行面 | 工作 | 禁止事项 |
|---|---|---|
| Main/控制协调器 | Service/Binder、命令 reducer、snapshot、TTL、通知 | `waitFor()`、阻塞 Socket、模型加载/invoke |
| 可取消 IO | root 子进程等待/输出收集、Socket connect/read、模型文件校验 | 直接变更引擎状态、直接调用 Interpreter |
| 单一专用推理线程 | delegate/Interpreter 创建、校验、warmup、preprocess/invoke/postprocess、关闭 | 使用 `Dispatchers.Default` 让 GPU 调用跨线程迁移 |

FrameReader 必须完整读取当前 payload 才能寻找下一帧；不能丢半帧后把像素当头部。完整帧进入容量为 1 的最新候选槽，替换时释放旧帧 buffer；推理中一帧、等待中一帧、IO 接收中一帧是允许的所有者上界，不建立无界 Bitmap 队列。每个 buffer/Bitmap 只能由一个所有者释放；DROP_OLDEST 必须包含回收动作。

重用输入 direct buffer、像素数组及 letterbox canvas，在尺寸变化时于安全点重建。不得向公开结果对象附带可复用像素 buffer；DetectionFrame 的列表和框值不可变。以 3200×1440 计算单个紧密 RGBA payload 为 18,432,000 字节；这是传输与内存压力的估算，不是实际耗时。

stop 顺序：撤销会话接收资格并清 snapshot → 取消候选与重连 → 关闭 Socket 解除 read → 请求自有 daemon 停止 → 等正在进行的 invoke 完成 → 在推理线程关闭 Interpreter/delegate/预处理资源 → 结束绑定/通知。失败路径使用同一幂等 finally；不能把 onDestroy 和工作协程各自的 cleanup 并发执行。

首次实现可复用 byte buffer + Bitmap 作为正确性基线。后续是否需要硬件 buffer、共享内存或其他传输方式，必须由完整链路测量驱动，不能提前把普通 Socket/writev 记为零拷贝。

## 6. 模型加载、预处理与输出解析

每个模型由 `model.tflite` 与 `model.tflite.json` 组成不可分离的一对。sidecar 的完整 JSON 以 CONTRACTS 为准，Android 必须检查 schemaVersion、不可变 modelId、SHA-256、labels、SDK 最低版本及真实 tensor。首版只接受：

- `labels=["enemy"]`；恰好一个输入、一个输出、batch=1，float32 输入 `[1,H,W,3]`，方形 RGB、0–1。H/W 只接受 640/960/1280/1600 中已验收的模型，960×960 为首版默认；实际尺寸由 metadata 与 tensor 一致性检查后驱动。
- LetterBox：padValue=114、bilinear、sizeRounding=floor、center。不能保留 Preprocessor 默认 640，也不能仅看某个输出维度猜类别数。
- float32 输出 `[1,300,6]`，`xyxy_score_class`、相对模型输入宽高归一化、`nms=true`；导出 conf=0.001 / iou=0.5 / maxDet=300，端上默认显示阈值 0.45。
- fp16 表示权重量化策略，不代表 fp16 IO；暂不支持其他 IO dtype、raw YOLO、objectness 布局或其他坐标单位。unsupported 明确报错，不做“尽量猜着读”。

模型加载顺序为 active 指针指定的已验证缓存 → APK baseline。只有候选不可用且 baseline 也完成同样校验时才能回退。显式 `ModelSelection.ById` 失败直接报错，不悄悄换另一个模型。APK 没有资产仍应可以安装启动，UI 显示待导入及准确位置；不生成伪模型填补缺口。

Interpreter 输出用与实际 rank/shape 一致的数组或按 `numBytes()` 分配的 direct buffer；输出不能用 `[1,N*6]` Java 数组代替 `[1,N,6]`。先核验 tensor，再分配；已内置 NMS 不再做一次端上 NMS。每行读取 `x1,y1,x2,y2,score,classId`，第 6 列是类别编号，不是分类概率。

坐标算法必须与导出对照一致：

```text
s = min(inputWidth / sourceWidth, inputHeight / sourceHeight)
newW = max(1, floor(sourceWidth * s)); newH = max(1, floor(sourceHeight * s))
padX = floor((inputWidth - newW) / 2)
padY = floor((inputHeight - newH) / 2)
scaleX = newW / sourceWidth; scaleY = newH / sourceHeight

xOriginal = (xNormalized * inputWidth - padX) / scaleX
yOriginal = (yNormalized * inputHeight - padY) / scaleY
```

归一化输出先变为模型输入像素，排除中心位于有效图像之外（纯 padding）的框，再去 padding 并除实际 scaleX/scaleY；不能只除一个理论 scale，也不能把 normalized 坐标直接减 padding。按 C02 顺序处理有限值、score≤0 的 padding 记录、score∈[0,1]、classId 近似整数 0（整数容差 1e-4）、操作阈值及正面积。非 padding 行的非有限值/非法类别使本帧以 `INVALID_OUTPUT` 失败，清latestFrame并丢弃该帧而不伪报无目标；连续 3 次坏输出转 ERROR。边框再与 `[0,sourceWidth] × [0,sourceHeight]` 相交，裁剪后非正面积丢弃。输出字段称 confidence，读取来源是模型行的 score 列。

框保留 float xyxy 的边界坐标，x2/y2 可以等于原图宽高。中心由裁剪后的框计算，`floor((x1+x2)/2)`、`floor((y1+y2)/2)`，最终限制为 `0..width-1` / `0..height-1` 的可寻址像素。这一定义与旧 `0..width` 包含越界像素的实现区分。

主合同黄金样例：3200×1440 → 960×432，padY=264。输出框 `[0.45,0.45,0.55,0.55]` 映回原图为 `[1440,560,1760,880]`，中心 `(1600,720)`。附加不居中样例 `[0.3,0.425,0.5,0.575]` 映回 `[960,480,1600,960]`、中心 `(1280,720)`。还必须覆盖奇数尺寸、纵屏、边界和 padding 框。

跨引擎对照使用相同的 RGB float32 tensor；Ultralytics 默认 LetterBox 的 round 行为不能充当这里 floor 合同的像素真值。Python 与 Android bilinear 的采样差异先记录逐像素误差并核验允许阈值，再比较框/分数；具体容差与数据集以 VALIDATION 定义，不能把随机 smoke invoke 当检测精度验收。

## 7. Socket v2 与屏幕状态

Android 与 Native 同步升级，拒绝旧 v1，不通过“看头几个值像尺寸”猜协议。64 字节固定 LE 头由 CONTRACTS 定义：SVF2 magic、版本/头长、payload 长度、尺寸、stride、RGBA8888 格式、rotation、frameId、captureStartNs/captureEndNs、streamId。

接收顺序必须是完整头 → 结构与长度校验 → 安全分配/复用 → 完整 payload → 时间/stream/屏幕资格复核 → 发布候选。检查项目：

1. magic、version=2、headerBytes=64；宽高均 1..4096。
2. pixelFormat=1，rowStrideBytes=width×4；使用 Long/无符号安全运算计算 payload=width×height×4，要求精确一致且 ≤64 MiB。
3. rotationDegrees 只接受 0/90/180/270，payload 已是当前显示方向；Android 不依据此字段再旋转像素。
4. 一个连接固定一个非零 streamId；frameId 位于正 Long 范围，在该流单调递增，允许丢帧间隙，拒绝重复/倒退。Native 进程内序号保持单调，Android 在新连接建立独立序列检查；清掉旧候选。
5. Native 时间为 CLOCK_BOOTTIME，对应 `SystemClock.elapsedRealtimeNanos()`；时间位于正 Long 范围，captureStartNs ≤ captureEndNs，不能超出本机当前 BOOTTIME，允许 C01 的 1 ms 采样容差。时间基准错误按 INVALID_FRAME 处理，不能继续计算“负帧龄”。
6. EOF、半头、半 payload、超时、非法尺寸、未知格式都清当前结果；从本帧首字节起的总截止不能因每段 read 成功而重置。版本不支持立即 ERROR，坏帧只允许一次重连，普通断流进入 C06 有界重连；取消关闭 Socket 和所有半帧资源。

`DisplayStateWriter` 在 Android 启动时及 `DisplayManager.DisplayListener` 回调维护 C04 的主显示控制文件 `filesDir/runtime/display-state.json`。使用同目录临时文件+原子 rename，写入 schemaVersion=1、displayId=0、校准后的全显示 width/height、rotationDegrees、正整数 generation、updatedAtNs BOOTTIME；字段定义不在本文件另立版本。rotation 来自 Android ROTATION 枚举×90，尺寸不是 Activity 内容区域。

显示尺寸/方向变化时 generation 递增，协调器清 latestFrame/候选、废弃在途结果并断开旧连接；原子写入新控制文件后发起重连。Native 用 `--display-state-file <absolute-path>` 仅读，采集前后 generation 不一致则弃帧并关闭数据连接。每连接显示尺寸/方向固定，新 stream 首帧须与当前 C04 状态匹配，发布前再次检查 session/stream/generation。

root 读取 App 私有文件的权限由设备探针验证；失败报 `DISPLAY_STATE_UNAVAILABLE`，不修改全局 SELinux，也不转存到所有应用可改的公共控制文件。锁定方向的手工 DIAGNOSTIC 才允许 Native 显式固定 `--rotation-degrees`；REALTIME 必须使用控制文件。Native raw 不能提供可靠旋转，尺寸也不能区分 0°/180° 或 90°/270°，禁止凭宽高猜方向。该集成任务属于首帧对齐验收，不后置到性能优化。

## 8. GPU、CPU 回退与错误隔离

默认 `AUTO`：在专用线程查询 compatibility、创建 GPU delegate、创建 Interpreter、核验 tensor、分配输出并执行 warmup；整个阶段任一步失败都先在同线程释放已创建资源，再以同一个模型全新构建 CPU Interpreter。不能只捕获 `GpuDelegate()` 构造异常而遗漏 Interpreter 图准备或首次 invoke。

运行期 GPU invoke 失败时，AUTO 在同线程进行一次 CPU 重建并重新核验/预热；恢复期间清 latestFrame。只重试当前仍在 TTL 内且仍属于当前 session/stream/model/显示 generation 的候选；否则等待下一帧。CPU 重建也失败进入 ERROR；成功建好 CPU 后的普通 `INFERENCE_FAILED/INVALID_OUTPUT` 按 C06 清latestFrame并丢弃该帧、连续 3 次失败才 ERROR，不得无限循环创建解释器。

`CPU` 禁用 GPU 尝试，用于离线数值基准和回归；`GPU_REQUIRED` 在 GPU 不可用时明确失败，用于专项测量，避免 CPU 回退数据被误记为 GPU 性能。发布帧与 snapshot 均记录实际 backend。delegate 的 `close()` 不由 Interpreter 自动代办的假设必须按固定依赖版本核实；资源对象显式持有并按顺序释放。

GPU 创建和运行需同线程，属于官方约束，不能因为手机 GPU 型号受支持而忽略；参见 [Google GPU delegate 指南](https://developers.google.com/edge/litert/android/gpu)。依赖升级后重复 CPU/GPU 同图对照和 start/stop 测试。

## 9. 前台服务、root 与 daemon 所有权

首次实时启动必须从可见 Activity 的用户动作发起；startForegroundService 后，Service 先创建通知并及时 startForeground，再执行模型/root 的耗时检查。应用启动、Activity 重建、模型导入都不隐式开启 root 采集。

当前 `mediaProjection` 与实际 root 截图架构不符。目标方案为 API 34 使用 `specialUse`，声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` 及 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，用途说明为用户主动开启的本机 root 帧流视觉分析。低 API 使用对应可用的前台 API。不得伪造 MediaProjection 授权，也不使用仅适用于特定系统身份的 `systemExempted`。specialUse 的用途及权限依据见 [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)；私人安装运行与商店分发审查分别验收。

API 33+ 的通知权限由示例 Activity 显式处理，拒绝后的实际前台展示按平台规则验证；不能把拒绝通知权限简单等同于获得或失去 root。通知提供“打开状态”和“停止”动作，停止动作进入同一协调器。网络权限只在后续启用明确更新功能时加入，不靠无效的 `EXECUTE_COMMANDS` 声明获取 root。

启动前的 `inspect(includeRoot=false)` 不执行 su。显式完整探测/实时 start 才调用 root 检查；分别记录 App API/ROM、su 可用且授权、daemon 已部署且 executable、版本/v2 支持、Socket 文件访问和真实首帧。缺少任一项给出对应下一步，不将 su 成功当全部通过。

采用 [NATIVE 第 6.3 节](NATIVE.md#63-规划中的-cli) 的完整 CLI 与 `--version` JSON 字段，不维护第二套能力格式。SDK 启动时传 `--socket`、`--fps`、`--profile`、`--backend`、`--pid-file`、`--owner-token`、`--display-state-file`、`--client-uid`、`--capture-profile`。client UID 来自当前 App 的实际 UID，Native 用 SO_PEERCRED 验证；capture profile 来自已校准的设备/ROM/raw 记录，缺少记录时先执行设备探针与校准，不猜 raw 布局启动正式检测。

SDK 生成每会话唯一的 32 hex token，只使用校验后的参数构造 su 命令；App 私有文件绝对路径进行可靠 shell 参数转义，禁止直接拼接任意外部文本。异步收集并限制 stdout/stderr，避免管道堵塞。`--probe`/`--status`/`--version` 的退出状态与结构化字段进入诊断报告；数据 Socket 不能混入这些文本。

正式启动使用 profile 对应 15/5 fps，首个后端为可验证的 `cli-raw`。Native READY 仅说明预检与 listen 完成，SDK 收到合法 v2 首帧后才进入 RUNNING。部署作为开发者显式步骤，SDK 不在启动时下载或替换未知 daemon。

停止使用 `--stop --owner-token` helper，Native 比对 pid、boot_id、进程 starttime、可执行文件身份及 token，打开 pidfd 后复核，再经稳定的 pidfd TERM→KILL/poll 退出；具体字段与时限见 [NATIVE](NATIVE.md)。设备探针不能验证 pidfd/进程身份方案时使用 `LIFECYCLE_UNSUPPORTED`，仅保留手动前台诊断，自动 live 不验收通过、不退回裸 PID/global kill。SDK 只停止本次会话拥有的进程；已有其他 owner 的 daemon 返回 `DAEMON_BUSY`，不抢占。App 异常退出后的孤儿清理也须核对身份，不删除其他会话的 socket/pid 文件。

首台 K70 的明确后继：先探测和记录 ROM/API/root 结果 → CLI 后端 DIAGNOSTIC 首帧与方向/颜色对齐 → CPU 离线黄金图对照 → GPU 功能对照 → REALTIME 完整性能测量。任何后端不支持时保留报告并进入其替代实现任务；不得把所有未知项合并为无限期“待定”。

## 10. 演示 APK、离线入口与 AAR 拆分

第一阶段保留 `app` application 模块，新增最小 Activity：

- 启动即显示模型、运行状态、profile 及必要的未就绪说明。没有模型时显示“待导入 model.tflite 与 model.tflite.json”，不崩溃、不请求 root。
- “检查环境”：默认本地模型/API检查；用户显式选择 root 探测才执行 su。
- “离线检测截图”：系统文件选择器选图，不申请广泛存储权限；按合同检查尺寸，若存在 EXIF 方向则只规范化一次，输出坐标相对于界面展示的该规范化原图，报告其宽高。显示框、中心、模型版本、实际后端和耗时。离线结果只属于该图，不注入实时 frames/snapshot，不伪造 captureStartNs。
- “开始实时检测”：显示选定 profile 后显式 start；“停止”等待真实清理结果。实时详情只展示状态和最近有效结果，过期即清空，不在本轮设计完整游戏悬浮层。
- 运行实时会话时离线 analyzeImage 返回 BUSY，避免第二个 Interpreter 与游戏争抢资源；离线作业取消由其 worker 安全退出。Activity 生命周期用 lifecycle-aware collect，重建不重启引擎。

采集配置通过C08校准工具生成；App增加“导入采集配置”入口，验证profile/evidence的hash、设备fingerprint与backendBuildId后落入私有runtime/capture-profiles，candidate不进入正式live。

模型导入先支持开发者把成对资产放入 `app/src/main/assets/` 后构建；下一阶段加入文件选择器导入成对 bundle，经 stage 验证后用户显式激活。不要要求为了 APK 可启动而先训练出正式准确模型，也不要把导入一个空文件当安装了模型。

AAR 拆分在上述流程及状态/线程测试通过后执行：把 `api/model/runtime/detection/socket/service` 迁到 `:vision-sdk` Android library，`:app` 变为只依赖公开 API 的样例。daemon 保持独立交付的 ABI/version 产物，模型由资产或 bundle 交付；AAR 的 consumer rules、Manifest 合并、资源命名、版本与使用文档一并验收。新增一个只引用产物的最小宿主构建，证明无需拷贝内部源码即可集成。

## 11. 模型缓存、原子激活与回滚

当前 ModelUpdater 不进入默认 start 路径。首版没有占位地址网络请求，inspect/start/离线检测均可完全离线。后续更新入口显式启用，检查更新与激活分开，不能下载完成便替换运行中的模型。

目标目录结构：

```text
APK assets/model.tflite + model.tflite.json        只读 baseline
filesDir/models/staging/<requestId>/             未信任的导入/下载临时目录
filesDir/models/<modelId>/                       校验完成、不可变 bundle
filesDir/models/active.json                      原子 active 指针
filesDir/models/previous.json                    已成功运行的回滚版本
```

清单采用 C07 的 manifestVersion=1，包含 modelId/modelVersion/sdkMinVersion、两文件 URL、两个 SHA-256 及两个准确字节数；模型 ≤256 MiB、metadata ≤64 KiB，长度必须与声明一致，metadata 内模型 hash 还须匹配清单 modelSha256。只从用户明确配置的 HTTPS 来源获取，同信任来源最多 3 次重定向且不得降级 HTTP；connect 15 s、read 60 s、整个更新 5 分钟。一次仅一个更新，stop 可取消未进入激活阶段的下载。用户主动选择本地文件对是离线导入来源，同样执行大小/C02 检查及模型与 sidecar hash 匹配，计算并保存两个文件的 hash；不把本地导入伪装成已校验某份不存在的服务端清单。

暂存严格遵循 C07：成对下载 → 长度与两个 SHA-256 → metadata schema/SDK 兼容性 → 写 staged 标记。Staged 只代表文件完整，不代表 Interpreter 可加载或模型可用。`modelId` 只接受 C02 的 1–80 位字母数字/点/下划线/连字符，不允许路径字符；同 modelId/version 不可变。服务器版本号不能替代文件完整性和模型契约检查，MD5 不再作为目标完整性方案。

真实 runtime 验证属于激活事务。专用 worker 到帧边界后先关闭旧 Interpreter，加载候选并核对真实 tensor，再执行 CPU 固定输入 3 次 smoke/warmup；若选择 GPU，先关闭 CPU 实例再创建 GPU，按 AUTO/CPU/GPU_REQUIRED 规则处理。全程不同时保留两个可执行 Interpreter，旧 bundle 文件继续保留供回滚。TTL 清理不受预热阻塞，候选帧槽保持容量 1，更新过程不混入稳定性能验收窗口。

live 激活在帧边界进入 STARTING、清 latestFrame 并暂停旧模型的推理资格，完成上述验证后还必须产生一帧符合当前 profile TTL 的真实新结果；健康检查成功后才原子提交 active 并回到 RUNNING，发布与新 modelId 一致的 frame/snapshot。失败重新加载此前已验证 bundle，保持原 active，恢复后返回 RolledBack；旧版本也不可用才 ERROR。既有 CPU 预热不能代替 live 新鲜帧检查。原状态为 STOPPED 时只执行离线核验/预热，关闭实例、提交指针并保持 STOPPED，不触发 root/daemon；失败保持原 active，以 ActivationOutcome 返回原因。

进程在各步骤退出后：staging 不被选用；active 指针只能指向已提交 bundle；active 损坏或指向缺失文件则尝试 previous，最后尝试 APK baseline，每一次都核验并显示实际 modelId。文件落盘/rename 原子性和断电边界需通过故障注入证明，不能把一次 `renameTo()` 的返回值当完整事务。

清理旧版本在没有活动/回滚引用后执行，保留至少 active、previous 和 baseline；下载失败、取消、校验错误都不改变 active。更新 HTTP 实现使用明确超时、响应码、长度限制、可取消 IO 和结构化 JSON；默认源为空，用户配置的端点不硬编码 example.com。

## 12. 构建、打包与 CI

当前仓库没有 Gradle Wrapper、Android 自动化测试或 CI，且已知 Kotlin 编译错误待修。实现阶段先冻结可验证的组合：现有 AGP 8.2.0、Kotlin 1.9.20、compile/target SDK 34、min SDK 28、JDK 17；Wrapper 初始采用 Gradle 8.2，SDK Build Tools 34.0.0。这与 [AGP 8.2 官方兼容表](https://developer.android.com/build/releases/agp-8-2-0-release-notes) 一致，但仍需实际构建验证。保存发行包 checksum 与 wrapper 来源校验。若主计划锁定该兼容范围内补丁版，统一锁文件后重复构建；其他依赖调整须提交兼容依据与证据，不无关升级整套库。

保留 TFLite 2.14.0 / GPU 2.14.0 作为首轮待验证候选，明确实际使用的 Android artifact 与 ABI。删除无实际调用的 support/lifecycle 依赖需跟代码审查一起完成；模型 `.tflite` 配置不压缩以支持 assets mmap，sidecar 与模型同版本打包。离线纯逻辑尽量拆成不依赖 android.graphics 的类，用 JVM 测试；Bitmap/Service/Manifest 在 Android instrumentation 或真机测试。

未来仓库拥有 Wrapper 后的门禁命令目标为 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；当前没有该文件，不把此命令写成现已可执行。release 构建、R8 与 ABI 打包测试随后加入；签名凭据由 CI secret 提供，不入库。

CI 第一层执行纯逻辑测试、Android 编译/lint 和 APK 资产契约检查；第二层模拟器执行无 root 的启动、缺资产反馈、离线图像、Binder/生命周期测试；第三层为人工触发的 K70 真机作业，运行 root、GPU、连续采集和性能验收。云模拟器不能证明 K70 GPU/root 可用。

CI 不自动部署到用户手机、不申请 su、不点击游戏。版本产物包含 APK/AAR、构建环境清单、模型/daemon SHA、测试报告及性能数据位置。缺数据/模型的 job 明确 SKIPPED 并列依赖；不能把跳过记为通过。

## 13. 文件级任务拆分

所有“新增”都是未来路径，完成时同步 CODEMAP；可按同一交付切片调整内部类名，但不得改变 CONTRACTS。

| 任务 | 文件/范围 | 完成内容 | 前置依赖 |
|---|---|---|---|
| A01 构建与入口 | `android-app/gradle/wrapper/*`、`gradlew*`、现有 Gradle 文件、Manifest、新 `MainActivity.kt` | 修编译基线、正确 Binder 返回、可安装且缺模型不崩溃、明确前台声明 | 文档合同冻结 |
| A02 纯数据契约 | 现有 `model/DetectResult.kt`，新 `Detection.kt`、`DetectionFrame.kt`、`VisionSnapshot.kt`、`VisionError.kt` | 框/中心/时间/会话/错误类型；兼容投影 | A01 |
| A03 模型资源 | 新 `detection/ModelContract.kt`、`update/ModelRepository.kt`，改 `YOLODetector.kt` | sidecar+SHA+tensor核验、assets/cache解析、缺模型错误 | 训练导出探针合同、A02 |
| A04 坐标正确性 | `Preprocessor.kt`、`PostProcessor.kt`，新 `LetterboxTransform.kt` | floor LetterBox、normalized NMS rows、真实 shape buffer、黄金样例 | A03 + 黄金 tensor/输出 fixture |
| A05 离线可用 | `MainActivity.kt`，新离线 use case | 选截图→CPU结果与诊断；不调用 root/daemon | A04 |
| A06 单一运行时 | `ScreenVisionSDK.kt`、`DetectionService.kt`，新 `runtime/RuntimeCoordinator.kt`、`InferenceWorker.kt` | 6状态、Binder/Flow、revision/session、TTL、取消/幂等清理 | A02/A04 |
| A07 帧协议 | `UnixSocketClient.kt`，新 `socket/FrameHeader.kt`、`FrameReader.kt` | v2固定LE、长度/时钟/序列检查、profile超时、有限最新槽 | Native v2 fixtures、A06 |
| A08 root 生命周期 | 新 `runtime/DaemonController.kt`、`DisplayStateWriter.kt`、Manifest/Service | 版本能力、owner token、C04原子控制文件、首帧就绪、精准停止、显示变化失效 | Native CLI/显示控制合同、A07 |
| A09 GPU与真机 | `YOLODetector.kt`、`InferenceWorker.kt`、诊断面板 | 全阶段AUTO回退、实际后端记录、持续运行指标 | A05/A08、K70就绪 |
| A10 模型更新 | `ModelUpdater.kt`、`ModelRepository.kt`、导入入口 | staged bundle、不可变版本、原子active、回滚；默认无网络 | A03/A06稳定 |
| A11 SDK交付 | 新 `:vision-sdk`、样例 app、consumer rules、发布任务 | AAR宿主集成、ABI/资源/Manifest合并和release验证 | A01–A09及T20对应验收完成；可与A10/T25并行，最终T26汇合验收 |

建议切片顺序是 A01→A02/A03→A04/A05，同时设计 A06；Native 完成 v2 后接 A07/A08，再做 A09。A10/A11 后置且可并行；拆分后A10更新实现落入library，最终按T26汇合验收。不用热更新或模块拆分阻挡最小离线闭环。每个切片提交真实测试证据后才标完成。

## 14. 测试用例与验收证据

| 层级 | 必测场景 | 通过证据 |
|---|---|---|
| JVM：模型合同 | 缺字段、错误 schema/labels/hash、SDK不兼容、错误dtype/rank、N不是300、错误NMS/坐标单位 | 明确拒绝且不进入invoke；有效fixture通过 |
| JVM：NMS记录/坐标 | 第6列class=0、score阈值、空/全零输出、NaN/Inf、padding外框、越界裁剪、奇数resize、上述K70样例 | 原图bbox/整数中心符合合同，没有二次NMS |
| JVM：v2头与流读取 | 固定64字节黄金字节串、v1拒绝、LE、payload溢出、尺寸0/4097、stride不匹配、分段头/payload、EOF、取消 | 分配前拒绝错误，分段输入恢复同一帧，半帧不泄漏 |
| JVM：状态/时间 | 真空结果、双profile TTL、旧session/stream/revision/generation回调、帧序倒退、断流、30s退避截止、start→stop竞态、重复命令 | fake clock驱动可重复结果；ERROR/过期不虚构frame事件；兼容observe及时为空 |
| JVM：资源/更新 | GPU构造/图准备/首次invoke/运行失败注入、CPU再次失败、stop超时、stage/激活各步退出 | GPU调用线程一致、资源仅关闭一次、active/previous始终可解释 |
| Android无root | debug安装启动、缺模型、合法Binder、Activity重建、通知动作、图片选择/方向、assets/cache读取 | instrumentation/模拟器记录；无自动su/网络/输入事件 |
| 真模型离线 | 相同tensor/截图在Python、TFLite CPU、Android CPU/GPU对照 | 固定modelId/hash、数据集、误差与阈值报告；不以形状通过替代精度 |
| K70 DIAGNOSTIC | root/SELinux/Socket/CLI能力、首帧颜色/尺寸/旋转、daemon被杀、App退出、横竖屏/分辨率变化 | 明确profile和ROM/API；无陈旧目标、无跨owner停止 |
| K70 REALTIME | 连续采集、最新槽丢帧、游戏并行GPU负载、温升、后台/回前台、锁屏恢复 | 各阶段分位数、有效FPS、运营帧龄、stale/drop、RSS/温度；达到VALIDATION门槛才记性能通过 |
| 发布 | release/R8、资产noCompress、ABI、AAR最小宿主、升级后baseline/cache回退 | 可安装运行产物、校验值、宿主构建和验收报告 |

不测试只为复述实现的私有方法数量；重点测试边界输入、顺序竞态、状态清理和真实跨引擎结果。纯 host/JVM 通过、Android 编译通过、模拟器通过、K70 真机通过及精度/性能达标是不同证据层级，报告必须分别记录。

## 15. 本阶段完成的判定

Android 规划可以开始实现的条件是：CONTRACTS 的 wire/metadata/API 已冻结，A01–A11 每个任务有前置、文件范围和可执行验收；不要求现在已具备 root 或训练数据。Android 功能完成则必须依次具备构建、离线对照、生命周期、协议/真机、性能和交付证据，不能因为类文件存在或 K70 配置较高提前打勾。

目前所有这些实现与验收仍是计划。已知 # 注释、错误 onBind、object 内 companion、未接结果桥、旧输出布局、GPU跨线程、占位更新和不完整socket校验，在后续对应任务中修复；本文件不修改任何 `.kt/.xml/.gradle` 源码，也不声称修复已经完成。
