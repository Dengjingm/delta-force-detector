# Delta Force（三角洲行动）敌方干员视觉检测

目标是在 root Android 设备上检测游戏画面中的敌方干员，返回中心坐标和置信度。当前聚焦单类 `enemy`，训练候选为 YOLOv8s-P2、960×960 输入，15fps 为待验证目标。

**当前状态：原型脚手架，尚未跑通。** 仓库有训练、截图守护进程和 Android 推理代码，但缺少数据与模型产物，且存在编译错误和接口不匹配。当前不能直接训练后装机使用，也没有经过验证的精度或帧率数据。

## 文档导航

**准备开发时先读 [完整实施规划](IMPLEMENTATION_PLAN.md)**，再按 [26项任务卡](docs/plan/TASKS.md) 执行。规划已包含协议、模型包、状态机、模块设计、失败分支和数值验收门槛；所有实现仍待开发。

- [AGENTS.md](AGENTS.md)：Agent 协作规则、跨模块约束、验证要求。
- [CODEMAP.md](CODEMAP.md)：完整文件树、关键调用链、数据/模型/帧接口与当前断点。
- [PROGRESS.md](PROGRESS.md)：现状评估、问题优先级、后续里程碑及验收条件。

## 检测范围与目标设备

唯一类别为 `0: enemy`，覆盖近、中、远距离可确认的敌方干员。远距离小目标是重点；P2 和更高输入分辨率只是候选策略，效果须通过数据与真机测量确认。

首台设备由用户指定为红米 K70：第二代骁龙 8、12GB + 4GB 扩展内存、3200×1440 屏幕。用户计划后续 root，Android/HyperOS 版本和实际游戏截图分辨率待记录。构建配置最低 API 28（Android 9），不代表已验证全部 Android 9+ 设备。

## 架构与现有入口

```text
离线：截图/标注 → training/train.py → PyTorch 权重 → export_tflite.py → TFLite
在线：root daemon → Unix Socket → Android Service → 预处理/推理/后处理 → SDK 结果
```

| 模块 | 当前入口 | 状态 |
|---|---|---|
| 训练 | `training/train.py`、`training/data/dataset.yaml` | 缺训练/验证数据；P2 权重来源待修正和验证 |
| 导出 | `training/export_tflite.py` | `nms=True`、默认 fp16；与 Android 输出解析不匹配 |
| 截图/传输 | `native-daemon/main.c`、`screencap.c`、`socket_server.c` | 构建与截图路径有阻断，需先建立正确的低速闭环 |
| Android | `ScreenVisionSDK`、`DetectionService` | 编译、Binder/结果桥接、权限、模型生命周期待修复 |
| 更新 | `ModelUpdater` | 占位服务地址；下载、校验、选用和回滚未闭环 |

Android 当前是 APK 工程，没有 Activity 演示入口、独立 AAR 模块或模型资产。`VisionApp` 不会自动启动 SDK。

## 现有命令与使用条件

训练与导出前需要满足 [M2 的数据、模型来源和依赖条件](PROGRESS.md#里程碑与验收)；Android 集成另依赖 M1 构建基线，跨引擎一致性在 M3 验收。以下保留当前源码的真实命令和路径：

```sh
cd training
python -m pip install -r requirements.txt
python train.py
python export_tflite.py --weights runs/hok_detector/weights/best.pt
python visualize.py --dir data/images/train
```

当前训练目录名仍为 `hok_detector`；后续统一为 `delta_enemy` 是待办。`visualize.py` 的标签目录计算存在错误，修复前不能用于确认标注完整性。导出脚本尚未可靠定位并复制产物到 `android-app/app/src/main/assets/model.tflite`。

Native 入口是 `cd native-daemon` 后运行 `./build.sh`，需要 Android NDK 和 CMake。脚本目前将构建、资源复制和自动 adb 推送绑在一起，并有编译/打包阻断；先参考 PROGRESS 的修复项。Android 由 Android Studio 打开 `android-app/`；仓库未提供 Gradle Wrapper，尚未通过编译。

当前 SDK 签名意图如下，结果通道仍待接通；这不是可运行演示：

```kotlin
ScreenVisionSDK.start(context, listOf("enemy"))
ScreenVisionSDK.observe().collect { results ->
    results.forEach { result ->
        println("${result.elementId}: (${result.x}, ${result.y}), ${result.confidence}")
    }
}
ScreenVisionSDK.stop(context)
```

`observe()` 返回 `SharedFlow<List<DetectResult>>`；`elementId` 是类别名称，不是跟踪 ID。root `tap()` / `swipe()` 是独立 API，检测流程当前不会自动调用。

## 后续推进顺序

先补可复现构建、数据规则和模型契约；用少量样例验证 Python/TFLite/Android 离线一致性。设备 root 后打通正确截图、Socket 和 SDK 生命周期，再建立训练效果与 K70 持续性能基线。模型热更新、多机型适配和高帧率截图后置。

当前缺陷证据见 [PROGRESS.md](PROGRESS.md)；完整目标设计从 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 进入，开发验收按 [VALIDATION.md](docs/plan/VALIDATION.md) 执行。本轮仅完善文档与计划，没有修改代码实现。
