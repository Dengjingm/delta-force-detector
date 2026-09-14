package com.screen.vision.util

import java.util.concurrent.atomic.AtomicReference

/**
 * 容量为 1 的“最新帧”槽（CONTRACTS C01 内存模型）。
 *
 * 写入方 publish 永远成功，并返回被替换掉的旧值（可能为 null），
 * 由调用方负责回收旧值的资源；读取方 take 取走当前值并清空槽位。
 *
 * 基于 [AtomicReference] 的 CAS 实现，无锁、无阻塞，天然适合
 * 单生产者（reader）+ 单消费者（worker）的最新优先背压语义：
 * 生产快于消费时，旧帧被丢弃，消费者永远处理最新一帧。
 */
class LatestSlot<T> {

    private val ref = AtomicReference<T?>(null)

    /** 写入新值，返回被替换的旧值（无旧值则返回 null）。 */
    fun publish(value: T): T? = ref.getAndSet(value)

    /** 取走当前值并清空槽位，无值返回 null。 */
    fun take(): T? = ref.getAndSet(null)

    /** 清空并返回当前值（无值返回 null），用于关闭时回收残留。 */
    fun clear(): T? = ref.getAndSet(null)
}
