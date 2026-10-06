package com.smartball.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ThrowAnalyzerTest {

    @Test
    fun computesFlightTimeSpinAndBallisticMetrics() {
        val header =
            EventHeader(
                version = 1,
                flags = 0,
                sampleCount = 24,
                triggerIndex = 20,
                sampleRateHz = 833,
                accelRangeG = 16,
                gyroRangeDps = 2000
            )

        val samples =
            List(24) { index ->
                when {
                    index < 5 ->
                        sample(
                            index = index,
                            accelG = 1.0,
                            spinDps = 0.0
                        )

                    index < 20 ->
                        sample(
                            index = index,
                            accelG = 0.1,
                            spinDps = 600.0
                        )

                    else ->
                        sample(
                            index = index,
                            accelG = 8.0,
                            spinDps = 300.0
                        )
                }
            }

        val metrics =
            ThrowAnalyzer.analyze(
                samples = samples,
                header = header
            )

        assertEquals(
            5,
            metrics.flightStartIndex
        )

        assertNotNull(
            metrics.flightTimeSeconds
        )

        assertEquals(
            0.018,
            metrics.flightTimeSeconds!!,
            0.002
        )

        assertEquals(
            100.0,
            metrics.meanSpinRpm!!,
            1.0
        )

        assertTrue(
            metrics.spinStabilityPercent!! > 99.0
        )

        assertTrue(
            metrics.spinAxisStabilityPercent!! > 99.0
        )

        assertTrue(
            metrics.rotationsInFlight!! > 0.02
        )

        assertNotNull(
            metrics.ballisticHeightMeters
        )
    }

    @Test
    fun distanceAddsHorizontalSpeedInitialSpeedAndAngle() {
        val base =
            ThrowMetrics(
                flightStartIndex = 0,
                impactIndex = 100,
                flightTimeSeconds = 1.0,
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
                ballisticHeightMeters = 1.22583125,
                ballisticVerticalSpeedMps = 4.903325,
                averageHorizontalSpeedMps = null,
                ballisticInitialSpeedMps = null,
                ballisticLaunchAngleDeg = null,
                accelSaturated = false,
                gyroSaturated = false
            )

        val metrics =
            ThrowAnalyzer.withDistance(
                base,
                10.0
            )

        assertEquals(
            10.0,
            metrics.averageHorizontalSpeedMps!!,
            1e-9
        )

        assertEquals(
            11.14,
            metrics.ballisticInitialSpeedMps!!,
            0.02
        )

        assertEquals(
            26.1,
            metrics.ballisticLaunchAngleDeg!!,
            0.2
        )
    }

    @Test
    fun decodesSixAxisSamplesFromBinaryPayload() {
        val header =
            EventHeader(
                version = 1,
                flags = 0,
                sampleCount = 1,
                triggerIndex = 0,
                sampleRateHz = 833,
                accelRangeG = 16,
                gyroRangeDps = 2000
            )

        val payload =
            ByteBuffer
                .allocate(
                    ThrowAnalyzer.HEADER_SIZE +
                        ThrowAnalyzer.SAMPLE_SIZE
                )
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )
                .apply {
                    position(
                        ThrowAnalyzer.HEADER_SIZE
                    )

                    putInt(123_456)
                    putShort(100)
                    putShort((-200).toShort())
                    putShort(300)
                    putShort(400)
                    putShort((-500).toShort())
                    putShort(600)
                }
                .array()

        val sample =
            ThrowAnalyzer.decodeSamples(
                payload,
                header
            ).single()

        assertEquals(
            123_456L,
            sample.timestampUs
        )

        assertEquals(100, sample.axRaw)
        assertEquals(-200, sample.ayRaw)
        assertEquals(300, sample.azRaw)
        assertEquals(400, sample.gxRaw)
        assertEquals(-500, sample.gyRaw)
        assertEquals(600, sample.gzRaw)
    }

    private fun sample(
        index: Int,
        accelG: Double,
        spinDps: Double
    ): ImuSample {
        val accelRaw =
            (accelG / 0.000488)
                .toInt()

        val gyroRaw =
            (spinDps / 0.070)
                .toInt()

        return ImuSample(
            timestampUs =
                index * 1_200L,
            axRaw =
                accelRaw,
            ayRaw = 0,
            azRaw = 0,
            gxRaw =
                gyroRaw,
            gyRaw = 0,
            gzRaw = 0
        )
    }
}
