package space.owork.draw.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Serializable
enum class CanvasLockMode(
    val label: String,
    val shortBadge: String?,
) {
    @SerialName("unlocked")
    UNLOCKED("Unlocked", null),

    @SerialName("lock_rotation")
    LOCK_ROTATION("Lock Rotation", "Rot"),

    @SerialName("lock_zoom")
    LOCK_ZOOM("Lock Zoom", "Zoom"),

    @SerialName("lock_zoom_and_rotation")
    LOCK_ZOOM_AND_ROTATION("Lock Zoom & Rotation", "Z+R");

    fun next(): CanvasLockMode = when (this) {
        UNLOCKED -> LOCK_ROTATION
        LOCK_ROTATION -> LOCK_ZOOM
        LOCK_ZOOM -> LOCK_ZOOM_AND_ROTATION
        LOCK_ZOOM_AND_ROTATION -> UNLOCKED
    }

    fun toCameraLocks(lockPan: Boolean = false): CameraLocks = when (this) {
        UNLOCKED -> CameraLocks(lockPan = lockPan, lockZoom = false, lockRotation = false)
        LOCK_ROTATION -> CameraLocks(lockPan = lockPan, lockZoom = false, lockRotation = true)
        LOCK_ZOOM -> CameraLocks(lockPan = lockPan, lockZoom = true, lockRotation = false)
        LOCK_ZOOM_AND_ROTATION -> CameraLocks(lockPan = lockPan, lockZoom = true, lockRotation = true)
    }
}

@Serializable
data class CameraLocks(
    val lockPan: Boolean = false,
    val lockZoom: Boolean = false,
    val lockRotation: Boolean = false,
) {
    val anyLocked: Boolean get() = lockPan || lockZoom || lockRotation
    val allLocked: Boolean get() = lockPan && lockZoom && lockRotation
}

@Serializable
data class WorldPoint(
    val x: Float,
    val y: Float,
)

@Serializable
data class WorldRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun expanded(margin: Float): WorldRect = WorldRect(
        left = left - margin,
        top = top - margin,
        right = right + margin,
        bottom = bottom + margin,
    )

    fun union(other: WorldRect): WorldRect = WorldRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    fun intersects(other: WorldRect): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    companion object {
        fun fromPoint(x: Float, y: Float, radius: Float = 0f): WorldRect =
            WorldRect(x - radius, y - radius, x + radius, y + radius)
    }
}

/**
 * 2D camera mapping between screen pixel coordinates (origin top-left) and
 * document world coordinates (origin top-left of artboard for fixed canvases,
 * or world origin (0, 0) for infinite canvases).
 *
 * Screen = Rotate(zoom * World) + (offsetX, offsetY)
 */
@Serializable
data class ViewportCamera(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val zoom: Float = 1f,
    val rotationRad: Float = 0f,
) {
    fun worldToScreen(wx: Float, wy: Float): WorldPoint {
        val sx = wx * zoom
        val sy = wy * zoom
        if (rotationRad == 0f) {
            return WorldPoint(sx + offsetX, sy + offsetY)
        }
        val c = cos(rotationRad)
        val s = sin(rotationRad)
        return WorldPoint(
            x = sx * c - sy * s + offsetX,
            y = sx * s + sy * c + offsetY,
        )
    }

    fun screenToWorld(sx: Float, sy: Float): WorldPoint {
        val dx = sx - offsetX
        val dy = sy - offsetY
        val invZoom = 1f / zoom
        if (rotationRad == 0f) {
            return WorldPoint(dx * invZoom, dy * invZoom)
        }
        val c = cos(-rotationRad)
        val s = sin(-rotationRad)
        return WorldPoint(
            x = (dx * c - dy * s) * invZoom,
            y = (dx * s + dy * c) * invZoom,
        )
    }

    /**
     * Applies a simultaneous 2-finger gesture (pan, zoom, and rotation) anchored at [pivotScreenX], [pivotScreenY].
     */
    fun applyTransform(
        pivotScreenX: Float,
        pivotScreenY: Float,
        panDeltaX: Float,
        panDeltaY: Float,
        zoomFactor: Float,
        rotationDeltaRad: Float = 0f,
        minZoom: Float = MIN_ZOOM,
        maxZoom: Float = MAX_ZOOM,
    ): ViewportCamera {
        val worldPivot = screenToWorld(pivotScreenX, pivotScreenY)
        val newZoom = (zoom * zoomFactor).coerceIn(minZoom, maxZoom)
        val newRot = rotationRad + rotationDeltaRad
        val c = cos(newRot)
        val s = sin(newRot)
        val rx = (worldPivot.x * newZoom) * c - (worldPivot.y * newZoom) * s
        val ry = (worldPivot.x * newZoom) * s + (worldPivot.y * newZoom) * c
        return ViewportCamera(
            offsetX = pivotScreenX + panDeltaX - rx,
            offsetY = pivotScreenY + panDeltaY - ry,
            zoom = newZoom,
            rotationRad = newRot,
        )
    }

    fun visibleWorldBounds(viewportWidth: Float, viewportHeight: Float): WorldRect {
        val p0 = screenToWorld(0f, 0f)
        val p1 = screenToWorld(viewportWidth, 0f)
        val p2 = screenToWorld(viewportWidth, viewportHeight)
        val p3 = screenToWorld(0f, viewportHeight)
        return WorldRect(
            left = min(min(p0.x, p1.x), min(p2.x, p3.x)),
            top = min(min(p0.y, p1.y), min(p2.y, p3.y)),
            right = max(max(p0.x, p1.x), max(p2.x, p3.x)),
            bottom = max(max(p0.y, p1.y), max(p2.y, p3.y)),
        )
    }

    companion object {
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 32f

        fun fitBounds(
            bounds: WorldRect,
            viewportWidth: Float,
            viewportHeight: Float,
            paddingPx: Float = 64f,
        ): ViewportCamera {
            if (viewportWidth <= 0f || viewportHeight <= 0f || bounds.isEmpty) {
                return ViewportCamera(viewportWidth * 0.5f, viewportHeight * 0.5f, 1f, 0f)
            }
            val availW = (viewportWidth - paddingPx * 2f).coerceAtLeast(32f)
            val availH = (viewportHeight - paddingPx * 2f).coerceAtLeast(32f)
            val zoom = min(availW / bounds.width, availH / bounds.height)
                .coerceIn(MIN_ZOOM, MAX_ZOOM)
            val offsetX = viewportWidth * 0.5f - bounds.centerX * zoom
            val offsetY = viewportHeight * 0.5f - bounds.centerY * zoom
            return ViewportCamera(offsetX = offsetX, offsetY = offsetY, zoom = zoom, rotationRad = 0f)
        }

        fun centeredOnOrigin(viewportWidth: Float, viewportHeight: Float, zoom: Float = 1f): ViewportCamera =
            ViewportCamera(offsetX = viewportWidth * 0.5f, offsetY = viewportHeight * 0.5f, zoom = zoom, rotationRad = 0f)
    }
}

internal fun distancePointToSegment(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq <= 1e-6f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
