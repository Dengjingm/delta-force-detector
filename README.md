# Delta Force (三角洲行动) 视觉识别

三角洲行动局内元素实时检测系统。在 root 过的 Android 手机上运行时，通过 YOLOv8nano 逐帧检测屏幕中的 FPS 游戏元素并返回中心坐标。

## 检测元素 (12 类)

| ID | 元素 | 说明 |
|----|------|------|
| joystick | 移动摇杆 | 左下角控制区 |
| fire_btn | 开火按钮 | 右下角开火键 |
| aim_btn | 瞄准/开镜 | 瞄准镜按钮 |
| reload_btn | 换弹 | 弹药不足时亮起 |
| jump_btn | 跳跃 | 右侧跳跃键 |
| crouch_btn | 下蹲 | 蹲下/趴下切换 |
| minimap | 小地图 | 左上角地图 |
| health_bar | 血条 | 屏幕中央/下方 |
| operator_skill | 干员技能 | 角色技能图标 |
| pickup_btn | 拾取/交互 | 拾取物资/开门 |
| backpack_btn | 背包 | 背包按钮 |
| enemy | 敌人 | 敌方单位/标记 |

## 架构

```
┌──────────────────────────────────────────────────┐
│  SDK API (ScreenVisionSDK)                        │
│  start(classNames) / observe() / tap(x,y) / stop  │
├──────────────────────────────────────────────────┤
│  Android Service (DetectionService)               │
│  前台保活, 30fps 检测循环: Socket→Preproc→推理→NMS │
├──────────────────────────────────────────────────┤
│  Native Daemon (C, root)                          │
│  SurfaceFlinger 截图 + Unix Socket 推帧            │
├──────────────────────────────────────────────────┤
│  Root 环境: su + /data/local/tmp/screen-visiond   │
└──────────────────────────────────────────────────┘
```

## 使用步骤

### 1. 采集数据 → 标注 → 训练

```bash
cd training

# 截 200+ 张三角洲行动各场景截图 → data/images/train/
# 用 LabelImg 标注 12 类 → data/labels/train/

pip install -r requirements.txt
python train.py
python export_tflite.py
```

### 2. 编译 Native 守护进程

```bash
cd native-daemon
# 需要 Android NDK r26+
./build.sh
adb push native-daemon/build/screen-visiond /data/local/tmp/
adb shell chmod 755 /data/local/tmp/screen-visiond
adb shell su -c /data/local/tmp/screen-visiond &
```

### 3. 编译 Android App

用 Android Studio 打开 `android-app/` 编译安装。

### 4. SDK 集成

```kotlin
val dfClasses = listOf("joystick", "fire_btn", "aim_btn", "reload_btn",
    "jump_btn", "crouch_btn", "minimap", "health_bar",
    "operator_skill", "pickup_btn", "backpack_btn", "enemy")

ScreenVisionSDK.start(context, dfClasses)

ScreenVisionSDK.observe().collect { results ->
    results.forEach {
        println("发现 ${it.elementId} 在 (${it.x}, ${it.y})")
    }
}

ScreenVisionSDK.tap(x, y)
ScreenVisionSDK.stop(context)
```

## 系统要求

- Android 10+ (API 28+), Root
- 推荐: 骁龙 8 Gen 1+ / 天玑 9000+ (GPU Delegate)
- 训练: macOS (Apple Silicon MPS) 或任意 N 卡

## 帧率预算

| 阶段 | 耗时 | 说明 |
|------|------|------|
| 截图 | ~8ms | SurfaceFlinger |
| 传输 | ~2ms | Unix Socket |
| 预处理 | ~2ms | 640×640 LetterBox |
| TFLite 推理 | ~15ms | GPU Delegate |
| NMS 后处理 | ~2ms | 坐标映射 |
| **合计** | **~29ms** | **30fps ✅** |