package com.screen.vision.simulation

data class SimulationRequest(
    val yawDeltaRad: Double,
    val pitchDeltaRad: Double,
    val touchStartPx: Point2,
    val horizontalPixelsPerRad: Double,
    val verticalPixelsPerRad: Double,
    val requestedDurationNs: Long,
    val startTimeNs: Long = 0L,
    val seed: Long = 1L,
)

data class DualModalSimulationTrace(
    val plan: DualModalPlan,
    val touchSamples: List<SimulatedTouchSample>,
    val imuSamples: List<SimulatedImuSample>,
)

/** Coordinates both in-memory channels for an application-owned test scenario. */
class InteractiveSimulationEngine(
    private val coordinator: SpatiotemporalConsistencyCoordinator = SpatiotemporalConsistencyCoordinator(),
    private val imuSynthesizer: ImuPhysicsSynthesizer = ImuPhysicsSynthesizer(),
    private val touchSynthesizer: TouchPathSynthesizer = TouchPathSynthesizer(),
) {
    fun synthesize(request: SimulationRequest): DualModalSimulationTrace {
        require(request.horizontalPixelsPerRad.isFinite() && request.horizontalPixelsPerRad > 0.0)
        require(request.verticalPixelsPerRad.isFinite() && request.verticalPixelsPerRad > 0.0)
        require(request.requestedDurationNs > 0L)
        val plan = coordinator.plan(request.yawDeltaRad, request.pitchDeltaRad)
        val touchEnd = Point2(
            request.touchStartPx.x + plan.touchAngularDelta.x * request.horizontalPixelsPerRad,
            request.touchStartPx.y + plan.touchAngularDelta.y * request.verticalPixelsPerRad,
        )
        val touchSamples = if (plan.inDeadzone ||
            (plan.touchAngularDelta.x == 0.0 && plan.touchAngularDelta.y == 0.0)
        ) {
            emptyList()
        } else {
            touchSynthesizer.synthesize(
                request.touchStartPx,
                touchEnd,
                request.requestedDurationNs,
                request.startTimeNs,
                request.seed,
            )
        }
        val imuSamples = if (plan.inDeadzone) {
            emptyList()
        } else {
            imuSynthesizer.synthesize(
                angularDisplacementRad = Vector3(
                    x = plan.gyroAngularDelta.y,
                    y = plan.gyroAngularDelta.x,
                    z = 0.0,
                ),
                requestedDurationNs = request.requestedDurationNs,
                startTimeNs = request.startTimeNs,
                seed = request.seed xor IMU_SEED_MASK,
            )
        }
        return DualModalSimulationTrace(plan, touchSamples, imuSamples)
    }

    fun reset() = coordinator.reset()

    companion object {
        private const val IMU_SEED_MASK = 0x5DEECE66DL
    }
}
