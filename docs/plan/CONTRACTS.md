# 跨端合同 v1.0（待实现）

本文是新开发的唯一跨端定义。现有源码仍使用不完整的 v1 帧和旧模型解析；本轮没有实施迁移。修改本文的字段、单位或语义，必须同时更新生产者、消费者、fixture 与版本号。

## C01 帧数据协议 v2

传输：文件系统 AF_UNIX / SOCK_STREAM，数据地址 `/data/local/tmp/screen-vision.sock`，一次只允许一个授权 App 客户端。没有 PING/PONG，也不在像素流中混入日志或控制文本。客户端连接后直接接收完整帧；第一帧同时验证协议就绪，不能用 socket 文件存在证明截图可用。

### 固定 64 字节头

所有整数 **little-endian**。手工按偏移编码/解码，禁止直接发送具有隐式 padding 的 C struct。Kotlin 用显式 `LITTLE_ENDIAN`，u32 先转换为 Long 校验，u64 位模式保留但只有 frameId/时间被要求位于正 Long 范围。

| 字节偏移 | 字段 | 类型/固定值 | 语义 |
|---:|---|---|---|
| 0 | magic | 4 bytes：`53 56 46 32`（ASCII SVF2） | 不符立即关闭，绝不猜成旧协议 |
| 4 | version | u16 = 2 | 不支持的版本明确报错 |
| 6 | headerBytes | u16 = 64 | v2 只接受 64 |
| 8 | payloadBytes | u32 | 后续像素负载长度 |
| 12 | width | u32 | 已按当前主显示方向归一化的帧宽 |
| 16 | height | u32 | 对应帧高 |
| 20 | rowStrideBytes | u32 | v2 固定等于 width×4 |
| 24 | pixelFormat | u32 = 1 | RGBA8888、sRGB/SDR，逐像素 R/G/B/A 四个字节 |
| 28 | rotationDegrees | u32：0/90/180/270 | 从显示原生方向到当前显示的旋转元数据 |
| 32 | frameId | u64，正数 | daemon 进程内成功采集序号，严格递增、可跳号 |
| 40 | captureStartNs | u64，正数 | 开始请求采集的 CLOCK_BOOTTIME 纳秒 |
| 48 | captureEndNs | u64，≥start | 完成读取与紧密 RGBA 归一化的 CLOCK_BOOTTIME 纳秒 |
| 56 | streamId | u64，非零随机值 | 每次 accept 新连接生成；只比较等值，不作有符号大小比较 |

`rotationDegrees` 是描述，不要求 App 再转一次图。Native 输出必须已经与当前显示方向一致；App 仅做 LetterBox 与坐标回映。单连接内 displayId=0、宽高和方向固定，改变时按 C04 重建连接；v2 不支持多显示器。

### 校验顺序与边界

1. 读满头部；在分配像素内存前校验 magic/version/headerBytes/pixelFormat/rotation。
2. 宽高各在 `[1,4096]`；用 64 位算 `width×height×4`，要求与 payloadBytes 完全相等，且 ≤67,108,864 字节（64MiB）；stride 恰好 width×4。K70 的3200×1440负载为18,432,000字节。
3. 要求 start≤end，时间未超出本机当前 BOOTTIME（允许1ms采样容差），且该次连接中的 streamId 不变、frameId 比已接收帧大。时间跳变/进程重启通过断连重连处理，不复用旧 epoch。
4. 读满且仅读本帧 payloadBytes；完成后才进入 latest-slot。半帧 EOF、超时或非法字段关闭连接，清空待处理帧，发出协议/传输错误；不在坏字节流内扫描下一段 magic 尝试恢复。
5. Alpha 输入若来源为 RGBX 等格式，由 Native 规范化为255；发送端不能带行 padding。明确的HDR/宽色域必须先转换为sRGB/SDR，无法转换时报CAPTURE_FORMAT_UNSUPPORTED；未知dataspace仅在该设备指纹的raw/PNG校准通过并记录后允许。Android 必须用色块fixture验证 RGBA→Bitmap 的字节含义，不依据 ARGB 类名猜布局。

### 时序、资源与背压

Native：采集缓冲被 capture worker 独占，转换后交换到容量1的完整帧槽；sender 取走后持有到整个头+负载发送完成。未开始发送的槽可被新帧替换；发送到一半的帧只能继续完整发送或关闭连接，不能丢弃剩余像素继续下一帧。

App：专用 I/O 读完整帧后放容量1的 latest-slot；推理 worker 独占取走的帧。被替换的完整帧立即归还池；同一缓冲区不能在推理时被写入。对象所有权随阶段移交，禁止无界队列。初始预算 Native 至多3份归一化全屏帧，加CLI至多1份有界raw输入工作区（≤64MiB+头部）；App至多3份全屏帧，输出/960输入buffer循环复用；实际内存按 VALIDATION 验收。

断连或显示变化使旧连接世代失效；所有在途结果发布前再次检查 sessionId/streamId/显示generation。新连接不能先发送旧槽中的帧。

| 行为 | 默认期限/动作 |
|---|---|
| poll/accept 等待 | 每≤200ms检查停止；不永久卡在阻塞调用 |
| REALTIME 单帧发送/读取 | 整帧总截止1s，从本帧首字节/发送尝试计；不按每小段重置 |
| DIAGNOSTIC 单帧发送/读取 | 整帧总截止5s |
| 空闲等头 | 同 profile 的1s/5s；超时视作断流，退出本连接 |
| 短写、EINTR | 在总截止内续写；屏蔽/规避 SIGPIPE，将 EPIPE 当普通断连 |
| 断连 | 关闭双方资源、清槽、清latestFrame；有界重连，见ANDROID |

### 黄金帧样例

后续 T07 应创建共同 fixture；本轮不生成二进制文件。样例为宽2高1、stride8、format1、rotation0、frameId1、captureStartNs1000、captureEndNs2000、streamId1。帧头应为64字节；payloadBytes=8；负载按顺序为红像素 `(255,0,0,255)`、绿像素 `(0,255,0,255)`。时间字段是解析测试值，运行时校验由测试时钟控制。C 编码与 Kotlin 解码必须逐字节一致。

迁移：旧8字节帧头没有可协商版本，**不提供隐式兼容**。daemon 和 App 同批升级，`--version` 显示 protocol=2；不匹配时 SDK 报 `PROTOCOL_MISMATCH` 并指导安装对应daemon。

## C02 模型包与输出

### 文件与元数据

一个版本是一对不可拆分文件：`model.tflite`、`model.tflite.json`。SHA-256 对实际 tflite 字节计算。示意元数据如下，仅为设计样例，零哈希不是可发布值：

```json
{
  "schemaVersion": 1,
  "modelId": "delta-enemy-000001-fp16",
  "modelVersion": 1,
  "sdkMinVersion": 1,
  "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "labels": ["enemy"],
  "input": {
    "shape": [1, 960, 960, 3],
    "dtype": "float32",
    "colorSpace": "RGB",
    "normalization": "zero_to_one",
    "letterbox": {
      "padValue": 114,
      "resize": "bilinear",
      "sizeRounding": "floor",
      "padAlignment": "center"
    }
  },
  "output": {
    "shape": [1, 300, 6],
    "dtype": "float32",
    "layout": "xyxy_score_class",
    "coordinates": "normalized",
    "nms": true
  },
  "export": {
    "ultralyticsVersion": "8.3.200",
    "conf": 0.001,
    "iou": 0.5,
    "maxDet": 300
  }
}
```

schemaVersion1固定 labels仅enemy、float32输入输出、一个输入一个输出、batch1、方形RGB输入、归一化NMS输出。H/W接受640/960/1280/1600中的已验收模型，默认960；输出严格 `[1,300,6]`。部署resize算子不能只写“bilinear”就假定数值一致，需用黄金预处理输入确定并固定实现差异容差。

元数据在模型转换后经桌面 tensor 检查、算子/加载和黄金输入验证生成候选模型包；Android使用候选包做runtime核验，成功后才晋升发布包，避免元数据与端上检查相互等待；样例中的Ultralytics版本为候选，发布时必须写实际白名单版本。fp16指权重，I/O仍为float32；int8权重产物仅在I/O与完整合同仍匹配且量化验收通过时可用，否则拒绝并另立新版合同。

modelId建议生成格式为`enemy-v<modelVersion>-<SHA256前16位>`；只允许字母数字、点、下划线、连字符，长度1–80，不可含路径字符；版本正整数且不可覆盖同版本内容。未知 schema/layout/dtype、重复/错误类别、哈希错、tensor不符、SDK过旧均报 `MODEL_INCOMPATIBLE` 或 `MODEL_CORRUPT`，不进入推理循环。

### 输出记录解释

每条六个float依次为：`x1, y1, x2, y2, score, classId`。xyxy归一化到**含LetterBox的模型输入画布**，不是有效图像区域，也不是原屏幕。classId=0代表enemy，不是置信度。没有objectness字段，也不再执行一次常规NMS。

解析顺序：检查float有限→跳过score≤0的padding记录→检查score∈[0,1]、classId近似整数且等于0（整数容差1e-4）→score达到运行阈值→验证x2>x1、y2>y1→按C03转换和裁剪。坐标超出画布可按连续框裁剪；中心位于纯padding的记录丢弃。非有限值/非法class的非padding行视作坏输出，记录诊断、清latestFrame并使本次帧失败，不能当无目标成功帧。

export.conf=0.001保留低分候选用于阈值评估；端上初始scoreThreshold=0.45，验证集校准后将正式阈值写入发布配置。运行阈值不得低于导出conf；更改NMS IoU或maxDet需要重新导出/验收，不能伪装成端上随意可调参数。

输出Java容器形状必须完全等同真实tensor，或使用容量和dtype匹配的直接ByteBuffer。模型加载时验证所有维度，不用 `shape[1]-5` 猜类数。

## C03 预处理与坐标合同

原帧 W×H，模型 S×S：

- `r = min(S/W, S/H)`；`newW=max(1,floor(W*r))`，`newH=max(1,floor(H*r))`。
- `left=floor((S-newW)/2)`，`top=floor((S-newH)/2)`；余下padding给右/下。
- `scaleX=newW/W`，`scaleY=newH/H`，记录实际比例，不能在反变换时只用理想r。
- RGBA去掉A，RGB双线性缩放到newW×newH；置于填充114的S×S画布；按NHWC顺序写float32，每通道除255。
- 归一化框乘S得到模型像素xyxy；先排除中心位于有效图像之外的框，再分别 `(x-left)/scaleX`、`(y-top)/scaleY`。
- bbox采用连续半开边界 `[0,W] × [0,H]`，裁剪后非正面积丢弃。中心取**裁剪后框中心**，`floor(center)`，夹到整数 `[0,W-1] × [0,H-1]`；tap/swipe坐标也使用该有效整数域。

K70黄金例：W3200/H1440/S960，newW960/newH432，left0/top264，sx=sy=0.3。模型输出框归一化 `(0.45,0.45,0.55,0.55)` 对应原框 `(1440,560,1760,880)`、整数中心 `(1600,720)`。边界测试必须覆盖不等比例取整、竖屏、奇数padding、小框、越界和纯padding。

训练时随机增强不用于验证/测试部署。PyTorch黄金比较也必须显式使用此部署预处理，不能把Ultralytics高层predict默认round/auto-padding与Android自定义floor结果直接比较后认定模型有误。算子级误差及最终检测容差见 VALIDATION。

## C04 显示控制与坐标参照

raw截图头不能区分0/180或90/270。App使用主显示DisplayManager/DisplayListener维护 `filesDir/runtime/display-state.json`，通过同目录临时文件+原子rename提交；Native以 `--display-state-file <absolute-path>` 仅读，不能凭宽高猜旋转。

控制文件字段固定为：`schemaVersion=1, displayId=0, width, height, rotationDegrees, generation, updatedAtNs`。width/height表示与截图校准一致的当前主显示完整像素空间；rotationDegrees来自Android旋转枚举映射0/90/180/270；generation为正Int、尺寸/方向改变时递增；updatedAtNs为BOOTTIME纳秒的十进制字符串（避免JSON工具损失64位精度）。width/height/schemaVersion/displayId/rotationDegrees为JSON整数。不以Activity内容区/去状态栏窗口尺寸冒充全屏帧尺寸。

App先更新文件再发起新连接；显示变化时清latestFrame、作废在途结果并断开旧连接。Native在采集前后读取generation，不一致则弃帧、关闭数据连接等待重连。每个新连接换streamId，新streamId的首帧需与当前显示控制一致。物理分辨率、wm override和游戏内部渲染比例由校准决定，中心坐标只承诺相对于**实际采集的全显示帧**；用户触控注入另做边角点对照。

root能否读取该App私有文件由设备探针验证；失败报 `DISPLAY_STATE_UNAVAILABLE`。不得静默改为对全系统开放的控制路径。手工daemon校准允许固定 `--rotation-degrees`，仅限锁定方向的DIAGNOSTIC，不用于正式实时运行。

## C05 SDK结果、状态与时间

公开模型字段由Android规格落实为Kotlin类型，以下语义不可改变：

| 数据类型 | 必要字段与语义 |
|---|---|
| Detection | `classId=0, className="enemy", x1,y1,x2,y2:Float, centerX,centerY:Int, confidence:Float`；原帧坐标，无稳定实例ID |
| DetectionFrame | `sessionId,revision,streamId,frameId,captureStartNs,captureEndNs,resultNs,sourceWidth,sourceHeight,rotationDegrees,modelId,backend,profile,detections`；一次真正推理完成的结果，允许空列表 |
| VisionSnapshot | `sessionId,revision,state,latestFrame?,error?,modelId?,backend?,profile,updatedAtNs`；StateFlow的唯一权威快照 |
| VisionError | 稳定code、可读message、recoverable:Boolean、cause摘要；不能通过空列表隐去错误 |
| EngineState | STOPPED、STARTING、RUNNING、RECONNECTING、STOPPING、ERROR |
| Backend / Profile | CPU/GPU；DIAGNOSTIC/REALTIME |

初始STOPPED的snapshot.sessionId可为null；`sessionId` 为每次接受的新start请求生成的非零正Long会话标识；进程级Coordinator串行分配snapshot.revision并单写StateFlow。DetectionFrame.revision等于发布它的snapshot.revision。身份判断至少包含sessionId+streamId+frameId，不能把frameId单独当全局唯一ID。

Android统一使用 `SystemClock.elapsedRealtimeNanos()`，Native用CLOCK_BOOTTIME。`frameAgeMs=(resultNs-captureStartNs)/1e6` 是采集请求到结果的运营帧龄，不是显示器实际曝光/渲染时间；`captureEnd-start`含采集读取及RGBA转换。禁止混入wall clock、`System.nanoTime()`或CLOCK_MONOTONIC后直接相减。

REALTIME最大帧龄250ms，DIAGNOSTIC为5000ms。推理开始前和发布前都检查；到期后用本会话定时任务清latestFrame，并取消过时定时任务。没有目标发布真实空DetectionFrame；断流、停止、错误、显示变更和TTL到期清latestFrame，不伪造新frameId或假装新空帧已经推理。

`RUNNING`要求模型已核验、worker已初始化、当前v2连接至少收到一个合法完整帧且检测循环在工作；不表示存在目标，也不表示最新结果一定未过期。离线图片入口使用独立一次性调用，不伪造live会话/streamId。

屏幕关闭或设备锁定触发与显式stop相同的STOPPING流程，清帧并释放；解锁保持STOPPED，必须再次显式start。不会只靠黑帧推理继续保持RUNNING。

兼容 `observe(): SharedFlow<List<DetectResult>>` 只作为snapshot的投影：`latestFrame?.detections.orEmpty()`，状态变化和过期也应发空列表。旧DetectResult字段 `elementId,x,y,confidence` 分别映射className、centerX、centerY、confidence。高级调用方使用完整snapshot/frame，不能依赖旧API区分空场景与断流。

frames事件流（若提供）replay=0、容量1、DROP_OLDEST；snapshot可回放最新值，是恢复订阅状态的来源。结果列表发布后不可变，不能复用可变数组污染已经发出的快照。

## C06 错误与恢复边界

| 错误code | 自动恢复规则 |
|---|---|
| ROOT_REQUIRED / ROOT_DENIED | ERROR，用户准备root后显式重试；不得循环弹授权 |
| MODEL_MISSING / MODEL_CORRUPT / MODEL_INCOMPATIBLE | ERROR，保留可用旧模型或等待正确包；不下载占位URL |
| DAEMON_MISSING / DAEMON_VERSION_MISMATCH | ERROR，指向显式安装/升级；若用户配置自动安装只执行一次可验证安装 |
| FGS_NOT_ALLOWED / DISPLAY_STATE_UNAVAILABLE | ERROR，展示平台/控制访问条件；前台再次start或修设备条件 |
| PROTOCOL_MISMATCH / INVALID_FRAME | 清帧、断连；协议版本不符直接ERROR，非法帧一次重连后再次出现转ERROR |
| SOCKET_CLOSED / FRAME_TIMEOUT / DAEMON_EXITED | RECONNECTING，有界退避；30s截止仍失败则ERROR |
| GPU_INIT_FAILED / GPU_INFERENCE_FAILED | AUTO在专用线程销毁GPU后重建CPU一次；GPU_REQUIRED直接失败，不回退；CPU模式不尝试GPU |
| INFERENCE_FAILED / INVALID_OUTPUT | 清latestFrame并丢弃该帧；连续3次失败转ERROR，避免无限空转 |
| CAPTURE_PROFILE_REQUIRED / CAPTURE_FORMAT_UNSUPPORTED / LIFECYCLE_UNSUPPORTED | ERROR，要求校准配置、适配后端或系统能力；不把配置失败当断流自动重启 |
| CAPTURE_UNAVAILABLE / UNSUPPORTED_DEVICE | ERROR，保存设备指纹与失败阶段；调查支持后再显式重试 |
| BUSY / DAEMON_BUSY / INSTANCE_MISMATCH | 拒绝本次冲突命令，保持已有合法会话；不停止未知实例 |
| BIND_FAILED / START_TIMEOUT / SOCKET_PERMISSION_DENIED | 清本次启动资源并ERROR；用户修复绑定/前台/权限后显式重试 |
| STOP_TIMEOUT | 已切STOPPING并清结果；清可安全释放的本会话资源后ERROR，报告未确认退出的进程/worker |

恢复退避默认200/400/800/1600/2000ms，之后2s，带±10%抖动且总时长≤30s；STOP立刻取消退避。daemon自动重启最多每30s一次、只重启当前会话启动的匹配实例；用户显式start重置恢复预算。

启动ready预算REALTIME10s、DIAGNOSTIC15s，不包括等待用户交互授权的无限时间；root检查需可取消并到期提示重试。stop优雅阶段2s，随后只针对已校验身份的本会话daemon强制终止再等待1s；不能用全局killall代替实例管理。Interpreter.invoke若仍未返回，禁止从其他线程并发close；隔离该worker、返回STOP_TIMEOUT并拒绝新start，提示重启App进程，不能虚报已释放。

## C07 模型更新与发布边界

更新只从用户显式配置的HTTPS清单来源获取，默认关闭。清单必须包含 `manifestVersion=1, modelId, modelVersion, sdkMinVersion, modelUrl, metadataUrl, modelSha256, metadataSha256, modelBytes, metadataBytes`；URL和hash为真实服务生成，下载大小分别≤256MiB/64KiB且与声明完全相符。同一modelId/version不可变。模型metadata中的sha256须等于manifest.modelSha256。sdkMinVersion与SDK library自身的单调版本code比较，不与宿主APK versionCode或Android API等级比较。

可允许同一明确信任来源的HTTPS CDN重定向，最多3次，拒绝降级HTTP；下载connect超时15s、read60s、整个更新5分钟截止。一次只进行一个更新，stop可取消尚未进入激活阶段的下载。

持久化目录：`filesDir/models/<modelId>/`为不可变完整版本；`staging/<attempt-id>/`接收临时下载；`active.json`仅保存已验证激活版本指针；`previous.json`保留上一个可用版本。指针以同目录临时文件+原子rename提交，文件校验/解释器加载前不得覆盖active。文件夹从modelId校验生成，不能由远端任意路径指定。

流程分为暂存与激活。暂存：下载两文件→长度与两个SHA-256→元数据schema/SDK兼容性→写staged标记；Staged仅表示文件完整，不表示runtime通过。激活：推理worker在帧边界进入STARTING并清latest→关闭旧解释器→加载候选并核对真实tensor→CPU固定输入3次smoke/warmup→按配置选择GPU或CPU→live状态再完成一帧新鲜结果→原子提交active并回到RUNNING。全程不同时保留两个可执行Interpreter；旧文件版本仍保留以供回滚。失败重新加载旧模型并保持active；旧模型也不可用才ERROR。若引擎原为STOPPED，激活只做离线核验/预热并关闭实例、提交指针、保持STOPPED，不启动root采集。进程中途死亡下次只读取完整且核验通过的active，staging不自动激活。

哈希提供传输完整性，可信来源来自预先配置的HTTPS服务；若后续需要不可信镜像分发，再单列签名验证版本。v1不把未设计的签名能力写成已提供。

发布同时交付APK/AAR、对应protocol2 daemon、模型包、全部文件SHA-256、工具链锁、数据/模型评估报告、设备适配清单。SDK/daemon/模型版本不匹配必须失败得清楚，不“尽力猜着运行”。

## C08 Native控制记录、设备profile与崩溃恢复

### 结构化控制输出

运行中的daemon **stdout只输出JSONL生命周期记录**，像素只走Socket，普通日志/统计走stderr。每行UTF-8、最大16KiB；SDK必须持续消费两管道，并以有界环形缓冲保存最近诊断。`--version`和`--status --json`各输出单个JSON对象后退出，不执行截图或改变状态。

生命周期记录的固定字段：`schemaVersion=1, event, state, pid, processStartTicks, bootId, ownerTokenHash, daemonVersion, buildId, protocolVersion=2, emittedAtNs, code, message, recoverable`。event取STARTING/READY/FAILED/STOPPED；state使用Native状态名；code正常时为null，错误时用C06公开code；message为可读简述，不要求App解析。64位 `processStartTicks/emittedAtNs` 用十进制字符串防止JSON工具丢精度，pid/版本是普通整数；ownerTokenHash是32hex原token对应的SHA-256，不回显token明文。

取得实例锁后**立即**持久化STARTING身份并发STARTING记录，再做可能阻塞的截图预检；预检完成更新READY，失败至少输出完整FAILED(code/message)后非零退出。清理可以移除运行状态文件，不能使App只能拿到无原因退出码。若进程来不及输出FAILED即崩溃，SDK才将其归为DAEMON_EXITED。首次STARTING被阻塞时也必须可通过已落盘身份精准停止。

命令退出码：0成功/幂等无实例；2配置/合同错误；3root/访问被拒绝；4捕获格式/能力/校准不支持；5实例繁忙/身份不匹配；6运行时捕获失败；7停机超时。退出码只作粗分类，公开错误以JSON的code为准；signal退出单列，不伪造结构化成功。

`--version`字段沿用NATIVE：schemaVersion、daemonVersion、gitSha、buildId、abi、minApi、protocolVersions、backends。`--status --json`返回 `schemaVersion=1, exists, identityMatches, state, identity, error`；identity包含上述pid/startTicks/bootId/ownerTokenHash/version/buildId，error为C06结构或null；不存在时exists=false/state=STOPPED。App不得只根据pid文件是否存在判断活实例。

### capture-profile生产与消费

profile是设备/后端的经验证配置，不是任意用户手填的“成功开关”。文件≤64KiB，字段固定：

| 字段 | 类型与要求 |
|---|---|
| schemaVersion / status | 1；`candidate`或`verified` |
| profileId | 1–80字符安全标识，不含路径字符 |
| deviceFingerprint / sdkInt / abi | 实际系统fingerprint、API、arm64-v8a |
| backend / backendBuildId | `cli-raw`、`cli-png`或已实现的`surfaceflinger`；实际后端构建id |
| rawHeaderBytes / sourcePixelFormat / sourceDataspace | cli-raw分别为12或16、校准格式整数、dataspace整数或`unknown`；其他后端为null或其已知源格式，不强套raw |
| outputColorSpace | 固定`sRGB`；明确HDR/宽色域必须有经过测试的转换 |
| orientationMode | `already_current`或`native_needs_rotation`；后一种必须对全部支持旋转提供测试通过的变换 |
| supportedDisplays | 首版仅 `[0]` |
| supportedRotations | 经校准的0/90/180/270子集；不在子集的方向明确拒绝 |
| calibratedAtUtc / evidenceSha256 | ISO8601时间、校准报告文件SHA-256 |

生产流程由T17实现：诊断App显示已知色块/边角标签的全屏校准页→按支持方向采集raw与PNG及C04元数据→生成candidate和比较报告→校准校验器确认头/长度/色彩/方向/坐标一致→`--verify-calibration <candidate> --evidence <report> --output <verified-profile>`产出verified profile；检测到不支持方向保留明确子集，不谎报四方向均通过。报告包含每个方向的输入hash、输出hash、比较容差与PASS/FAIL，不以手改status字段替代证据。

候选和证据默认在开发端设备档案目录；App“导入采集配置”读取profile与证据文件对，验证evidenceSha256和设备/buildId匹配后复制到 `filesDir/runtime/capture-profiles/<profileId>/`，daemon通过 `--capture-profile <absolute-path>`读取。首台不必先实现UI导入：T17部署工具可在调试App提供的受控导入接口完成同一校验，但不能把Android私有目录能否写入当作默认adb能力。

正式live只接受verified且设备/后端匹配的profile；candidate仅允许探测/固定方向诊断。未知raw布局且PNG正常时，T17的预定替代是 `cli-png`（固定libpng依赖、校验尺寸/颜色后归一化），重跑同套校准；它仍是DIAGNOSTIC，不能据此绕过高帧率后端验收。已有verified配置与新build不匹配时重新校准，不自动迁移。

### App崩溃后的自有daemon恢复

App在启动daemon之前，原子写 `filesDir/runtime/daemon-owner.json`：schemaVersion1、App uid/pid、App的`/proc` startTicks、bootId、ownerToken、期望daemonVersion/buildId、创建BOOTTIME；收到STARTING后补充daemon身份。文件仅App私有，不进入日志或发布包。

新进程默认STOPPED，不自动接管旧daemon。下一次用户显式start先读旧记录：确认同App UID、旧App进程已退出、bootId/daemon startTicks/buildId/token等身份吻合，再用**旧token**通过pidfd精确停止遗留实例；成功后删除旧记录，用新session/new token启动。若旧App仍存活或身份不符，返回BUSY/INSTANCE_MISMATCH；不能因为token不同就永久卡住，也不能停止其他owner实例。

记录尚未补全daemon pid时，可用旧token的hash匹配 `--status --json` 获取身份后进行同样核验。跨重启的stale记录只能清理记录本身；不能沿用旧boot的pid执行kill。正常stop确认资源释放后删除owner记录，STOP_TIMEOUT保留记录供诊断恢复。

root停止helper采用NATIVE规定的pidfd打开→复核身份→发送信号→poll退出，避免PID复用窗口。能力探测失败按LIFECYCLE_UNSUPPORTED终止自动live，不退到全局killall。实例锁和版本入口更新由同一事务约束，详细路径见NATIVE。

更新服务v1采用静态HTTPS文件托管即可，不要求动态后端：先上传不可变 `models/<modelId>/` 下的模型和metadata→核验远端长度/hash→最后原子发布 `updates/latest.json` 清单。客户端只接受高于当前版本的新候选；需要远端回退时，以更高modelVersion和新modelId重新发布已知良好权重/metadata，不覆盖旧版本，不通过降低latest版本绕过检查。本地激活失败回滚不受“只下载较新版本”限制。服务地址由部署配置提供，未配置时无网络；T25使用本地受控测试服务验证HTTP/中断分支，T26记录真实HTTPS分发路径和校验清单。
