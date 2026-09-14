# Native 实施规格

> 状态：待实施的开发规格，不是已实现能力。本文只规划 Native 模块；本轮不创建或修改 C、头文件、构建脚本、测试程序或设备文件。跨端数据结构以 [CONTRACTS.md](CONTRACTS.md) 为唯一权威；当前源码地图见 [CODEMAP.md](../../CODEMAP.md)。

## 1. 交付范围与顺序

首台设备按用户提供的信息规划：红米 K70、Snapdragon 8 Gen 2、3200×1440、12 GB 物理内存，另有 4 GB 扩展内存。内存预算按物理内存制定。实际 Android API、ROM fingerprint、当前渲染分辨率、root 工具和 SELinux 域尚未探测；这些信息影响设备适配与验收，不妨碍主机侧协议、解析、生命周期和构建工作的实施。

Native 分两次交付，不能用第二次的目标描述第一次的能力：

1. **正确性基线**：可重现构建和部署；通过系统 `screencap` CLI 获取并规范化图像；v2 帧协议；可停止、重连、限时和追踪版本。诊断默认上限 5fps，实际速度待真机测量。
2. **实时交付**：在基线的同一 FrameBuffer 和 v2 合同下，验证持续截图后端；通过全链路 15fps 及长时稳定性验收。若后端验证失败，保留诊断能力并明确报告实时指标未达成。

首版只处理内置主显示屏。多显示器、HDR 色彩还原、无 root 采集、共享内存/GPU 跨进程零拷贝不进入基线。必要的接口扩展先修改 CONTRACTS，再同步 Android；不能在任一端单独改变字节流。

## 2. 从现有代码进入实施

| 当前入口 | 计划修改 | 完成后应具备的行为 |
|---|---|---|
| `native-daemon/screencap.c` | 删除失效的 libgui 符号猜测路径；改为后端门面 | 后端初始化、一次完整捕获、取消、关闭有明确返回状态 |
| `native-daemon/screencap.h` | 明确归一化帧、尺寸、所有权和释放规则 | 后端、生产线程、发送线程之间没有跨帧借用悬空指针 |
| `native-daemon/socket_server.c/.h` | 用 v2 编解码与完整发送取代裸 `uint32_t`/单次 writev | LE 编码、长度校验、短写/EINTR/EAGAIN、断连和超时有一致处理 |
| `native-daemon/main.c` | 配置、单实例、启动预检、线程协调、状态和真实统计 | 没有客户端时不持续截图；启动失败非零退出；停止有截止时间 |
| `native-daemon/CMakeLists.txt` | Android/host 分离、依赖与测试目标 | C11 严格警告；可在无设备环境编译核心逻辑 |
| `native-daemon/Android.mk` | 与 CMake 使用同一源文件集合；提供显式 ABI/API 配置 | 备用构建不能悄悄落到不同 ABI 或平台级别 |
| `native-daemon/build.sh` | 参数化构建、产物记录，移除隐式部署和 res/raw 复制 | 默认只写 Native 构建目录；没有 adb 副作用 |

现有 `chmod` 缺声明、FPS 格式字符串类型不匹配必须先修，不能降低警告级别掩盖。纯 CLI 基线不再需要 libgui/dlopen；若保留可选动态加载器，其目标必须显式链接 `dl`。不要把“补了 `-ldl`”作为私有 API 截图已可用的依据。[Android NDK 原生 API 文档](https://developer.android.com/ndk/guides/stable_apis#c_library)

## 3. 截图基线：CLI、raw 解码和方向

### 3.1 固定执行方式

基线后端命名 `cli-raw`，通过子进程执行绝对路径 `/system/bin/screencap`，读取二进制 stdout。参数中不含 `-p`，不写每帧临时文件，不通过拼接 shell 字符串执行。stderr 单独收集并限长记录，不能混入图像管道。执行路径只由发布配置或 host 测试注入，SDK 不接受任意可执行命令。

每次捕获创建一个子进程；stdout 非阻塞读取，由 poll 和停止事件驱动。所有父进程 socket、锁文件及状态文件 FD 设置 close-on-exec。多线程进程优先用 `posix_spawn`；如必须 fork，子分支只进行允许的 FD/信号整理和 exec，不能调用父进程日志、分配器或锁。stdout EOF 之后仍要等待子进程成功退出；非零退出、超时、被信号终止都不得发布帧。

捕获期限包括进程启动、读取、退出和归一化。达到截止时间时关闭管道、终止该捕获子进程并回收；不可留下 `screencap` 僵尸进程，也不能为了等待子进程把 daemon 停止无限延后。

### 3.2 raw 布局识别与锁定

官方源代码中至少有两种布局：旧布局写 `width,height,pixelFormat` 共 12 字节；新布局再写一个颜色空间枚举，共 16 字节。两者均逐行输出 `width * bytesPerPixel` 字节，源 GraphicBuffer 的行 padding 不写入文件；`-p` 明确选择 PNG。[AOSP Android 7.1.2 screencap](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-7.1.2_r1/cmds/screencap/screencap.cpp)、[AOSP Android 9 screencap](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/cmds/screencap/screencap.cpp)

实现规则：

1. 先读取至少 12 字节并拒绝 PNG signature、零/越界尺寸及未知 pixelFormat；采用显式 LE 读取，不把字节数组强转成未对齐结构体。
2. 使用检查过的 `size_t`/64 位乘法计算原始负载长度，再分配；最大规范化像素负载为 64 MiB，原始读取也必须有独立上限。
3. 设备探测可把实际总长度与 `12 + rawPayloadBytes`、`16 + rawPayloadBytes` 比较，产生候选布局；不能仅由 Android 版本推断头长。
4. 候选必须在静态校准页上与 PNG 参考帧比对，检查尺寸、四角色块、中心网格与通道。通过后，将头长与 ROM fingerprint、后端版本绑定，作为启动配置；配置失效需重新探测。
5. 连续流固定使用已验证的头长。每帧要求长度精确相等；额外字节、截断字节、格式切换均失败，不逐帧在 12/16 之间自动尝试。

第 5 条不可省略：一个 16 字节头的帧若恰好少了 4 字节像素，长度会与 12 字节头候选相同；仅凭长度自动回退会把损坏数据当成合法旧格式。没有真机布局记录时，host fixture 可以显式指定 12 或 16，设备 live 模式返回 `CAPTURE_PROFILE_REQUIRED`，由一次性校准流程生成配置。

### 3.3 支持的像素格式

| raw 格式 | 转换成紧密 RGBA8888 的规则 |
|---|---|
| RGBA_8888 = 1 | 保持 R、G、B、A 字节顺序，逐行复制 |
| RGBX_8888 = 2 | 保持 RGB，alpha 写 255 |
| RGB_888 = 3 | 每 3 字节 RGB 扩展为 RGBA，alpha 写 255 |
| RGB_565 = 4 | 按目标 LE 读取 16 位；R5/G6/B5 通过位复制扩展到 8 位，alpha 写 255 |
| BGRA_8888 = 5 | 交换 B/R，保留 G/A |
| 其他，包括 RGBA_FP16、RGBA_1010102、YUV | 返回 `UNSUPPORTED_PIXEL_FORMAT`，记录原始枚举，不按 4 字节 RGBA 猜测 |

枚举来自 [AOSP graphics-base-v1.0.h](https://android.googlesource.com/platform/system/core/%2B/refs/heads/main/libsystem/include/system/graphics-base-v1.0.h)。C01 的 pixelFormat=1 不只要求通道顺序，还要求 sRGB/SDR；K70 首版采集和训练输入固定在此色彩条件。颜色空间字段和像素格式是两件事：把 BGRA 改为 RGBA 不等于完成 Display P3/HDR 到 sRGB 的转换。

raw 声明支持的 sRGB 时仍要校准通道与方向；raw colorspace=unknown 或旧 12 字节头没有颜色声明时，必须通过固定 SDR 设置、静态 raw/PNG 对照并在设备 profile 留存证据后才允许。已明确为 HDR/wide-gamut 而归一化器尚不能转换时返回 `CAPTURE_FORMAT_UNSUPPORTED`，不能把它当 sRGB 送入模型。后续 SurfaceFlinger 或 PNG 后端仍有显式色彩归一化职责，不能仅因改了获取 API 就声称解决了色域。

PNG 只作诊断参考和明确的兼容分支。若该 ROM 的 raw 布局无法验证、但系统 PNG 输出正确，则下一任务固定实现 `cli-png`：锁定 libpng/zlib 来源和版本，限制解码尺寸与内存，复用同一归一化器。该分支继续标记低速；不能把读取 PNG 当作“raw 兼容成功”。

### 3.4 方向、分辨率与校准

wire 中的 width/height 是已经朝向当前显示方向的图像尺寸，rotationDegrees 是元数据。Android 不再对收到的像素旋转。`screencap` 自身可能已经按显示方向处理，必须先用四角不对称标记验证，不能看到 landscape 就再转一次。Android 的显示旋转值来自 Surface 的 ROTATION 枚举；元数据取枚举对应的 0/90/180/270，不自行引入另一套顺逆时针含义。[Android Surface API](https://developer.android.com/reference/android/view/Surface)

raw 没有旋转字段，单靠宽高无法区分 0/180 或 90/270。live 方向信息由 Android 发布的 display-state 控制平面提供，精确字段与规则引用 [CONTRACTS C04](CONTRACTS.md#c04-显示控制与坐标参照)。App 原子更新 `filesDir/runtime/display-state.json`，Native 通过 `--display-state-file` 仅读取。截图前后读取 generation，发生变化则丢弃这帧并关闭当前数据连接；Android 清候选和在途结果、更新显示文件后重连。单连接内宽高/方向固定，新连接换 streamId，Android 在使用结果时再次检查当前显示状态。连接 epoch 与显示 generation 是不同的失效标记，不能混用。

纯 Native 诊断只在 DIAGNOSTIC profile 允许显式 `--rotation-degrees` 和锁定方向；此模式只能验收固定方向截图，不能冒充动态旋转已完成。无可靠方向来源、root 无法读取 App 私有控制文件、尺寸不匹配、文件损坏或 generation 倒退均返回 `DISPLAY_STATE_UNAVAILABLE`，不默认填 0，也不无限等待。设备访问验收由主验证计划 T16 覆盖。

设备需要验证 0/90/180/270 四个方向、实际 3200×1440 及系统允许的其他渲染尺寸。若后端捕获是原生方向，归一化器在 Native 只旋转一次；四个角的标记 ID、图像边界和网格中心必须与当前显示一致。K70 标称分辨率不能覆盖实际 `wm size` 或每帧头部尺寸。

## 4. FrameBuffer 与线程所有权

计划将 FrameBuffer 分成“后端原始视图”和“可发布的归一化帧”，避免一个含糊的 pixels 指针承担所有权转换。

| 对象 | 必需内容 | 所有权 |
|---|---|---|
| 后端原始视图 | 数据长度、宽高、原始 stride、格式、可选颜色空间、获取/释放句柄 | 仅捕获线程持有；离开后端调用周期前必须释放 |
| 归一化帧 | 自有 RGBA 存储、capacity/实际长度、width/height/rowStride、rotation、captureStart/End、捕获序号 | 从池独占取得；发布后内容不可变 |
| latest slot | 最多一个归一化帧所有权、连接 epoch | 捕获线程原子交换；旧槽帧归还池 |
| sender in-flight | 当前帧的独占所有权和发送偏移 | 完整发送或连接关闭前不能回收、覆盖或改元数据 |

正常运行固定使用三个归一化缓冲：正在捕获/转换的一个、latest 槽一个、发送中一个。没有空闲缓冲时跳过本次捕获，不临时无限扩池。尺寸变化时先检查所有分配上限，失败保留已拥有对象的可回收状态。任何 `realloc` 必须通过临时指针提交，不能在失败时丢失旧内存。

在 3200×1440 下，一帧为 18,432,000 字节，三个 RGBA 缓冲约 52.73 MiB；再加一个同等 raw 输入缓冲约 70.31 MiB。此数不含截图子进程、解析器、线程栈、socket 内核缓冲或 Android 内存。最大 4096² 帧会使四个同等缓冲接近 256 MiB，必须按单独的峰值预算测试，不能依赖“设备有 12 GB”掩盖无界分配。

captureStartNs 在调用后端之前记录；captureEndNs 在像素转换、方向归一化完成并可发布时记录。二者不是游戏渲染时间或显示硬件的 present timestamp，不据此声称精确测得源画面年龄。目标 Android 使用 CLOCK_BOOTTIME，与 Android `elapsedRealtimeNanos()` 对齐；不得换成 CLOCK_MONOTONIC 后直接跨进程相减。[AOSP SystemClock 实现](https://android.googlesource.com/platform/system/core/%2B/846fedb3664c9b2a43970c133071b68a8278014f/libutils/SystemClock.cpp)

host 测试通过可注入时钟模拟 BOOTTIME，记录测试时钟类型；macOS host 时间测试通过不构成 Android 休眠时钟对齐证据。

## 5. v2 传输实施要求

### 5.1 编码和验证

帧头固定 64 字节，magic 为 `SVF2`，version=2，所有数值字段 LE；精确偏移只维护在 CONTRACTS。使用逐字段编码和独立 golden bytes 测试，不直接发送 C struct，不依赖编译器 padding 或本机字节序。

发布前统一验证：宽高均 1..4096；pixelFormat=RGBA8888；rowStrideBytes=width×4；payloadBytes=width×height×4 且不超过 64 MiB；rotation 是四个合法值之一；frameId 和时间均在正 Long 范围内；captureEndNs≥captureStartNs。接收侧要求时间不超过本机当前 BOOTTIME，仅允许 C01 的 1ms 采样容差。头部/负载总长度的加法同样要检查。后端含 padding 时必须先逐行生成紧密 RGBA，发送器不读取未定义的行尾数据。

frameId 是进程内捕获成功的单调序号，从 1 开始；允许因 latest 槽替换产生间隙，不允许同连接内回退或重复。每次接受新连接从系统随机源生成非零 streamId，且必须不同于上一连接；使用完整 u64 位模式、只比较等值，不能以有符号正负拒绝它，也不能使用 PID 或固定时间值代替。随机源失败则连接初始化失败，不做低质量回退。清除前连接 latest 槽，并通过连接 epoch 拒收此前已在捕获中的帧。frameId 达到正 Long 上限时退出重启，不回绕。

v1 不静默兼容。SDK 启动前检查 daemon 支持的协议版本，客户端首帧再次验证 magic/version/headerBytes；不符映射 C06 的 `PROTOCOL_MISMATCH`。部署 Native v2 时必须同步部署 v2 Android；旧 socket 文件存在不能作为服务可用证据。

### 5.2 完整帧、背压和断连

发送器使用非阻塞 socket、poll 和“当前帧累计发送偏移”。头部可以和负载 scatter/gather，但每次成功仅前进实际写入字节；EINTR 重试，EAGAIN 等待，0 字节进展进入截止时间判断。Linux 用 `send`/`sendmsg` 的 MSG_NOSIGNAL 防止 EPIPE 变成进程级 SIGPIPE；host macOS 测试走对应的 SO_NOSIGPIPE 适配。[Linux send 语义](https://man7.org/linux/man-pages/man2/send.2.html)

一旦发出某帧的第一个字节，只能完成该帧，或者关闭该连接。不能从半帧切到更新帧，也不能在同一流中混入日志、PING/PONG 或错误文本。发送失败关闭连接、释放 in-flight、清空旧 epoch，回到等待连接；Native 不主动连接 Android。

capture 线程继续更新 latest 槽，sender 每次完成后只取当前最新完整帧。Android 也只保留最新完整帧，不能只读半个 payload 后为了“跳旧帧”去找下一个 magic。慢客户端由 deadline 触发断开，不能通过加大缓冲无限容纳旧帧。

一个实例只服务一个客户端；第二个客户端在第一个仍存活时立即关闭并记录 BUSY，不抢占已建立的会话。生产模式通过 SO_PEERCRED 校验 SDK 的实际 UID；文件权限与 SELinux 仍须真机验证。[Linux Unix socket 与对端凭据](https://man7.org/linux/man-pages/man7/unix.7.html)

### 5.3 统一期限

| 项目 | diagnostic 基线 | realtime 候选 |
|---|---|---|
| 目标采集上限 | 5fps | 15fps；可用参数调整，禁止将参数值记为实测 FPS |
| 单次捕获截止 | 5 秒 | 1 秒；启动预检可用 5 秒 |
| 一帧发送截止 | 5 秒 | 1 秒 |
| poll/accept 检查停止的最长间隔 | 200ms | 200ms |
| 连续捕获失败退出阈值 | 3 次 | 3 次 |
| 已启动但无成功新帧的最长时间 | 30 秒 | 30 秒 |
| 优雅停止/强制终止期限 | 2 秒 / 再 1 秒 | 2 秒 / 再 1 秒 |

无客户端的 IDLE 时间不计入“无成功新帧”超时。每帧发送总截止从第一次发送尝试起算，读取总截止从本帧首字节起算，不能因每次有少量进展而重置；空闲等待头部单独使用同 profile 的 1 秒/5 秒截止。更改默认值时同步 CONTRACTS 和 Android，不让一端 1 秒断开、另一端仍合法等待 5 秒。Android 重连退避和总截止统一引用 C06（最长 30 秒）；耗尽后明确 ERROR，而不是无限后台重启。

## 6. 安装、就绪与进程生命周期

### 6.1 设备路径与身份

保留 SDK 执行路径 `/data/local/tmp/screen-visiond` 和 socket 路径 `/data/local/tmp/screen-vision.sock`；新增计划路径为独立锁文件、PID/状态文件和安装 staging 文件，名称随 `--pid-file` 派生。持锁文件不在运行期间删除或重新建 inode，避免重复实例绕过锁。

PID 状态至少记录：schemaVersion、pid、boot_id、`/proc/<pid>/stat` starttime、实际可执行文件身份、daemonVersion/buildId、ownerToken、启动 BOOTTIME、状态和 socket 地址。ownerToken 为 SDK 每次 live session 另外生成的 128 位随机标识，不等同于 C05 的正 Long sessionId；它用于限定停止所有权，不代替 UID 或文件访问控制。状态更新使用同目录临时文件+原子 rename，读取需要最大字节数与 schema 校验；状态路径及父目录的所有权、文件类型和链接必须先验证，不能跟随可疑链接覆盖其他文件。

停止命令不能仅相信 PID 文件：必须同时核对 boot_id、PID starttime、可执行文件身份与 ownerToken。PID 被复用、状态来自上次开机、token 不一致时返回 `INSTANCE_MISMATCH`，不发送信号。常规路径不使用 `killall`；开发者手动全局清理只属于独立诊断命令。

为关闭“身份检查之后、kill(pid) 之前 PID 被复用”的窗口，首台设备的 root 控制 helper 要探测 `pidfd_open` / `pidfd_send_signal`：打开目标 pidfd 后再次核对身份，通过同一个 pidfd 发 TERM/KILL 并 poll 退出，而不是检查一次后使用裸 PID。API 28 不保证内核提供这些调用；不支持或被策略拒绝时标记 `LIFECYCLE_UNSUPPORTED`，首版保留手动前台诊断，自动 live 的停止验收不通过，不退回可能误杀的全局清理。[Linux pidfd_open](https://man7.org/linux/man-pages/man2/pidfd_open.2.html)、[Linux pidfd_send_signal](https://man7.org/linux/man-pages/man2/pidfd_send_signal.2.html)

### 6.2 状态机

```text
STARTING
  → 检查配置/root/版本/单实例锁
  → 立即原子写 STARTING 身份并输出 C08 STARTING 记录
  → 检查截图 profile 与显示状态
  → 获取并验证一帧；释放预检帧
  → bind/listen、更新 READY 状态并输出 C08 READY 记录
READY/IDLE
  → accept + UID 检查 → 新 streamId/epoch
STREAMING
  → 捕获线程发布 latest；sender 发完整 v2 帧
  → 客户端断开或显示 generation 改变 → 清理本 epoch → READY/IDLE
STOPPING
  → 通知所有等待点、关连接、终止捕获子进程
  → join、释放缓冲/后端、删除自己拥有的 socket/状态
  → 释放实例锁 → STOPPED
任一不可恢复错误 → 写 FAILED + 原因 → 同样清理 → 非零退出
```

READY 代表后端预检通过且正在 listen。Android 只有读取到第一帧完整、有效的 v2 数据后才能报告 RUNNING。不使用固定 sleep 猜测就绪。SDK 必须持续消费启动进程 stdout/stderr，或重定向到有界日志，避免日志管道堵住 daemon。

SIGINT/SIGTERM handler 只设置原子停止标记并唤醒 self-pipe/eventfd，不做日志、free 或加锁。所有 poll、条件变量、捕获等待和 socket 发送都观察该停止事件。SDK/控制 helper 等 2 秒后，针对身份仍匹配的进程发 SIGKILL，再等 1 秒；若仍无法确认退出，返回 STOP_TIMEOUT 并保留诊断，不虚报已停止。捕获子进程及其可验证的进程组也必须纳入清理。

SDK 与 Native 同时重启时，另一个仍活跃 owner 的实例返回 BUSY；SDK 不接管、不停止它。App崩溃后的自有遗留实例按CONTRACTS C08持久化owner记录恢复：用户显式start、确认旧App已退出与身份匹配后，用旧token精确停止，再以新token启动。本 session 的重复 start 由 Android 串行状态机去重。daemon 崩溃后，下一次启动取得实例锁才可验证并清理陈旧 socket；同名路径若不是 socket 或身份不符则拒绝删除。

### 6.3 规划中的 CLI

以下全部是未来要实现的接口，当前源码不支持这些参数。

| 命令/参数 | 定义 |
|---|---|
| `screen-visiond --version` | 一行 JSON：schemaVersion=1、daemonVersion 字符串、gitSha 字符串、buildId 字符串、abi 字符串、minApi 整数、protocolVersions 整数数组、backends 字符串数组；不启动截图 |
| `screen-visiond --protocol-version` | 输出整数 `2`，退出 0；用于简短兼容检查 |
| `--backend cli-raw` | 基线后端；未知名字立即失败，不静默回退 |
| `--profile diagnostic` | 默认 profile；选择同表期限与 5fps 上限 |
| `--profile realtime` | 必须已通过后端能力预检；启用实时期限与 15fps 上限 |
| `--fps N` | 范围 1..30；只限制采集频率，不承诺达到该值 |
| `--socket PATH` | 默认上述固定文件系统 socket；路径长度必须合法 |
| `--pid-file PATH` | 默认 `/data/local/tmp/screen-vision.pid`；锁与状态由它派生 |
| `--owner-token HEX` | live 模式必填，32 个十六进制字符；由 SDK 会话生成 |
| `--client-uid UID` | live 模式必填，SO_PEERCRED 接受 UID 白名单；诊断客户端单独配置 |
| `--capture-profile PATH` | 经校准的 raw 布局/后端/ROM 记录；解析严格、版本明确 |
| `--display-state-file PATH` | C04 live 显示元数据来源；校验来源 UID、schema 和单调 generation |
| `--rotation-degrees D` | 只用于固定方向诊断；不能与 live display-state 同时启用 |
| `--status --json` | 核对身份后报告 STARTING/READY/STREAMING/FAILED/STOPPED；不触发启动 |
| `--stop --owner-token HEX` | 精确停止匹配实例，遵守 2 秒+1 秒期限；无实例为幂等成功 |
| `--probe --output PATH` | 一次性采集和环境探测，输出候选能力记录；未完成校准不得标记 verified |
| `--verify-calibration CANDIDATE --evidence REPORT --output VERIFIED` | 按C08核验各支持方向、raw/PNG/色彩证据并产生verified profile；不接受仅手工修改status |

生命周期stdout JSONL、status对象、退出码、profile字段及校准/导入流程统一采用CONTRACTS C08；普通日志走stderr，不与控制输出或像素混用。取得锁后必须先发布STARTING身份，确保预检中stop可定位进程。

日志包含机器可判定 code 和可读 message。Native 内部原因可细分 CONFIG_INVALID、BUSY、CAPTURE_PROFILE_REQUIRED、CAPTURE_TIMEOUT、CAPTURE_FAILED、UNSUPPORTED_PIXEL_FORMAT、DISPLAY_CHANGED、SOCKET_ACCESS_DENIED、SEND_TIMEOUT、INSTANCE_MISMATCH；公开错误只使用 C06 合同，不能让 Android 根据自由文本判断。至少固定映射：协议不符→PROTOCOL_MISMATCH；非法完整帧→INVALID_FRAME；root 缺失/拒绝→ROOT_REQUIRED/ROOT_DENIED；显示控制不可用→DISPLAY_STATE_UNAVAILABLE；不支持的像素/色彩格式→CAPTURE_FORMAT_UNSUPPORTED；捕获/发送超时→FRAME_TIMEOUT；daemon 因连续捕获失败退出→DAEMON_EXITED；未确认停止→STOP_TIMEOUT。内部原因保留在 cause，stderr 原文只用于诊断。

## 7. 构建、打包与部署设计

### 7.1 构建

主构建使用 NDK 官方 CMake toolchain，arm64-v8a、API 28、C11；API 28 是编译最低 API，不是所有 Android 9+ ROM 都已支持截图的承诺。[NDK CMake 指南](https://developer.android.com/ndk/guides/cmake)

按主计划固定初始工具链为 NDK r26d `26.3.11579264`、CMake `3.22.1`。首次构建保存 source.properties、实际 Clang 版本、host OS 和编译参数，CI 校验固定值，不扫描“最新 NDK”；若主计划变更工具链，两个构建入口和证据一起更新。NDK r26 对 C11 未声明函数本就报错，不能以宽松 host 编译替代交叉构建。[官方 NDK r26 变更说明](https://github.com/android/ndk/wiki/Changelog-r26)

规划命令：

```sh
# 只构建；未来 build.sh 的参数，当前不能直接使用。
./native-daemon/build.sh --abi arm64-v8a --api 28 --type Release

# 无 Android 设备的核心测试；未来要实现的目标。
cmake -S native-daemon -B native-daemon/build-host -DSV_HOST_TESTS=ON
cmake --build native-daemon/build-host
ctest --test-dir native-daemon/build-host --output-on-failure
```

构建产物放 `native-daemon/build/<abi>/<type>/`，包括 executable、符号文件和版本/工具链/SHA-256 清单。默认增量构建；显式 `--clean` 也只能清理经过路径校验的本模块 build 子目录。host 不链接 Android/log/libgui，使用带 printf format 检查的日志适配。

备用 ndk-build 必须显式 `APP_ABI=arm64-v8a APP_PLATFORM=android-28`，并与主构建共享源文件列表与宏；若没有持续验证资源，删除“已支持备用构建”的发布声明，只保留未验收状态。

### 7.2 显式部署与 SDK 打包

计划新增 `deploy.sh`，将构建和设备修改彻底分开：

```sh
# 未来部署接口；SERIAL 必须替换成探测出的设备序列号。
./native-daemon/deploy.sh --serial SERIAL \
  --binary native-daemon/build/arm64-v8a/Release/screen-visiond
```

设备不唯一时必须指定 serial，不能把第一台设备当作目标。脚本先检查 adb 在线状态、ABI/API、root 能力、远端可执行目录和哈希工具；失败发生在替换原二进制之前。部署事务为：推送唯一 staging 文件 → 比对 SHA-256/大小/ABI/版本 → 取得与 daemon 共用的实例锁、确认没有运行实例 → 安装完整不可变版本目录 → 提交活动入口 → 执行 `--version` 验证。默认不启动、不执行 root 改造、不修改 ROM。发现运行实例返回 BUSY，不擅自停止。

为避免“两次 rename 等于一次事务”的错误，版本目录规划为 `/data/local/tmp/screen-vision/versions/<daemonVersion-buildId>/`，含 executable 与安装清单；全部验证完后才用原子 rename 更新 `/data/local/tmp/screen-visiond` 符号链接。目录与链接由安装器校验 owner/mode，版本标识不接受路径字符。一次提交只切换一个入口，因此二进制和所属版本清单不会分离。首次从旧普通文件迁移也须持锁并保留备份；断电/中断后的下一次安装先核验入口和版本目录，不激活残缺 staging。

后续 APK 自包含交付使用 `assets/native/arm64-v8a/screen-visiond`，另附安装清单；不得复制带连字符文件到 `res/raw`。SDK 先解包到 App 私有 staging 并用 Java SHA-256 校验，再经已授权的 root 安装器执行同样的设备事务。保留上一个已验证版本；更新失败回滚，不覆盖仍在使用的版本。具体 APK 打包实现由 Android 计划承接。

协议版本、daemon 语义版本和 buildId 分开记录。SDK 只启动满足当前合同的版本；若 APK 自带 v2 而设备只有 v1，明确升级或报 PROTOCOL_MISMATCH，不尝试对 v1 socket 发送新帧。

## 8. 首台设备探测与固定决策路线

所有设备操作都在用户完成 root、提供/连接设备后执行；现在只编写探测器和 fixture 相关任务规格，不安装工具或操作手机。

| 探测项与证据 | 通过路线 | 失败路线 |
|---|---|---|
| `getprop ro.build.version.sdk`、`ro.build.fingerprint`、`ro.product.cpu.abilist`；保存原始输出 | ABI 含 arm64-v8a、API≥28，选择对应构建/设备记录 | 不满足则标 UNSUPPORTED_DEVICE；不自动换 ABI 或降低 API |
| `wm size`、当前显示 ID/rotation、校准页尺寸 | 用实际显示尺寸和显示元数据配置后端 | 无法取得方向时仅开放固定方向诊断；live 验收不通过 |
| `su -c id` 的退出码、stdout UID、stderr；root 工具版本 | euid=0 后探测命令和 socket | 返回 ROOT_REQUIRED；继续 host 与 Android 离线工作 |
| 系统 `screencap` 帮助、退出码、raw/PNG 大小与校准对照 | 锁定 raw 12/16、像素格式和 fingerprint | raw 不可解释但 PNG 正确：实现 cli-png；两者失败：标 CAPTURE_UNAVAILABLE，进入版本后端调查 |
| 以真实 APK UID 连接文件系统 socket，保存 errno、`ls -lZ`、相关 AVC 日志 | 固定文件权限与同 UID 接受策略 | 不把 chmod 成功当修复；检查目录与 SELinux。仍不可达则标 TRANSPORT_UNAVAILABLE，提交明确的 ROM 适配任务 |
| 动态旋转四方向、截图前后 display generation | 所有标记与当前屏幕一致；同连接尺寸/方向不变 | 丢弃跨 generation 帧并关闭连接；缺可靠控制平面则禁止 live，保留固定方向诊断 |
| 进程命令行、starttime、boot_id、pidfd 打开/发送信号能力、SIGTERM/退出耗时 | 通过 pidfd 启用精确所有权停止 | root 工具/内核无法核验和稳定引用进程身份时返回 LIFECYCLE_UNSUPPORTED；不退回 killall |
| 5fps/全尺寸、1000 帧与 30 分钟记录 | 发布诊断能力，开始实时后端验证 | 先修完整性、内存、停止/断流；不提前调小分辨率掩盖错误 |

文件系统 socket 因策略失败时不自动切 abstract socket：namespace 和 Android 地址类型也是跨端合同，需要新任务同时修改。abstract socket 不受文件权限控制，也不意味着 SELinux 必定允许。不得将全局关闭 SELinux 作为基线的通过条件。

## 9. 15fps 后端验证分支

### 9.1 首选：与设备版本匹配的持续捕获适配器

在目标 ROM 已完成基线记录后，单独建立 `surfaceflinger` 后端验证任务。使用该 Android 版本的 AOSP capture 接口、匹配的 C++ 类型与 Binder/GraphicBuffer 生命周期，初始化一次、连续捕获。不能沿用当前 C 文件中人工拼接的 C++ mangled symbol 或把构造函数当作工厂函数。

适配器的探测输出必须包含：设备 fingerprint、依赖库 buildId/符号、实际加载结果、权限/SELinux 结果、捕获 API 与像素格式、fence/锁释放方式、方向处理、1000 帧校准记录。源码依赖固定到已核对的 AOSP revision；不同 ROM 未匹配时拒绝加载，并可显式选择已验证的 CLI profile。

私有平台 API 没有公共 NDK 的兼容性保证；普通 App 的私有库限制也不能直接推断 root 进程必定可用或不可用。root 目标必须单独验证动态链接、Binder 权限和设备实现，API28 编译成功不替代这一步。[Android 私有原生库兼容性说明](https://developer.android.com/about/versions/nougat/android-7.0-changes#ndk)

技术验证按以下顺序，任一步失败都不进入下一步：

1. 可加载与可释放：独立进程完成一次捕获并退出，异常路径无崩溃/泄漏。
2. 正确性：与 CLI 基线的静态校准页对照，尺寸、颜色、方向通过。
3. 连续性：1000 帧和反复 start/stop，没有 GraphicBuffer/fence/FD 持续增长。
4. 性能：全分辨率下统计捕获+归一化 p50/p95、发送 p50/p95、有效送达 FPS、丢帧和内存；将帧送入 Android 全链路后再次测试。
5. 长时：前 5 分钟暖机，随后 30 分钟游戏/代表性负载；温升和调频后仍满足主验收指标。

只达成 Native 15fps 不代表推理已达成 15fps。3200×1440 RGBA 在 15fps 时纯负载约 276.48 MB/s，内存复制和 Socket 成本必须实测。第一次优化顺序是缓冲复用、最新帧背压、减少重复转换；共享内存或降低采样尺寸属于后续合同/模型对比任务。

### 9.2 失败后的固定路线

若私有 API 无法在该 ROM 稳定工作，诊断版继续使用已验证 CLI，实时能力标记未达成。下一项独立设计评审选择 Android 公共 MediaProjection + ImageReader FrameSource，复用下游归一化/模型测试，但明确这是采集架构变更，涉及用户授权、前台服务和截图源切换，不在 Native 内悄悄替换。官方 MediaProjection 有会话授权和前台服务要求，不能把 root 状态当作它们已完成。[Android MediaProjection 指南](https://developer.android.com/media/grow/media-projection)

若持续后端可用但全链路达不到性能目标，按阶段数据决定：截图/转换为瓶颈时再验证 ROI/缩放或缓冲共享；推理为瓶颈时由模型计划评估 n/s、640/960、切片。任何降低原图信息的方案须回到小目标召回测试，不能只以 FPS 通过作为发布依据。

## 10. 文件级任务与依赖

下表文件名是计划中的落点，不表示文件已创建。每个任务完成时同时更新当前地图、状态和验证证据。

| 任务 | 计划文件 | 前置 | 完成条件 |
|---|---|---|---|
| N01 构建与 host 边界 | 修改 CMakeLists/Android.mk/build.sh；计划 `sv_log.h`、`version.h` 构建产物 | 无设备 | host 核心可编译；严格警告；NDK arm64/API28 链接通过；build 无 adb 行为 |
| N02 v2 codec 与帧校验 | 计划 `frame_protocol.c/.h`、`frame_buffer.c/.h` | CONTRACTS 冻结 | 与 Kotlin 共用的 golden bytes、边界/非法头测试全部通过 |
| N03 raw 解码/归一化 | 计划 `screencap_raw.c/.h`，修改 screencap 门面 | N02 | 12/16 fixture、五种支持格式、padding/旋转、损坏输入测试通过 |
| N04 可取消 CLI 捕获 | 计划 `capture_cli.c/.h`、`io_wait.c/.h` | N03 | fake child 成功/失败/挂起/超量输出可测；停止后无子进程残留 |
| N05 有界帧与发送循环 | 计划 `frame_queue.c/.h`，修改 socket_server | N02/N04 | 三缓冲所有权、短写、慢客户端、断流/重连、epoch 隔离通过 |
| N06 生命周期控制 | 计划 `daemon_control.c/.h`、`config.c/.h`，修改 main | N04/N05 | 单实例、READY、PID 身份、停止期限、重复启动、异常清理通过 |
| N07 部署与版本事务 | 计划 `deploy.sh`、`probe_device.sh`、安装清单生成 | N01/N06 | 离线脚本参数测试、错误设备/哈希/运行中保护通过；连接设备后验证安装/回滚 |
| N08 K70 基线校准 | 计划探测报告与受控截图 fixture 目录 | N03–N07 + 用户完成 root | raw 布局、颜色、四方向、真实 APK UID 连接、1000 帧/30 分钟验证 |
| N09 实时适配器验证 | 计划 `backends/surfaceflinger/` 的版本适配文件 | N08 | 版本来源可追溯；正确性/生命周期/性能分项通过，失败有固定降级结论 |
| N10 发布收敛 | 版本清单、CI、测试结果与设备矩阵 | Native/Android/模型集成 | 同版本组合可安装、启动、输出完整有效帧、停止与回滚；性能等级如实标识 |

N01–N07 的代码与 host 测试可以在 root 前实施；N08 的设备结果不能用合成测试代替。N09 的平台私有源码不能混入 N01 的通用 C11 核心，让后续 API 适配可以单独构建和回退。

## 11. 测试矩阵与验收记录

### 11.1 无设备即可执行的测试

| 测试 | 注入方式 | 必须观察到的结果 |
|---|---|---|
| v2 字节布局 | 独立构造 64 字节 golden header、非对齐输入 | C/Kotlin 对所有字段一致；没有 host endian 依赖 |
| 头部非法值 | 错 magic/version/headerBytes、0/4097 尺寸、乘法溢出、错误 stride/format/rotation/时间顺序 | 分配和发送前失败；不尝试 v1 或继续扫描 magic |
| raw 12/16 头 | 两套布局和固定 profile fixture | 只接受对应布局；新头截断 4 字节不能自动落到旧布局 |
| 格式与行 padding | 不对称 RGB 色块、RGB565 边界、BGRA、带 padding 原始视图 | 紧密 RGBA 逐像素符合预期，padding 不发送 |
| raw 损坏 | PNG signature、未知格式、短头、少/多字节、极大尺寸 | 明确错误，旧有效 buffer 不丢失，ASan/UBSan 无越界 |
| 子进程 | fake screencap 输出分片/中途退出/无EOF/非零状态/无限输出 | 成功才发布；所有失败受大小和时间限制，结束后无子进程 |
| 短写与 EINTR | 非阻塞 socketpair、小 send buffer、缓慢分段接收、注入信号 | 字节无重复/缺失；send offset 正确恢复 |
| 半帧断流 | 头部各偏移、像素中部、尾部关闭对端 | daemon 存活并回到 accept；旧连接剩余帧不串到新 stream |
| latest 槽 | 控制 producer 快于 sender，记录占用和序号 | 占用有界；只丢完整待发帧；frameId 可跳但不回退 |
| 连接 epoch | 捕获中/发送中重连 | 清空旧槽；新连接仅有新 streamId，不发布旧 epoch 的结果 |
| 生命周期 | 无客户端/慢客户端/捕获挂起时 stop；重复 start；PID 复用与检查后退出；pidfd 不可用 | 期限内结束；锁/FD/子进程回收；不误杀其他实例；不支持时明确失败 |
| 构建/部署参数 | 临时目录、带空格路径、缺 NDK/adb、多设备、坏哈希、运行实例 | 清晰失败；不修改非目标路径/设备；build 默认不执行 adb |

Linux host 跑 socket/credential 行为与 sanitizers；macOS host 跑可移植核心和对应 socket 适配。两者都应支持 CTest 一条命令执行。测试需包含独立预期值或真实故障注入，不仅复写生产实现算式。

### 11.2 真机验收

每次记录 device fingerprint、API、root 工具/域、实际分辨率、方向、刷新率、色彩模式、二进制 buildId/SHA-256、协议版本、profile、测试时长和原始错误日志。至少包括：

- 静态四角/网格校准，四个方向，App 接收到的像素与当前屏幕坐标一致；v2 元数据与显示状态一致。
- 一次 1000 帧传输，完整帧校验无误；连接断开后 streamId 改变，旧结果被 Android 清空。
- 100 次 start/stop，包括启动后立即停止、截图中停止、客户端不读时停止；没有遗留 daemon、screencap 或持有端点的 FD。
- 5 分钟暖机加 30 分钟持续负载；K70 daemon PSS 门槛为 192 MiB，捕获子进程峰值和进程合计另记。观察稳态 PSS/RSS 没有随帧数持续上升，具体窗口/增长阈值以主 VALIDATION 为准；最大 4096² 输入单独测，不套用 K70 工作尺寸的内存估算。
- 输出真实的 `capturedFrames / wallElapsed`、`sentFrames / wallElapsed`；捕获/归一化/发送 p50/p95、latest 丢弃数、超时/失败数、峰值内存。统计分母包含限速与等待，不沿用当前“处理耗时倒数”。
- 对 diagnostic 记录实际 FPS 与可用性，不要求达 15；realtime 只有在 Android 全链路主验收通过后标记支持 15fps。任何温升降速、重连或超时都保留在统计中。

交付记录最后必须逐项区分：主机测试通过、NDK 构建通过、K70 基线通过、持续后端通过、15fps 全链路通过。未进行的项填写“待验证”及对应任务编号，不填写估计结果。
