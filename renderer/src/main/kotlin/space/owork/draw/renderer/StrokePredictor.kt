package space.owork.draw.renderer

import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.MotionPredictor
import android.view.View
import androidx.annotation.RequiresApi
import androidx.input.motionprediction.MotionEventPredictor
import space.owork.draw.engine.StrokePoint
import space.owork.draw.engine.ViewportCamera
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Low-jitter stylus motion predictor for front-buffered live ink.
 *
 * On API 34+, uses [android.view.MotionPredictor] directly when available so we pass a clean,
 * bounded prediction timestamp instead of double-counting event delivery age (which causes
 * frame-to-frame horizon oscillation in `androidx.input.motionprediction` 1.0.1's system wrapper).
 * Falls back to Jetpack [MotionEventPredictor] on API 31-33 or unsupported input sources, and
 * feeds the full predicted polyline through [PredictedTailStabilizer].
 */
internal class StrokePredictor(
    private val hostView: View,
) {
    private val fallbackPredictor: MotionEventPredictor = MotionEventPredictor.newInstance(hostView)
    private val systemPredictor: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34MotionPredictor.create(hostView)
        } else {
            null
        }

    private var lastSource: Int = -1
    private var lastDeviceId: Int = -2
    private var useSystemPredictor: Boolean = false
    private var smoothedEventAgeMs: Float = 2f

    private val stabilizer = PredictedTailStabilizer()
    private val rawPredictedScratch = ArrayList<StrokePoint>(8)

    fun reset() {
        stabilizer.reset()
        smoothedEventAgeMs = 2f
    }

    fun recordMotionEvent(event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && systemPredictor != null) {
            val source = event.source
            val deviceId = event.deviceId
            if (source != lastSource || deviceId != lastDeviceId) {
                useSystemPredictor = Api34MotionPredictor.isPredictionAvailable(systemPredictor, deviceId, source)
                lastSource = source
                lastDeviceId = deviceId
            }
            if (useSystemPredictor) {
                Api34MotionPredictor.record(systemPredictor, event)
                return
            }
        }
        fallbackPredictor.record(event)
    }

    fun recordRealSample(rawWorldPoint: StrokePoint) {
        stabilizer.recordRealSample(rawWorldPoint)
    }

    fun predictTail(
        event: MotionEvent,
        pointerIndex: Int,
        camera: ViewportCamera,
        committedPoints: List<StrokePoint>,
        rawLastPoint: StrokePoint,
        smoothingBlend: Float,
    ): List<StrokePoint> {
        if (committedPoints.size < 3) return emptyList()

        val frameTimeMs = currentFrameTimeMs()
        val rawAgeMs = (SystemClock.uptimeMillis() - event.eventTime).coerceIn(0L, MAX_EVENT_AGE_MS).toFloat()
        smoothedEventAgeMs = smoothedEventAgeMs * 0.7f + rawAgeMs * 0.3f

        val maxHorizonMs = min(MAX_PREDICT_HORIZON_MS, frameTimeMs * 1.15f)
        val targetHorizonMs = (smoothedEventAgeMs * 0.6f + frameTimeMs * 0.75f).coerceIn(MIN_PREDICT_HORIZON_MS, maxHorizonMs)
        val targetTimeMs = event.eventTime + targetHorizonMs.toLong()

        val predicted: MotionEvent = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            systemPredictor != null &&
            useSystemPredictor
        ) {
            Api34MotionPredictor.predict(systemPredictor, TimeUnit.MILLISECONDS.toNanos(targetTimeMs))
        } else {
            fallbackPredictor.predict()
        } ?: return stabilizer.stabilize(
            committedPoints = committedPoints,
            rawLastPoint = rawLastPoint,
            rawPredictedSamples = emptyList(),
            maxHorizonMs = targetHorizonMs,
            zoom = camera.zoom,
            density = hostView.resources.displayMetrics.density,
            smoothingBlend = smoothingBlend,
        )

        return try {
            val pointerId = event.getPointerId(pointerIndex)
            val pIdx = predicted.findPointerIndex(pointerId)
            if (pIdx < 0) return emptyList()

            val lastCommitted = committedPoints.last()
            rawPredictedScratch.clear()
            val historyCount = predicted.historySize
            for (h in 0 until historyCount) {
                val world = camera.screenToWorld(
                    predicted.getHistoricalX(pIdx, h),
                    predicted.getHistoricalY(pIdx, h),
                )
                rawPredictedScratch.add(
                    StrokePoint(
                        x = world.x,
                        y = world.y,
                        pressure = lastCommitted.pressure,
                        tilt = lastCommitted.tilt,
                        orientation = lastCommitted.orientation,
                        tMs = predicted.getHistoricalEventTime(h),
                    ),
                )
            }
            val finalWorld = camera.screenToWorld(predicted.getX(pIdx), predicted.getY(pIdx))
            rawPredictedScratch.add(
                StrokePoint(
                    x = finalWorld.x,
                    y = finalWorld.y,
                    pressure = lastCommitted.pressure,
                    tilt = lastCommitted.tilt,
                    orientation = lastCommitted.orientation,
                    tMs = predicted.eventTime,
                ),
            )

            stabilizer.stabilize(
                committedPoints = committedPoints,
                rawLastPoint = rawLastPoint,
                rawPredictedSamples = rawPredictedScratch,
                maxHorizonMs = targetHorizonMs,
                zoom = camera.zoom,
                density = hostView.resources.displayMetrics.density,
                smoothingBlend = smoothingBlend,
            )
        } finally {
            predicted.recycle()
        }
    }

    private fun currentFrameTimeMs(): Float {
        val hz = hostView.display?.refreshRate ?: 90f
        if (!hz.isFinite() || hz < 30f) return 11.1f
        return (1000f / hz).coerceIn(4f, 20f)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34MotionPredictor {
        fun create(view: View): MotionPredictor = MotionPredictor(view.context)

        fun isPredictionAvailable(predictor: Any, deviceId: Int, source: Int): Boolean =
            (predictor as MotionPredictor).isPredictionAvailable(deviceId, source)

        fun record(predictor: Any, event: MotionEvent) {
            (predictor as MotionPredictor).record(event)
        }

        fun predict(predictor: Any, predictionTimeNanos: Long): MotionEvent? =
            (predictor as MotionPredictor).predict(predictionTimeNanos)
    }

    private companion object {
        const val MIN_PREDICT_HORIZON_MS = 5f
        const val MAX_PREDICT_HORIZON_MS = 14f
        const val MAX_EVENT_AGE_MS = 8L
    }
}

/**
 * Pure geometric & kinematic filter that turns noisy raw predictor samples into a steady,
 * bounded predicted stroke tail anchored smoothly at the active stroke's tip.
 *
 * Incorporates techniques from digital-ink prediction literature:
 * 1. Multi-point horizon clipping + speed-runaway & angular-snap clamping.
 * 2. Kinematic confidence tapering for slow drawing, braking, tight curves, and strong smoothing.
 * 3. Sample-rate-independent 1€ (One Euro) adaptive low-pass filtering (Casiez et al., CHI 2012)
 *    on the predicted tip displacement to suppress jitter without sluggishness across 60-240 Hz.
 * 4. Anti-backtrack guard during steady fast cruises (inspired by Nancel et al., UIST 2018) to
 *    prevent accordion-like tip pullbacks while still collapsing immediately on turns or stops.
 * 5. C1-continuous quadratic Bézier synthesis (inspired by Google `ink-stroke-modeler`) when the
 *    OS predictor only emits a single point, avoiding tangent kinks at the committed stroke tip.
 */
internal class PredictedTailStabilizer {
    private val recentRaw = ArrayDeque<StrokePoint>(RAW_HISTORY_CAPACITY + 1)

    // 1€ filter state on the predicted tip offset vector
    private var hasPrevTipOffset = false
    private var prevTipOffsetX = 0f
    private var prevTipOffsetY = 0f
    private var filteredDerivX = 0f
    private var filteredDerivY = 0f
    private var lastFilterTimeMs = 0L

    // Last rendered world-space predicted tip for steady-cruise anti-backtrack damping
    private var hasLastRenderedTip = false
    private var lastRenderedTipX = 0f
    private var lastRenderedTipY = 0f

    fun reset() {
        recentRaw.clear()
        clearFilterState()
    }

    fun recordRealSample(point: StrokePoint) {
        val last = recentRaw.lastOrNull()
        if (last != null) {
            if (point.tMs < last.tMs) return
            if (point.tMs == last.tMs && hypot(point.x - last.x, point.y - last.y) < 1e-3f) return
        }
        recentRaw.addLast(point)
        while (recentRaw.size > RAW_HISTORY_CAPACITY) {
            recentRaw.removeFirst()
        }
    }

    fun stabilize(
        committedPoints: List<StrokePoint>,
        rawLastPoint: StrokePoint,
        rawPredictedSamples: List<StrokePoint>,
        maxHorizonMs: Float,
        zoom: Float,
        density: Float,
        smoothingBlend: Float,
    ): List<StrokePoint> {
        if (committedPoints.size < 3 || rawPredictedSamples.isEmpty()) {
            clearFilterState()
            return emptyList()
        }

        val safeZoom = zoom.coerceAtLeast(0.05f)
        val safeDensity = density.coerceIn(0.75f, 6f)
        val lastCommitted = committedPoints.last()

        val kinematics = estimateRecentKinematics(rawLastPoint, safeZoom, safeDensity)
        if (kinematics == null) {
            clearFilterState()
            return emptyList()
        }

        // 1. Compute confidence [0..1] from speed, turn rate, braking, and user smoothing level.
        val speedConf = smoothRamp(kinematics.speedPxPerMs, MIN_SPEED_PX_PER_MS, FULL_SPEED_PX_PER_MS)
        val turnConf = 1f - smoothRamp(kinematics.turnAngleRad, TURN_FULL_CONF_RAD, TURN_ZERO_CONF_RAD)
        val decelConf = if (kinematics.prevSpeedWorldPerMs > 1e-4f) {
            val ratio = kinematics.speedWorldPerMs / kinematics.prevSpeedWorldPerMs
            smoothRamp(ratio, DECEL_ZERO_RATIO, DECEL_FULL_RATIO)
        } else {
            1f
        }
        val smoothingConf = (1f - smoothingBlend.coerceIn(0f, 1f) * 1.15f).coerceIn(0.2f, 1f)

        val baseConfidence = speedConf * turnConf * decelConf * smoothingConf
        if (baseConfidence <= 0.04f) {
            return decayOrClear(lastCommitted, kinematics.dirX, kinematics.dirY, safeZoom, safeDensity)
        }

        // 2. Clip & sanitize the predicted polyline relative to rawLastPoint.
        val maxTimeMs = rawLastPoint.tMs + maxHorizonMs.coerceAtLeast(1f)
        val maxArcWorld = (MAX_PREDICT_DP * safeDensity) / safeZoom

        val relXs = FloatArray(rawPredictedSamples.size + 1)
        val relYs = FloatArray(rawPredictedSamples.size + 1)
        val arcAt = FloatArray(rawPredictedSamples.size + 1)
        var count = 0

        var prevRawX = rawLastPoint.x
        var prevRawY = rawLastPoint.y
        var prevRawT = rawLastPoint.tMs.toFloat()

        var outRelX = 0f
        var outRelY = 0f
        var accArc = 0f

        var prevStepDirX = kinematics.dirX
        var prevStepDirY = kinematics.dirY
        val maxAllowedSpeedWorldPerMs = maxOf(
            kinematics.speedWorldPerMs * MAX_SPEED_MULTIPLIER,
            kinematics.prevSpeedWorldPerMs * 0.9f,
        )

        for (sample in rawPredictedSamples) {
            val sampleT = sample.tMs.toFloat()
            if (sampleT <= prevRawT) continue

            var targetX = sample.x
            var targetY = sample.y
            var targetT = sampleT
            var hitHorizon = false
            if (sampleT > maxTimeMs) {
                val frac = ((maxTimeMs - prevRawT) / (sampleT - prevRawT)).coerceIn(0f, 1f)
                targetX = prevRawX + (sample.x - prevRawX) * frac
                targetY = prevRawY + (sample.y - prevRawY) * frac
                targetT = maxTimeMs
                hitHorizon = true
            }

            val dx = targetX - prevRawX
            val dy = targetY - prevRawY
            val rawDist = hypot(dx, dy)
            val dt = (targetT - prevRawT).coerceAtLeast(1f)

            prevRawX = targetX
            prevRawY = targetY
            prevRawT = targetT

            if (rawDist <= 1e-4f) {
                if (hitHorizon) break else continue
            }

            val dirX = dx / rawDist
            val dirY = dy / rawDist

            // Reject sudden angular spikes against both previous predicted step and recent real tangent.
            val dotStep = dirX * prevStepDirX + dirY * prevStepDirY
            val dotReal = dirX * kinematics.dirX + dirY * kinematics.dirY
            if (dotStep < MIN_STEP_ALIGNMENT_COS || dotReal < MIN_REAL_ALIGNMENT_COS) {
                break
            }

            // Clamp acceleration runaway to a bounded multiple of recent real speed.
            val clampedDist = min(rawDist, maxAllowedSpeedWorldPerMs * dt)
            val remainingArc = maxArcWorld - accArc
            if (remainingArc <= 1e-4f) break

            val stepDist = min(clampedDist, remainingArc)
            outRelX += dirX * stepDist
            outRelY += dirY * stepDist
            accArc += stepDist

            relXs[count] = outRelX
            relYs[count] = outRelY
            arcAt[count] = accArc
            count++

            prevStepDirX = dirX
            prevStepDirY = dirY

            if (hitHorizon || stepDist < clampedDist) break
        }

        if (count == 0 || accArc <= 1e-4f) {
            return decayOrClear(lastCommitted, kinematics.dirX, kinematics.dirY, safeZoom, safeDensity)
        }

        // 3. Apply overall lateral deviation penalty & confidence scaling.
        val candTipX = relXs[count - 1]
        val candTipY = relYs[count - 1]
        val candLen = hypot(candTipX, candTipY)
        if (candLen <= 1e-4f) {
            clearFilterState()
            return emptyList()
        }
        val tipDotReal = (candTipX * kinematics.dirX + candTipY * kinematics.dirY) / candLen
        val tipAngleRad = acos(tipDotReal.coerceIn(-1f, 1f))
        val alignConf = 1f - smoothRamp(tipAngleRad, TIP_ALIGN_FULL_RAD, TIP_ALIGN_ZERO_RAD)

        val finalScale = (baseConfidence * alignConf).coerceIn(0f, 1f)
        if (finalScale <= 0.04f) {
            return decayOrClear(lastCommitted, kinematics.dirX, kinematics.dirY, safeZoom, safeDensity)
        }

        val scaledTipX = candTipX * finalScale
        val scaledTipY = candTipY * finalScale

        // 4. Continuous-time 1€ adaptive low-pass filter on the tip displacement vector.
        var filteredTipX: Float
        var filteredTipY: Float
        if (hasPrevTipOffset) {
            val prevLen = hypot(prevTipOffsetX, prevTipOffsetY)
            val scaledLen = hypot(scaledTipX, scaledTipY)
            val consistent = prevLen > 1e-4f && scaledLen > 1e-4f &&
                ((prevTipOffsetX * scaledTipX + prevTipOffsetY * scaledTipY) / (prevLen * scaledLen)) >= MIN_TEMPORAL_COS &&
                ((prevTipOffsetX * kinematics.dirX + prevTipOffsetY * kinematics.dirY) / prevLen) >= MIN_TEMPORAL_COS
            if (consistent) {
                val dtSec = (rawLastPoint.tMs - lastFilterTimeMs).coerceIn(1L, 50L) / 1000f
                val worldToDp = safeZoom / safeDensity
                val dOx = (scaledTipX - prevTipOffsetX) * worldToDp / dtSec
                val dOy = (scaledTipY - prevTipOffsetY) * worldToDp / dtSec
                val alphaD = oneEuroAlpha(dtSec, ONE_EURO_DERIV_CUTOFF_HZ)
                filteredDerivX += alphaD * (dOx - filteredDerivX)
                filteredDerivY += alphaD * (dOy - filteredDerivY)
                val derivMagDpPerSec = hypot(filteredDerivX, filteredDerivY)
                val cutoffHz = ONE_EURO_MIN_CUTOFF_HZ + ONE_EURO_BETA * derivMagDpPerSec
                val alpha = oneEuroAlpha(dtSec, cutoffHz)
                filteredTipX = prevTipOffsetX + alpha * (scaledTipX - prevTipOffsetX)
                filteredTipY = prevTipOffsetY + alpha * (scaledTipY - prevTipOffsetY)
            } else {
                filteredTipX = scaledTipX
                filteredTipY = scaledTipY
                filteredDerivX = 0f
                filteredDerivY = 0f
            }
        } else {
            filteredTipX = scaledTipX
            filteredTipY = scaledTipY
            filteredDerivX = 0f
            filteredDerivY = 0f
        }

        // 5. Anti-backtrack guard during steady fast cruises (never active while turning or braking).
        if (hasLastRenderedTip && decelConf > ANTI_BACKTRACK_MIN_CONF && turnConf > ANTI_BACKTRACK_MIN_CONF) {
            val prevRemForward = (lastRenderedTipX - lastCommitted.x) * kinematics.dirX +
                (lastRenderedTipY - lastCommitted.y) * kinematics.dirY
            val newForward = filteredTipX * kinematics.dirX + filteredTipY * kinematics.dirY
            if (prevRemForward > newForward && newForward > 0f && prevRemForward <= maxArcWorld) {
                val targetForward = min(maxArcWorld, newForward + (prevRemForward - newForward) * ANTI_BACKTRACK_HOLD)
                val boost = targetForward - newForward
                filteredTipX += kinematics.dirX * boost
                filteredTipY += kinematics.dirY * boost
            }
        }

        prevTipOffsetX = filteredTipX
        prevTipOffsetY = filteredTipY
        hasPrevTipOffset = true
        lastFilterTimeMs = rawLastPoint.tMs

        val filteredTipLen = hypot(filteredTipX, filteredTipY)
        val minEmitWorld = (MIN_EMIT_DP * safeDensity) / safeZoom
        if (filteredTipLen < minEmitWorld) {
            hasLastRenderedTip = false
            return emptyList()
        }

        lastRenderedTipX = lastCommitted.x + filteredTipX
        lastRenderedTipY = lastCommitted.y + filteredTipY
        hasLastRenderedTip = true

        // 6. Emit the stabilized predicted curve anchored at lastCommitted, pinning style to lastCommitted.
        val curveSynthThresholdWorld = (CURVE_SYNTH_MIN_DP * safeDensity) / safeZoom
        if (count == 1 && filteredTipLen >= curveSynthThresholdWorld) {
            // Synthesize a C1-continuous quadratic Bézier arc from the committed stroke's outgoing tangent.
            val prevCommitted = committedPoints[committedPoints.lastIndex - 1]
            var commitTx = lastCommitted.x - prevCommitted.x
            var commitTy = lastCommitted.y - prevCommitted.y
            val commitLen = hypot(commitTx, commitTy)
            if (commitLen > 1e-4f) {
                commitTx /= commitLen
                commitTy /= commitLen
            } else {
                commitTx = kinematics.dirX
                commitTy = kinematics.dirY
            }
            val proj = (filteredTipX * commitTx + filteredTipY * commitTy).coerceAtLeast(filteredTipLen * 0.35f)
            val ctrlLen = min(proj * 0.5f, filteredTipLen * 0.55f)
            val p1x = commitTx * ctrlLen
            val p1y = commitTy * ctrlLen

            val out = ArrayList<StrokePoint>(CURVE_SYNTH_SAMPLES.size)
            for (t in CURVE_SYNTH_SAMPLES) {
                val inv = 1f - t
                val rx = 2f * inv * t * p1x + t * t * filteredTipX
                val ry = 2f * inv * t * p1y + t * t * filteredTipY
                out.add(
                    StrokePoint(
                        x = lastCommitted.x + rx,
                        y = lastCommitted.y + ry,
                        pressure = lastCommitted.pressure,
                        tilt = lastCommitted.tilt,
                        orientation = lastCommitted.orientation,
                        tMs = lastCommitted.tMs,
                    ),
                )
            }
            return out
        }

        val deltaTipX = filteredTipX - scaledTipX
        val deltaTipY = filteredTipY - scaledTipY
        val out = ArrayList<StrokePoint>(count)
        for (i in 0 until count) {
            val frac = (arcAt[i] / accArc).coerceIn(0f, 1f)
            val rx = relXs[i] * finalScale + deltaTipX * frac
            val ry = relYs[i] * finalScale + deltaTipY * frac
            out.add(
                StrokePoint(
                    x = lastCommitted.x + rx,
                    y = lastCommitted.y + ry,
                    pressure = lastCommitted.pressure,
                    tilt = lastCommitted.tilt,
                    orientation = lastCommitted.orientation,
                    tMs = lastCommitted.tMs,
                ),
            )
        }
        return out
    }

    private fun decayOrClear(
        lastCommitted: StrokePoint,
        realDirX: Float,
        realDirY: Float,
        zoom: Float,
        density: Float,
    ): List<StrokePoint> {
        hasLastRenderedTip = false
        if (!hasPrevTipOffset) return emptyList()
        val prevLen = hypot(prevTipOffsetX, prevTipOffsetY)
        val dot = if (prevLen > 1e-4f) {
            (prevTipOffsetX * realDirX + prevTipOffsetY * realDirY) / prevLen
        } else {
            0f
        }
        // If direction turned away from the previous prediction, drop immediately so we never linger on a corner.
        if (dot < MIN_DECAY_ALIGN_COS) {
            clearFilterState()
            return emptyList()
        }
        prevTipOffsetX *= DECAY_FACTOR
        prevTipOffsetY *= DECAY_FACTOR
        filteredDerivX = 0f
        filteredDerivY = 0f
        val minEmitWorld = (MIN_EMIT_DP * density) / zoom
        if (hypot(prevTipOffsetX, prevTipOffsetY) < minEmitWorld) {
            clearFilterState()
            return emptyList()
        }
        return listOf(
            StrokePoint(
                x = lastCommitted.x + prevTipOffsetX,
                y = lastCommitted.y + prevTipOffsetY,
                pressure = lastCommitted.pressure,
                tilt = lastCommitted.tilt,
                orientation = lastCommitted.orientation,
                tMs = lastCommitted.tMs,
            ),
        )
    }

    private fun clearFilterState() {
        hasPrevTipOffset = false
        prevTipOffsetX = 0f
        prevTipOffsetY = 0f
        filteredDerivX = 0f
        filteredDerivY = 0f
        lastFilterTimeMs = 0L
        hasLastRenderedTip = false
        lastRenderedTipX = 0f
        lastRenderedTipY = 0f
    }

    private class RecentKinematics(
        val dirX: Float,
        val dirY: Float,
        val speedWorldPerMs: Float,
        val speedPxPerMs: Float,
        val prevSpeedWorldPerMs: Float,
        val turnAngleRad: Float,
    )

    private fun estimateRecentKinematics(
        pNow: StrokePoint,
        zoom: Float,
        density: Float,
    ): RecentKinematics? {
        if (recentRaw.size < 2) return null

        val minChordWorld = (MIN_KINEMATIC_CHORD_DP * density) / zoom

        // Walk backward from newest sample to find a spatial chord long enough to resist digitizer quantization.
        val newest = recentRaw.last()
        val sameAsNewest = newest.tMs == pNow.tMs && hypot(newest.x - pNow.x, newest.y - pNow.y) < 1e-3f
        var idxPrev = if (sameAsNewest) recentRaw.size - 2 else recentRaw.size - 1
        if (idxPrev < 0) return null
        while (idxPrev > 0) {
            val cand = recentRaw[idxPrev]
            val dist = hypot(pNow.x - cand.x, pNow.y - cand.y)
            val dt = pNow.tMs - cand.tMs
            if (dist >= minChordWorld || dt >= MAX_CHORD_AGE_MS) break
            idxPrev--
        }

        val pPrev = recentRaw[idxPrev]
        val dtNow = (pNow.tMs - pPrev.tMs).coerceAtLeast(1L)
        if (dtNow > STALE_SAMPLE_MS) return null

        val dxNow = pNow.x - pPrev.x
        val dyNow = pNow.y - pPrev.y
        val distNow = hypot(dxNow, dyNow)
        if (distNow <= 1e-4f) return null

        val dirX = dxNow / distNow
        val dirY = dyNow / distNow
        val speedWorldPerMs = distNow / dtNow.toFloat()
        val speedPxPerMs = speedWorldPerMs * zoom

        // Look back one more chord to estimate turning rate and deceleration.
        var prevSpeedWorldPerMs = 0f
        var turnAngleRad = 0f
        if (idxPrev > 0) {
            var idxOlder = idxPrev - 1
            while (idxOlder > 0) {
                val cand = recentRaw[idxOlder]
                val dist = hypot(pPrev.x - cand.x, pPrev.y - cand.y)
                val dt = pPrev.tMs - cand.tMs
                if (dist >= minChordWorld || dt >= MAX_CHORD_AGE_MS) break
                idxOlder--
            }
            val pOlder = recentRaw[idxOlder]
            val dtPrev = (pPrev.tMs - pOlder.tMs).coerceAtLeast(1L)
            if (dtPrev <= STALE_SAMPLE_MS) {
                val dxPrev = pPrev.x - pOlder.x
                val dyPrev = pPrev.y - pOlder.y
                val distPrev = hypot(dxPrev, dyPrev)
                if (distPrev > 1e-4f) {
                    prevSpeedWorldPerMs = distPrev / dtPrev.toFloat()
                    val dot = ((dxPrev * dxNow + dyPrev * dyNow) / (distPrev * distNow)).coerceIn(-1f, 1f)
                    turnAngleRad = acos(dot)
                }
            }
        }

        return RecentKinematics(
            dirX = dirX,
            dirY = dirY,
            speedWorldPerMs = speedWorldPerMs,
            speedPxPerMs = speedPxPerMs,
            prevSpeedWorldPerMs = prevSpeedWorldPerMs,
            turnAngleRad = turnAngleRad,
        )
    }

    private fun oneEuroAlpha(dtSec: Float, cutoffHz: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(0.1f))
        return (dtSec / (dtSec + tau)).coerceIn(0.05f, 1f)
    }

    private fun smoothRamp(value: Float, edge0: Float, edge1: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    internal companion object {
        const val RAW_HISTORY_CAPACITY = 10
        const val MAX_PREDICT_DP = 24f
        const val MIN_EMIT_DP = 1.25f
        const val MIN_KINEMATIC_CHORD_DP = 3.5f
        const val MAX_CHORD_AGE_MS = 28L
        const val STALE_SAMPLE_MS = 65L

        const val MIN_SPEED_PX_PER_MS = 0.25f
        const val FULL_SPEED_PX_PER_MS = 0.90f

        const val TURN_FULL_CONF_RAD = 0.20f  // ~11.5 deg
        const val TURN_ZERO_CONF_RAD = 0.72f  // ~41 deg

        const val DECEL_ZERO_RATIO = 0.35f
        const val DECEL_FULL_RATIO = 0.72f

        const val MAX_SPEED_MULTIPLIER = 1.15f
        const val MIN_STEP_ALIGNMENT_COS = 0.80f // ~37 deg
        const val MIN_REAL_ALIGNMENT_COS = 0.68f // ~47 deg

        const val TIP_ALIGN_FULL_RAD = 0.22f // ~12.5 deg
        const val TIP_ALIGN_ZERO_RAD = 0.65f // ~37 deg

        const val MIN_TEMPORAL_COS = 0.65f
        const val MIN_DECAY_ALIGN_COS = 0.75f
        const val DECAY_FACTOR = 0.35f

        const val ONE_EURO_MIN_CUTOFF_HZ = 14f
        const val ONE_EURO_BETA = 0.012f
        const val ONE_EURO_DERIV_CUTOFF_HZ = 12f

        const val ANTI_BACKTRACK_MIN_CONF = 0.85f
        const val ANTI_BACKTRACK_HOLD = 0.75f

        const val CURVE_SYNTH_MIN_DP = 4f
        val CURVE_SYNTH_SAMPLES = floatArrayOf(0.35f, 0.7f, 1.0f)
    }
}
