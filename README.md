# yolo-research

目标是在 root Android 设备上做 YOLOv8 单类检测实验，返回屏幕中心坐标和置信度。当前聚焦单类 `enemy`，训练候选为 YOLOv8s-P2、960×960 输入，15fps 为待验证目标。

**当前状态：可构建的原型脚手架，尚未跑通检测。** M1 已生成 arm64 daemon 和 debug APK，v2 帧协议 C 端已有 host 测试；本地已隔离下载 Roboflow v1 与 Ultralytics Platform 合并候选数据，两者类别均为 `head` / `person`。候选数据尚未按 `enemy` 语义复核，合并集也没有许可证声明；模型产物不存在，运行链路仍有接口不匹配，当前不能直接训练后装机使用，也没有经过验证的精度或帧率数据。

候选数据以 GitHub Release 资产提供：[下载 Roboflow v1 YOLOv8 ZIP](https://github.com/Dengjingm/yolo-research/releases/download/dataset-roboflow-v1/roboflow-yolo-candidate-v1.zip)。文件 SHA-256 为 `e9413acedd9d789ee0f7e15412126af27cd32ef7e3bda0770f08b090c0d5f634`，许可为 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。该数据仅作 YOLO 研究候选输入，类别为 `head` / `person`，不能直接接入单类训练配置。

另有独立发布的 Ultralytics Platform 公开 YOLO 合并候选集：14,820 张、22,481 个框，类别仍为 `head` / `person`。其 [Ultralytics 合并候选集 ZIP](https://github.com/Dengjingm/yolo-research/releases/download/dataset-ultralytics-merged-20260914/ultralytics-yolo-candidate-20260914.zip) 与上面的 Roboflow v1 Release 分开，SHA-256 为 `ebcc1cc6648738e157f1173f57e7e7f546f9616907bd32081522f47ff916d4a1`。源页面标记 `No license`，该 Release 仅作候选数据留档且不授予使用或再分发权；使用前须确认授权。完整下载与静态检查证据见 [PROGRESS.md](PROGRESS.md)。

## 文档导航

**准备开发时先读 [完整实施规划](IMPLEMENTATION_PLAN.md)**，再按 [26项任务卡](docs/plan/TASKS.md) 执行。规划已包含协议、模型包、状态机、模块设计、失败分支和数值验收门槛；当前进度和剩余实现以 PROGRESS 为准。

- [AGENTS.md](AGENTS.md)：Agent 协作规则、跨模块约束、验证要求。
- [CODEMAP.md](CODEMAP.md)：完整文件树、关键调用链、数据/模型/帧接口与当前断点。
- [PROGRESS.md](PROGRESS.md)：现状评估、问题优先级、后续里程碑及验收条件。

## 检测范围与目标设备

唯一类别为 `0: enemy`，覆盖近、中、远距离可确认的检测目标。远距离小目标是重点；P2 和更高输入分辨率只是候选策略，效果须通过数据与真机测量确认。

首台设备由用户指定为红米 K70：第二代骁龙 8、12GB + 4GB 扩展内存、3200×1440 屏幕。用户计划后续 root，Android/HyperOS 版本和实际采集/截图分辨率待记录。构建配置最低 API 28（Android 9），不代表已验证全部 Android 9+ 设备。

## 架构与现有入口

```text
离线：截图/标注 → training/train.py → PyTorch 权重 → export_tflite.py → TFLite
在线：root daemon → Unix Socket → Android Service → 预处理/推理/后处理 → SDK 结果
```

| 模块 | 当前入口 | 状态 |
|---|---|---|
| 训练 | `training/train.py`、`training/data/dataset.yaml` | 有隔离候选数据，尚缺批准的单类 `enemy` 数据；P2 权重来源待修正和验证 |
| 导出 | `training/export_tflite.py` | `nms=True`、默认 fp16；与 Android 输出解析不匹配 |
| 截图/传输 | `native-daemon/main.c`、`screencap.c`、`socket_server.c` | arm64 构建通过；截图路径与 v2 Socket 接入仍有阻断 |
| Android | `ScreenVisionSDK`、`DetectionService` | debug APK 构建通过；Binder/结果桥接、模型和生命周期待接通 |
| 更新 | `ModelUpdater` | 占位服务地址；下载、校验、选用和回滚未闭环 |

Android 当前是带 `MainActivity` 诊断入口的 APK 工程，已有 Gradle Wrapper，但没有独立 AAR 模块或模型资产。`VisionApp` 不会自动启动 SDK。

## 现有命令与使用条件

训练与导出前需要满足 [M2 的数据、模型来源和依赖条件](PROGRESS.md#里程碑与验收)；Android 集成另依赖 M1 构建基线，跨引擎一致性在 M3 验收。以下保留当前源码的真实命令和路径：

```sh
cd training
python -m pip install -r requirements.txt
python train.py
python export_tflite.py --weights runs/yolo_research/weights/best.pt
python visualize.py --dir data/images/train
```

当前训练目录名为 `yolo_research`。`visualize.py` 的标签目录计算存在错误，修复前不能用于确认标注完整性。导出脚本尚未可靠定位并复制产物到 `android-app/app/src/main/assets/model.tflite`。

Native 入口是 `cd native-daemon` 后运行 `./build.sh`，需要 Android NDK 和 CMake；arm64 交叉编译已经通过。脚本仍将构建、资源复制和发现设备后的 adb 推送绑在一起，构建与部署分离仍待实现。Android 可由 Android Studio 打开 `android-app/`，也可使用仓库中的 Gradle Wrapper；debug APK 已构建通过，尚未真机安装验收。

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

`observe()` 返回 `SharedFlow<List<DetectResult>>`；`elementId` 是类别名称，不是跟踪 ID。root `tap()` / `swipe()` 是独立 API。`start(..., moveCenter=true)` 时，屏幕中心会朝最近检测目标移动。

## 后续推进顺序

先补可复现构建、数据规则和模型契约；用少量样例验证 Python/TFLite/Android 离线一致性。设备 root 后打通正确截图、Socket 和 SDK 生命周期，再建立训练效果与 K70 持续性能基线。模型热更新、多机型适配和高帧率截图后置。

当前缺陷证据见 [PROGRESS.md](PROGRESS.md)；完整目标设计从 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 进入，开发验收按 [VALIDATION.md](docs/plan/VALIDATION.md) 执行。
