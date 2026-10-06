package com.smartball.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ImuSample(
    val timestampUs: Long,
    val axRaw: Int,
    val ayRaw: Int,
    val azRaw: Int,
    val gxRaw: Int,
    val gyRaw: Int,
    val gzRaw: Int
)

data class ThrowMetrics(
    val flightStartIndex: Int?,
    val impactIndex: Int,
    val flightTimeSeconds: Double?,
    val peakLaunchAccelerationG: Double?,
    val peakImpactAccelerationG: Double?,
    val peakLaunchForceNewton: Double?,
    val peakImpactForceNewton: Double?,
    val launchImpulseNewtonSecond: Double?,
    val meanSpinRpm: Double?,
    val peakSpinRpm: Double?,
    val rotationsInFlight: Double?,
    val spinStabilityPercent: Double?,
    val spinAxisStabilityPercent: Double?,
    val ballisticHeightMeters: Double?,
    val ballisticVerticalSpeedMps: Double?,
    val averageHorizontalSpeedMps: Double?,
    val ballisticInitialSpeedMps: Double?,
    val ballisticLaunchAngleDeg: Double?,
    val accelSaturated: Boolean,
    val gyroSaturated: Boolean
)

object ThrowAnalyzer {

    const val HEADER_SIZE = 16
    const val SAMPLE_SIZE = 16

    /**
     * Masse nominale de la TB100 seule (~38 g).
     * A remplacer par la masse réellement mesurée de la balle instrumentée
     * pour améliorer les estimations de force et d'impulsion.
     */
    const val DEFAULT_BALL_MASS_KG = 0.038

    private const val GRAVITY_MPS2 = 9.80665
    private const val FREE_FALL_THRESHOLD_G = 0.75
    private const val FLIGHT_MIN_CONSECUTIVE_SAMPLES = 8
    private const val LAUNCH_WINDOW_SECONDS = 0.35
    private const val IMPACT_WINDOW_SECONDS = 0.05
    private const val SATURATION_RAW_THRESHOLD = 32112 // ~98 % de 32767
    private const val MIN_SPIN_DPS_FOR_AXIS = 5.0

    fun decodeSamples(
        payload: ByteArray,
        header: EventHeader
    ): List<ImuSample> {
        val availableSamples =
            ((payload.size - HEADER_SIZE).coerceAtLeast(0) / SAMPLE_SIZE)
                .coerceAtMost(header.sampleCount)

        if (availableSamples <= 0) {
            return emptyList()
        }

        val buffer = ByteBuffer
            .wrap(payload, HEADER_SIZE, availableSamples * SAMPLE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        return List(availableSamples) {
            ImuSample(
                timestampUs = buffer.int.toLong() and 0xFFFF_FFFFL,
                axRaw = buffer.short.toInt(),
                ayRaw = buffer.short.toInt(),
                azRaw = buffer.short.toInt(),
                gxRaw = buffer.short.toInt(),
                gyRaw = buffer.short.toInt(),
                gzRaw = buffer.short.toInt()
            )
        }
    }

    fun analyze(
        samples: List<ImuSample>,
        header: EventHeader,
        distanceMeters: Double? = null,
        ballMassKg: Double = DEFAULT_BALL_MASS_KG
    ): ThrowMetrics {
        if (samples.isEmpty()) {
            return ThrowMetrics(
                flightStartIndex = null,
                impactIndex = 0,
                flightTimeSeconds = null,
                peakLaunchAccelerationG = null,
                peakImpactAccelerationG = null,
                peakLaunchForceNewton = null,
                peakImpactForceNewton = null,
                launchImpulseNewtonSecond = null,
                meanSpinRpm = null,
                peakSpinRpm = null,
                rotationsInFlight = null,
                spinStabilityPercent = null,
                spinAxisStabilityPercent = null,
                ballisticHeightMeters = null,
                ballisticVerticalSpeedMps = null,
                averageHorizontalSpeedMps = null,
                ballisticInitialSpeedMps = null,
                ballisticLaunchAngleDeg = null,
                accelSaturated = false,
                gyroSaturated = false
            )
        }

        val impactIndex = header.triggerIndex.coerceIn(0, samples.lastIndex)
        val accelScale = accelGPerLsb(header.accelRangeG)
        val gyroScale = gyroDpsPerLsb(header.gyroRangeDps)

        val accelSaturated = samples.any { sample ->
            listOf(sample.axRaw, sample.ayRaw, sample.azRaw)
                .any { kotlin.math.abs(it) >= SATURATION_RAW_THRESHOLD }
        }

        val gyroSaturated = samples.any { sample ->
            listOf(sample.gxRaw, sample.gyRaw, sample.gzRaw)
                .any { kotlin.math.abs(it) >= SATURATION_RAW_THRESHOLD }
        }

        val flightStartIndex =
            detectFlightStart(samples, impactIndex, accelScale)

        val impactWindowSamples =
            max(1, (header.sampleRateHz * IMPACT_WINDOW_SECONDS).toInt())

        val impactEnd =
            min(samples.lastIndex, impactIndex + impactWindowSamples)

        val peakImpactAccelerationG =
            (impactIndex..impactEnd)
                .maxOfOrNull { accelNormG(samples[it], accelScale) }

        val peakImpactForceNewton =
            peakImpactAccelerationG?.let {
                ballMassKg * it * GRAVITY_MPS2
            }

        if (flightStartIndex == null || flightStartIndex >= impactIndex) {
            return ThrowMetrics(
                flightStartIndex = null,
                impactIndex = impactIndex,
                flightTimeSeconds = null,
                peakLaunchAccelerationG = null,
                peakImpactAccelerationG = peakImpactAccelerationG,
                peakLaunchForceNewton = null,
                peakImpactForceNewton = peakImpactForceNewton,
                launchImpulseNewtonSecond = null,
                meanSpinRpm = null,
                peakSpinRpm = null,
                rotationsInFlight = null,
                spinStabilityPercent = null,
                spinAxisStabilityPercent = null,
                ballisticHeightMeters = null,
                ballisticVerticalSpeedMps = null,
                averageHorizontalSpeedMps = null,
                ballisticInitialSpeedMps = null,
                ballisticLaunchAngleDeg = null,
                accelSaturated = accelSaturated,
                gyroSaturated = gyroSaturated
            )
        }

        val flightTimeSeconds =
            elapsedSeconds(
                samples[flightStartIndex].timestampUs,
                samples[impactIndex].timestampUs
            )

        val launchWindowSamples =
            max(1, (header.sampleRateHz * LAUNCH_WINDOW_SECONDS).toInt())

        val launchStart =
            max(0, flightStartIndex - launchWindowSamples)

        val peakLaunchAccelerationG =
            (launchStart..flightStartIndex)
                .maxOfOrNull { accelNormG(samples[it], accelScale) }

        // Approximation : on retire seulement la norme statique de 1 g.
        // Sans estimation d'attitude complète, ce n'est pas une force nette 3D.
        val peakLaunchForceNewton =
            peakLaunchAccelerationG?.let {
                ballMassKg * max(it - 1.0, 0.0) * GRAVITY_MPS2
            }

        val launchImpulseNewtonSecond =
            integrateLaunchImpulse(
                samples = samples,
                startIndex = launchStart,
                endIndexExclusive = flightStartIndex,
                accelScaleGPerLsb = accelScale,
                ballMassKg = ballMassKg
            )

        val flightSamples =
            samples.subList(flightStartIndex, impactIndex)

        val spinDps =
            flightSamples.map { gyroNormDps(it, gyroScale) }

        val meanSpinDps =
            spinDps.takeIf { it.isNotEmpty() }?.average()

        val peakSpinDps =
            spinDps.maxOrNull()

        val meanSpinRpm =
            meanSpinDps?.div(6.0)

        val peakSpinRpm =
            peakSpinDps?.div(6.0)

        val rotationsInFlight =
            integrateRotations(
                samples = samples,
                startIndex = flightStartIndex,
                endIndexExclusive = impactIndex,
                gyroScaleDpsPerLsb = gyroScale
            )

        val spinStabilityPercent =
            meanSpinDps
                ?.takeIf { it > 1e-6 }
                ?.let { mean ->
                    val variance =
                        spinDps.sumOf { value ->
                            val delta = value - mean
                            delta * delta
                        } / spinDps.size

                    val coefficientOfVariation =
                        sqrt(variance) / mean

                    ((1.0 - coefficientOfVariation) * 100.0)
                        .coerceIn(0.0, 100.0)
                }

        val spinAxisStabilityPercent =
            computeSpinAxisStability(
                flightSamples,
                gyroScale
            )

        val ballisticVerticalSpeedMps =
            flightTimeSeconds
                .takeIf { it > 0.0 }
                ?.let { GRAVITY_MPS2 * it / 2.0 }

        val ballisticHeightMeters =
            flightTimeSeconds
                .takeIf { it > 0.0 }
                ?.let { GRAVITY_MPS2 * it * it / 8.0 }

        val baseMetrics =
            ThrowMetrics(
                flightStartIndex = flightStartIndex,
                impactIndex = impactIndex,
                flightTimeSeconds = flightTimeSeconds,
                peakLaunchAccelerationG = peakLaunchAccelerationG,
                peakImpactAccelerationG = peakImpactAccelerationG,
                peakLaunchForceNewton = peakLaunchForceNewton,
                peakImpactForceNewton = peakImpactForceNewton,
                launchImpulseNewtonSecond = launchImpulseNewtonSecond,
                meanSpinRpm = meanSpinRpm,
                peakSpinRpm = peakSpinRpm,
                rotationsInFlight = rotationsInFlight,
                spinStabilityPercent = spinStabilityPercent,
                spinAxisStabilityPercent = spinAxisStabilityPercent,
                ballisticHeightMeters = ballisticHeightMeters,
                ballisticVerticalSpeedMps = ballisticVerticalSpeedMps,
                averageHorizontalSpeedMps = null,
                ballisticInitialSpeedMps = null,
                ballisticLaunchAngleDeg = null,
                accelSaturated = accelSaturated,
                gyroSaturated = gyroSaturated
            )

        return withDistance(baseMetrics, distanceMeters)
    }

    fun withDistance(
        metrics: ThrowMetrics,
        distanceMeters: Double?
    ): ThrowMetrics {
        val time = metrics.flightTimeSeconds
        val verticalSpeed = metrics.ballisticVerticalSpeedMps

        if (
            distanceMeters == null ||
            distanceMeters <= 0.0 ||
            time == null ||
            time <= 0.0
        ) {
            return metrics.copy(
                averageHorizontalSpeedMps = null,
                ballisticInitialSpeedMps = null,
                ballisticLaunchAngleDeg = null
            )
        }

        val horizontalSpeed = distanceMeters / time

        if (verticalSpeed == null) {
            return metrics.copy(
                averageHorizontalSpeedMps = horizontalSpeed,
                ballisticInitialSpeedMps = null,
                ballisticLaunchAngleDeg = null
            )
        }

        val initialSpeed =
            sqrt(
                horizontalSpeed * horizontalSpeed +
                    verticalSpeed * verticalSpeed
            )

        val launchAngleDeg =
            Math.toDegrees(
                atan2(verticalSpeed, horizontalSpeed)
            )

        return metrics.copy(
            averageHorizontalSpeedMps = horizontalSpeed,
            ballisticInitialSpeedMps = initialSpeed,
            ballisticLaunchAngleDeg = launchAngleDeg
        )
    }

    private fun detectFlightStart(
        samples: List<ImuSample>,
        impactIndex: Int,
        accelScaleGPerLsb: Double
    ): Int? {
        var consecutive = 0

        for (index in 0 until impactIndex) {
            if (
                accelNormG(samples[index], accelScaleGPerLsb) <
                FREE_FALL_THRESHOLD_G
            ) {
                consecutive += 1

                if (consecutive >= FLIGHT_MIN_CONSECUTIVE_SAMPLES) {
                    return index - FLIGHT_MIN_CONSECUTIVE_SAMPLES + 1
                }
            } else {
                consecutive = 0
            }
        }

        return null
    }

    private fun integrateLaunchImpulse(
        samples: List<ImuSample>,
        startIndex: Int,
        endIndexExclusive: Int,
        accelScaleGPerLsb: Double,
        ballMassKg: Double
    ): Double? {
        if (endIndexExclusive - startIndex < 2) {
            return null
        }

        var impulse = 0.0

        for (index in startIndex until endIndexExclusive - 1) {
            val excessG =
                max(
                    accelNormG(samples[index], accelScaleGPerLsb) - 1.0,
                    0.0
                )

            val dt =
                elapsedSeconds(
                    samples[index].timestampUs,
                    samples[index + 1].timestampUs
                )

            impulse +=
                ballMassKg * excessG * GRAVITY_MPS2 * dt
        }

        return impulse
    }

    private fun integrateRotations(
        samples: List<ImuSample>,
        startIndex: Int,
        endIndexExclusive: Int,
        gyroScaleDpsPerLsb: Double
    ): Double? {
        if (endIndexExclusive - startIndex < 2) {
            return null
        }

        var rotations = 0.0

        for (index in startIndex until endIndexExclusive - 1) {
            val dps =
                gyroNormDps(samples[index], gyroScaleDpsPerLsb)

            val dt =
                elapsedSeconds(
                    samples[index].timestampUs,
                    samples[index + 1].timestampUs
                )

            rotations += dps * dt / 360.0
        }

        return rotations
    }

    private fun computeSpinAxisStability(
        samples: List<ImuSample>,
        gyroScaleDpsPerLsb: Double
    ): Double? {
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0

        for (sample in samples) {
            val gx = sample.gxRaw * gyroScaleDpsPerLsb
            val gy = sample.gyRaw * gyroScaleDpsPerLsb
            val gz = sample.gzRaw * gyroScaleDpsPerLsb

            val norm = sqrt(gx * gx + gy * gy + gz * gz)

            if (norm < MIN_SPIN_DPS_FOR_AXIS) {
                continue
            }

            sumX += gx / norm
            sumY += gy / norm
            sumZ += gz / norm
            count += 1
        }

        if (count == 0) {
            return null
        }

        val resultantLength =
            sqrt(
                sumX * sumX +
                    sumY * sumY +
                    sumZ * sumZ
            ) / count

        return (resultantLength * 100.0)
            .coerceIn(0.0, 100.0)
    }

    private fun accelNormG(
        sample: ImuSample,
        scaleGPerLsb: Double
    ): Double {
        val ax = sample.axRaw * scaleGPerLsb
        val ay = sample.ayRaw * scaleGPerLsb
        val az = sample.azRaw * scaleGPerLsb

        return sqrt(ax * ax + ay * ay + az * az)
    }

    private fun gyroNormDps(
        sample: ImuSample,
        scaleDpsPerLsb: Double
    ): Double {
        val gx = sample.gxRaw * scaleDpsPerLsb
        val gy = sample.gyRaw * scaleDpsPerLsb
        val gz = sample.gzRaw * scaleDpsPerLsb

        return sqrt(gx * gx + gy * gy + gz * gz)
    }

    private fun accelGPerLsb(rangeG: Int): Double =
        when (rangeG) {
            2 -> 0.000061
            4 -> 0.000122
            8 -> 0.000244
            16 -> 0.000488
            else -> rangeG.toDouble() / 32768.0
        }

    private fun gyroDpsPerLsb(rangeDps: Int): Double =
        when (rangeDps) {
            125 -> 0.004375
            245 -> 0.00875
            500 -> 0.0175
            1000 -> 0.035
            2000 -> 0.070
            else -> rangeDps.toDouble() / 32768.0
        }

    private fun elapsedSeconds(
        startTimestampUs: Long,
        endTimestampUs: Long
    ): Double {
        val elapsedUs =
            (endTimestampUs - startTimestampUs) and 0xFFFF_FFFFL

        return elapsedUs / 1_000_000.0
    }
}
