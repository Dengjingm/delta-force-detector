# v2 帧协议黄金 fixture（T07）

本目录是跨端 v2 帧协议的**共同字节基准**，唯一权威字段定义见
[CONTRACTS C01](../../docs/plan/CONTRACTS.md#c01-帧数据协议-v2)。C 端编码
（`frame_protocol.c`）与 Kotlin 端解码（`FrameHeader.kt`）必须逐字节比对这里的
golden，不能各端自造期待值，也不能把生产实现输出反过来当 golden。

## 文件

| 文件 | 作用 |
|---|---|
| `generate_fixtures.py` | 确定性生成器。逐字段 `struct` 打包，并与独立手写 hex 断言一致后落盘 |
| `frame_v2_rg_2x1.bin` | 72 字节正常黄金帧（见下） |
| `manifest.json` | 记录大小、SHA-256、每个头部字段与负载像素的独立期待值 |

重新生成：`python3 contracts/fixtures/generate_fixtures.py`。生成器只依赖标准库
（`struct`/`hashlib`/`json`），不 import 任何生产编解码代码，避免实现与 golden
互相印证。

## 黄金帧内容（来自 C01 样例）

宽 2、高 1、stride 8、format 1（RGBA8888）、rotation 0、frameId 1、
captureStartNs 1000、captureEndNs 2000、streamId 1；payloadBytes=8；负载为红像素
`(255,0,0,255)` 后接绿像素 `(0,255,0,255)`。所有整数 little-endian，头 64 字节。

```
offset  size  field            value       bytes (LE)
0       4     magic            "SVF2"      53 56 46 32
4       2     version          2           02 00
6       2     headerBytes      64          40 00
8       4     payloadBytes     8           08 00 00 00
12      4     width            2           02 00 00 00
16      4     height           1           01 00 00 00
20      4     rowStrideBytes   8           08 00 00 00
24      4     pixelFormat      1           01 00 00 00
28      4     rotationDegrees  0           00 00 00 00
32      8     frameId          1           01 00 00 00 00 00 00 00
40      8     captureStartNs   1000        e8 03 00 00 00 00 00 00
48      8     captureEndNs     2000        d0 07 00 00 00 00 00 00
56      8     streamId         1           01 00 00 00 00 00 00 00
64      8     payload          RGBA8888    ff 00 00 ff  00 ff 00 ff
```

完整 72 字节 hex：

```
53564632020040000800000002000000010000000800000001000000000000000100000000000000e803000000000000d0070000000000000100000000000000ff0000ff00ff00ff
```

SHA-256：`1e1a57f8f7dc9060bcdf2f114ad61c6ff7a4625bcde66ecc27517320217e73fe`

## 两端如何消费

- **C 编码（T08）**：`frame_header_encode()` 对同一组字段值产出的字节必须等于本
  golden；`frame_header_decode()`/`frame_header_validate()` 解回字段值并与
  `manifest.json` 的 `header` 期望一致。host 测试读取本 `.bin`，不做内联 hex。
- **Kotlin 解码（T08/T09）**：显式 `LITTLE_ENDIAN` 读满 64 字节头后，字段值须与
  manifest 一致；再读满 8 字节负载，得到两个像素。用色块验证 RGBA→Bitmap 的字节
  含义，不依据 ARGB 类名猜布局。

## 校验顺序提醒（实现须遵守，fixture 只提供输入）

C01 要求在**分配像素内存前**校验 magic/version/headerBytes/pixelFormat/rotation；
宽高 ∈ [1,4096]，用 64 位算 `width×height×4` 且与 payloadBytes 完全相等、≤64MiB、
stride == width×4；要求 start≤end；streamId 连接内不变、frameId 比上一帧大。异常
帧（错 magic、version≠2、headerBytes≠64、0/4097 尺寸、乘法溢出、stride/format/
rotation/时间顺序非法）不在此处生成独立 `.bin`，其字节期待值由 T08 的 host 测试
就地构造并断言拒绝，避免本目录膨胀；正常帧的字段期待值以 `manifest.json` 为准。

## 边界

本目录只固化**帧数据协议**的 golden。NMS 六字段、LetterBox 黄金例、可控 BOOTTIME
事件序列属于 T07 的其余部分，分别随模型包（C02）与坐标合同（C03）在对应任务落
地，不在本帧 fixture 内混入模型或坐标语义。
