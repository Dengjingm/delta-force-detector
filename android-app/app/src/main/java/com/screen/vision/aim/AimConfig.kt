package com.screen.vision.aim

/**
 * 自动瞄准参数。所有距离以屏幕像素为单位，可随控制方案调整。
 */
data class AimConfig(
    /** 像素误差 → 注入滑动像素 的比例增益 */
    val sensitivity: Float = 1.0f,
    /** 死区半径（px），误差落在死区内时不注入 */
    val deadzonePx: Int = 5,
    /** 单次注入最大位移，防止过冲与抖动 */
    val maxStepPx: Int = 60,
    /** 水平反向（部分游戏方向相反时启用） */
    val invertX: Boolean = false,
    /** 垂直反向 */
    val invertY: Boolean = false,
    /** 注入节拍（ms），约 60Hz */
    val aimIntervalMs: Long = 16L,
    /** 每次 input swipe 的滑动时长（ms） */
    val swipeDurationMs: Long = 16L,
    /** 总开关 */
    val enabled: Boolean = true,
)
