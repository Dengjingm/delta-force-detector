package com.screen.vision.api

import com.screen.vision.model.DetectResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 进程级检测结果总线。
 *
 * DetectionService 发布每一帧的检测结果（含空列表表示本帧无目标），
 * SDK 与自动瞄准等消费者订阅同一实例。用于替代此前未接通的
 * Service Binder/Flow 通路（B05）。
 */
object ResultBus {
    private val _results = MutableSharedFlow<List<DetectResult>>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val results: SharedFlow<List<DetectResult>> = _results

    fun publish(detections: List<DetectResult>) {
        _results.tryEmit(detections)
    }
}
