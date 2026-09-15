package com.screen.vision.center

/**
 * 屏幕中心移动参数。所有距离以屏幕像素为单位，可随控制方案调整。
 */
data class ScreenCenterConfig(
    /** 像素误差 → 注入滑动像素 的比例增益 */
    val sensitivity: Float = 1.0f,
    /** 死区半径（px），误差落在死区内时不注入 */
    val deadzonePx: Int = 5,
    /** 单次注入最大位移，防止过冲与抖动 */
    val maxStepPx: Int = 60,
    /** 水平反向（部分场景方向相反时启用） */
    val invertX: Boolean = false,
    /** 垂直反向 */
    val invertY: Boolean = false,
    /** 跨帧关联的最大中心距离（px），小于该值视为同一目标 */
    val associateDistancePx: Int = 60,
    /** 连续多少帧未匹配到锁定目标后释放并重新获取 */
    val releaseAfterMisses: Int = 5,
    /**
     * 同一人同时有小框（头）和大框（身体）时，获取锁定选小框的概率。
     * 只有大框则始终跟大框。跟踪阶段不再重抽，避免头/身体间跳动。
     */
    val preferSmallBoxProbability: Float = 0.8f,
    /** 总开关 */
    val enabled: Boolean = true,
)
