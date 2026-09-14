package com.screen.vision.simulation

import java.util.Random
import kotlin.math.ln
import kotlin.math.sqrt

internal class DeterministicRandom(seed: Long) {
    private val random = Random(seed)
    private var spareGaussian: Double? = null

    fun uniform(minimum: Double, maximum: Double): Double {
        require(maximum >= minimum)
        return minimum + random.nextDouble() * (maximum - minimum)
    }

    fun sign(): Double = if (random.nextBoolean()) 1.0 else -1.0

    fun gaussian(): Double {
        spareGaussian?.let {
            spareGaussian = null
            return it
        }
        var u: Double
        var v: Double
        var radiusSquared: Double
        do {
            u = random.nextDouble() * 2.0 - 1.0
            v = random.nextDouble() * 2.0 - 1.0
            radiusSquared = u * u + v * v
        } while (radiusSquared <= 0.0 || radiusSquared >= 1.0)
        val multiplier = sqrt(-2.0 * ln(radiusSquared) / radiusSquared)
        spareGaussian = v * multiplier
        return u * multiplier
    }
}
