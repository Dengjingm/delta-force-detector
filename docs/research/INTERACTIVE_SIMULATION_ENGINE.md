# 双模态交互仿真测试引擎

本模块为应用自有界面、自动化回放和人体工学实验生成可复现的触控与 IMU 样本。它不注册系统级虚拟传感器，不调用 `InputManager`、`UiAutomation`、root 命令或 HAL/内核接口；触控回放只能发往调用方显式传入的本进程 `View`。

## 模块与数据流

```text
SimulationRequest
        │
        ▼
SpatiotemporalConsistencyCoordinator
        ├── coarse angular delta ──> TouchPathSynthesizer ──> SimulatedTouchSample[]
        └── fine angular delta ────> ImuPhysicsSynthesizer ─> SimulatedImuSample[]

SimulatedTouchSample[] ──> InAppMotionEventDispatcher ──> application-owned View
SimulatedImuSample[]   ──> experiment code / offline recorder / assertion
```

`InteractiveSimulationEngine` 是统一入口。相同请求与 seed 产生逐样本一致的结果，便于回归测试。所有时间使用纳秒；角度使用弧度；角速度和角加速度分别使用 rad/s 与 rad/s²。

## 物理姿态合成

`ImuPhysicsSynthesizer` 使用五次 minimum-jerk 位置曲线的导数生成基础角速度。它先根据曲线理论峰值自动延长过短动作，再逐采样执行速度和加速度限制。默认最大角速度为 300°/s，默认最大角加速度为 1200°/s²；两者是不同量纲的独立参数。

每次轨迹为三对非主轴生成固定的 5%–15% 带符号耦合系数，并叠加每轴独立的 8–12Hz 正弦分量和高斯白噪声。随机源由 seed 控制。噪声、微震、耦合、采样率与运动学上限均可关闭或配置，实验报告必须保存完整配置。

## 触控合成

`TouchPathSynthesizer` 以起终点三分位为控制点基线，沿路径法向加入受限随机扰动，生成三阶贝塞尔轨迹。解析导数给出瞬时速度，Pressure、Size 和接触椭圆长短轴由 `log(1 + speed/referenceSpeed)` 调整，并限制在 Android 字段的有效范围。

轨迹严格以 DOWN 开始、UP 结束，中间为 MOVE；起终坐标精确等于请求值。`InAppMotionEventDispatcher.play` 在主线程按样本相对时间调度 `MotionEvent`，填充 Pressure、Size、TouchMajor 与 TouchMinor，并返回可取消句柄。

诊断 Activity 内置 `SimulationPadView` 与“Run in-app dual-modal simulation”按钮。测试轨迹只绘制在该 Pad 上，同时显示本次 Touch/IMU 样本数量与 View 是否接受完整事件序列；离开 Activity 时自动取消未完成回放。

## 双通道协调

`SpatiotemporalConsistencyCoordinator` 用 smoothstep 将大角度误差连续分配给粗调触控，将剩余部分分配给精调 IMU。两通道的角位移之和严格等于请求角位移。死区使用进入/退出两个阈值形成滞回；进入死区后不生成合成通道，并将 `retainedPhysicalGyroWeight` 设为 1。

## 使用示例

```kotlin
val engine = InteractiveSimulationEngine()
val trace = engine.synthesize(
    SimulationRequest(
        yawDeltaRad = Math.toRadians(12.0),
        pitchDeltaRad = Math.toRadians(3.0),
        touchStartPx = Point2(900.0, 500.0),
        horizontalPixelsPerRad = 800.0,
        verticalPixelsPerRad = 800.0,
        requestedDurationNs = 400_000_000L,
        seed = 123L,
    )
)

val playback = InAppMotionEventDispatcher().play(testView, trace.touchSamples)
// playback.cancel()
// trace.imuSamples 交给实验代码或离线断言，不写入系统传感器。
```

像素/弧度换算、噪声强度、运动学上限和接触属性都必须来自实验标定。默认值用于测试管线，不代表普遍人体分布。生成数据应带配置和 seed 保存，不能与真实受试者数据混标。
