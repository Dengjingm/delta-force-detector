package com.screen.vision.center

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicReference

/**
 * 运行时瞄准参数 store: 进程级共享的 [AtomicReference] + SharedPreferences 持久化。
 *
 * 校准页调用 [update] 持久化并立即作用于已 [attach] 的运行中控制器,
 * 控制器每帧从自身 @Volatile config 读取一次, 实现无锁热更新。
 */
class AimConfigStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val current = AtomicReference(ScreenCenterConfig())

    @Volatile
    private var controller: ScreenCenterController? = null

    init { load() }

    fun get(): ScreenCenterConfig = current.get()

    fun update(newConfig: ScreenCenterConfig) {
        current.set(newConfig)
        controller?.updateConfig(newConfig)
        save(newConfig)
    }

    fun attach(controller: ScreenCenterController?) {
        this.controller = controller
    }

    fun load(): ScreenCenterConfig {
        val defaults = ScreenCenterConfig()
        val c = ScreenCenterConfig(
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity),
            deadzonePx = prefs.getInt(KEY_DEADZONE, defaults.deadzonePx),
            maxStepPx = prefs.getInt(KEY_MAX_STEP, defaults.maxStepPx),
            invertX = prefs.getBoolean(KEY_INVERT_X, defaults.invertX),
            invertY = prefs.getBoolean(KEY_INVERT_Y, defaults.invertY),
            associateDistancePx = prefs.getInt(KEY_ASSOCIATE, defaults.associateDistancePx),
            releaseAfterMisses = prefs.getInt(KEY_RELEASE_MISSES, defaults.releaseAfterMisses),
            preferSmallBoxProbability = prefs.getFloat(
                KEY_PREFER_SMALL,
                defaults.preferSmallBoxProbability,
            ),
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
        )
        current.set(c)
        return c
    }

    private fun save(c: ScreenCenterConfig) {
        prefs.edit()
            .putFloat(KEY_SENSITIVITY, c.sensitivity)
            .putInt(KEY_DEADZONE, c.deadzonePx)
            .putInt(KEY_MAX_STEP, c.maxStepPx)
            .putBoolean(KEY_INVERT_X, c.invertX)
            .putBoolean(KEY_INVERT_Y, c.invertY)
            .putInt(KEY_ASSOCIATE, c.associateDistancePx)
            .putInt(KEY_RELEASE_MISSES, c.releaseAfterMisses)
            .putFloat(KEY_PREFER_SMALL, c.preferSmallBoxProbability)
            .putBoolean(KEY_ENABLED, c.enabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "aim_config"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_DEADZONE = "deadzone_px"
        private const val KEY_MAX_STEP = "max_step_px"
        private const val KEY_INVERT_X = "invert_x"
        private const val KEY_INVERT_Y = "invert_y"
        private const val KEY_ASSOCIATE = "associate_distance_px"
        private const val KEY_RELEASE_MISSES = "release_after_misses"
        private const val KEY_PREFER_SMALL = "prefer_small_box_probability"
        private const val KEY_ENABLED = "enabled"

        @Volatile
        private var instance: AimConfigStore? = null

        fun get(context: Context): AimConfigStore =
            instance ?: synchronized(this) {
                instance ?: AimConfigStore(context.applicationContext).also { instance = it }
            }
    }
}
