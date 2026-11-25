package space.owork.draw.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.owork.draw.engine.BrushSpec
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.ToolType
import kotlin.math.abs

class StrokePredictionTest {

    @Test
    fun fastStraightStrokePreservesMultiPointCurveAndPinsStyleToCommittedTip() {
        val stabilizer = PredictedTailStabilizer()
        val real = listOf(
            StrokePoint(0f, 0f, pressure = 0.8f, tilt = 0.3f, orientation = 1.1f, tMs = 0L),
            StrokePoint(12f, 0f, pressure = 0.8f, tilt = 0.3f, orientation = 1.1f, tMs = 4L),
            StrokePoint(24f, 0f, pressure = 0.8f, tilt = 0.3f, orientation = 1.1f, tMs = 8L),
            StrokePoint(36f, 0f, pressure = 0.8f, tilt = 0.3f, orientation = 1.1f, tMs = 12L),
        )
        real.forEach { stabilizer.recordRealSample(it) }

        val rawPredicted = listOf(
            StrokePoint(48f, 0.5f, pressure = 0.1f, tilt = 0f, orientation = 0f, tMs = 16L),
            StrokePoint(60f, 1.5f, pressure = 0.1f, tilt = 0f, orientation = 0f, tMs = 20L),
            StrokePoint(72f, 3.0f, pressure = 0.1f, tilt = 0f, orientation = 0f, tMs = 24L),
            StrokePoint(96f, 6.0f, pressure = 0.1f, tilt = 0f, orientation = 0f, tMs = 32L), // beyond 12ms horizon
        )

        val out = stabilizer.stabilize(
            committedPoints = real,
            rawLastPoint = real.last(),
            rawPredictedSamples = rawPredicted,
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )

        assertEquals(3, out.size)
        // Must clip at 12ms (t = 24 -> x = 72) and stay within MAX_PREDICT_DP * density (48px).
        assertTrue(out.last().x > real.last().x + 25f)
        assertTrue(out.last().x <= real.last().x + 48.1f)
        for (p in out) {
            assertEquals(0.8f, p.pressure, 1e-4f)
            assertEquals(0.3f, p.tilt, 1e-4f)
            assertEquals(1.1f, p.orientation, 1e-4f)
        }
    }

    @Test
    fun runawayAccelerationIsClampedToBoundedSpeedAndScreenLength() {
        val stabilizer = PredictedTailStabilizer()
        val real = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(10f, 0f, tMs = 4L),
            StrokePoint(20f, 0f, tMs = 8L),
            StrokePoint(30f, 0f, tMs = 12L),
        )
        real.forEach { stabilizer.recordRealSample(it) }

        // Predictor claims an absurd 150px jump in 8ms (7.5x real speed).
        val wildPredicted = listOf(
            StrokePoint(180f, 0f, tMs = 20L),
        )

        val out = stabilizer.stabilize(
            committedPoints = real,
            rawLastPoint = real.last(),
            rawPredictedSamples = wildPredicted,
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )

        assertTrue(out.isNotEmpty())
        val dx = out.last().x - real.last().x
        // Recent speed is 2.5 px/ms; max allowed in 8ms is 2.5 * 1.15 * 8 = 23 px (well below 150px).
        assertTrue("Expected clamped dx <= 24px, got $dx", dx in 18f..24f)
    }

    @Test
    fun sharpTurnDropsPredictionImmediatelyWithoutLingeringCornerTail() {
        val stabilizer = PredictedTailStabilizer()
        val straight = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(12f, 0f, tMs = 4L),
            StrokePoint(24f, 0f, tMs = 8L),
            StrokePoint(36f, 0f, tMs = 12L),
        )
        straight.forEach { stabilizer.recordRealSample(it) }

        val firstOut = stabilizer.stabilize(
            committedPoints = straight,
            rawLastPoint = straight.last(),
            rawPredictedSamples = listOf(StrokePoint(60f, 0f, tMs = 20L)),
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )
        assertTrue(firstOut.isNotEmpty())

        // Now pen whips 90 degrees downward.
        val turned = straight + StrokePoint(36f, 14f, tMs = 16L)
        stabilizer.recordRealSample(turned.last())

        val afterTurn = stabilizer.stabilize(
            committedPoints = turned,
            rawLastPoint = turned.last(),
            rawPredictedSamples = listOf(StrokePoint(56f, 14f, tMs = 24L)),
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )

        assertTrue("Expected empty tail on 90-deg turn, got $afterTurn", afterTurn.isEmpty())
    }

    @Test
    fun noisyFrameToFramePredictionJitterIsTemporallyDampedAndNeverBacktracksDuringCruise() {
        val stabilizer = PredictedTailStabilizer()
        val points = mutableListOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(12f, 0f, tMs = 4L),
            StrokePoint(24f, 0f, tMs = 8L),
            StrokePoint(36f, 0f, tMs = 12L),
        )
        points.forEach { stabilizer.recordRealSample(it) }

        val frame1 = stabilizer.stabilize(
            committedPoints = points,
            rawLastPoint = points.last(),
            rawPredictedSamples = listOf(StrokePoint(60f, 0f, tMs = 20L)), // world tip = 60
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )
        val tip1WorldX = frame1.last().x

        // Next real sample cruises at exact same speed (x = 48), but raw predictor dips to world x = 54 (+6px) & y = 3.
        val nextReal = StrokePoint(48f, 0f, tMs = 16L)
        points.add(nextReal)
        stabilizer.recordRealSample(nextReal)

        val frame2 = stabilizer.stabilize(
            committedPoints = points,
            rawLastPoint = nextReal,
            rawPredictedSamples = listOf(StrokePoint(54f, 3f, tMs = 20L)),
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )
        val tip2WorldX = frame2.last().x

        // World tip should stay forward of previous tip (no accordion snap backwards) and damp sideways jitter.
        assertTrue("Expected tip2WorldX >= tip1WorldX - 1f, got $tip2WorldX vs $tip1WorldX", tip2WorldX >= tip1WorldX - 1f)
        assertTrue("Expected damped lateral shift < 2px, got ${abs(frame2.last().y)}", abs(frame2.last().y) < 2f)
    }

    @Test
    fun singlePointCurvedPredictionSynthesizesSmoothC1Arc() {
        val stabilizer = PredictedTailStabilizer()
        val real = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(12f, 0f, tMs = 4L),
            StrokePoint(24f, 0f, tMs = 8L),
            StrokePoint(36f, 0f, tMs = 12L),
        )
        real.forEach { stabilizer.recordRealSample(it) }

        // Single predicted sample gently curving downward (historySize == 0 case on OEM MotionPredictor).
        val out = stabilizer.stabilize(
            committedPoints = real,
            rawLastPoint = real.last(),
            rawPredictedSamples = listOf(StrokePoint(60f, 6f, tMs = 20L)),
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )

        assertEquals(3, out.size)
        // First synthesized segment should hug the horizontal outgoing tangent closer than a straight chord.
        val chordSlope = (out.last().y - real.last().y) / (out.last().x - real.last().x)
        val initialSlope = (out.first().y - real.last().y) / (out.first().x - real.last().x)
        assertTrue("Expected C1 initial slope < chord slope ($initialSlope vs $chordSlope)", initialSlope < chordSlope)
    }

    @Test
    fun smoothingLagDoesNotStretchPredictedSegment() {
        val stabilizer = PredictedTailStabilizer()
        val raw = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(16f, 0f, tMs = 4L),
            StrokePoint(32f, 0f, tMs = 8L),
            StrokePoint(48f, 0f, tMs = 12L),
        )
        raw.forEach { stabilizer.recordRealSample(it) }

        // Committed points lag behind raw because of inputBlend smoothing.
        val smoothedCommitted = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(9f, 0f, tMs = 4L),
            StrokePoint(19f, 0f, tMs = 8L),
            StrokePoint(30f, 0f, tMs = 12L),
        )

        val out = stabilizer.stabilize(
            committedPoints = smoothedCommitted,
            rawLastPoint = raw.last(),
            rawPredictedSamples = listOf(StrokePoint(72f, 0f, tMs = 18L)), // +24px ahead of raw (42px ahead of smoothed!)
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0.42f,
        )

        assertTrue(out.isNotEmpty())
        val visualTailLen = out.last().x - smoothedCommitted.last().x
        // Anchored to smoothedCommitted and scaled down for smoothingBlend=0.42 -> well under raw gap (42px).
        assertTrue("Expected short anchored tail <= 15px, got $visualTailLen", visualTailLen in 4f..15f)
    }

    @Test
    fun slowPrecisionDrawingSuppressesPrediction() {
        val stabilizer = PredictedTailStabilizer()
        val slow = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(1f, 0f, tMs = 16L),
            StrokePoint(2f, 0f, tMs = 32L),
            StrokePoint(3f, 0f, tMs = 48L),
        )
        slow.forEach { stabilizer.recordRealSample(it) }

        val out = stabilizer.stabilize(
            committedPoints = slow,
            rawLastPoint = slow.last(),
            rawPredictedSamples = listOf(StrokePoint(8f, 0f, tMs = 60L)),
            maxHorizonMs = 12f,
            zoom = 1f,
            density = 2f,
            smoothingBlend = 0f,
        )

        assertTrue(out.isEmpty())
    }

    @Test
    fun incrementalLiveMeshHandlesMultiPointPredictedTailAndStopsOnReversal() {
        val mesh = IncrementalLiveMesh()
        val brush = BrushSpec.defaultFor(ToolType.PEN)
        val real = listOf(
            StrokePoint(0f, 0f, tMs = 0L),
            StrokePoint(10f, 0f, tMs = 4L),
            StrokePoint(20f, 0f, tMs = 8L),
        )
        val predictedWithReversal = listOf(
            StrokePoint(30f, 0f, tMs = 12L),
            StrokePoint(40f, 1f, tMs = 16L),
            StrokePoint(25f, 1f, tMs = 20L), // sudden backward reversal; must be dropped
        )

        mesh.update(brush, real, predictedWithReversal, zoom = 1f)

        var emittedFloats = 0
        var maxX = Float.NEGATIVE_INFINITY
        var allFinite = true
        mesh.readForGpu { _, _, data, totalFloats, _ ->
            emittedFloats = totalFloats
            var i = 0
            while (i < totalFloats) {
                if (!data[i].isFinite()) allFinite = false
                if (i % StrokeMeshBuilder.FLOATS_PER_VERTEX == 0) {
                    maxX = maxOf(maxX, data[i])
                }
                i++
            }
        }

        assertTrue(allFinite)
        assertTrue(emittedFloats > 0)
        assertEquals(40f, maxX, 0.01f)
        // Last vertex pair (cap) should stay at (40, 1), NOT reversed back to x = 25.
        var capX = 0f
        mesh.readForGpu { _, _, data, totalFloats, _ ->
            capX = data[totalFloats - StrokeMeshBuilder.FLOATS_PER_VERTEX]
        }
        assertEquals(40f, capX, 0.01f)
    }
}
