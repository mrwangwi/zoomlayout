package com.otaliastudios.zoom.internal.gestures

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.otaliastudios.zoom.*
import com.otaliastudios.zoom.ZoomApi.AbsolutePan
import com.otaliastudios.zoom.internal.matrix.MatrixController
import com.otaliastudios.zoom.internal.StateController
import com.otaliastudios.zoom.internal.movement.PanManager
import com.otaliastudios.zoom.internal.movement.ZoomManager

/**
 * Deals with pinch gestures.
 *  处理捏合手势
 * - Detects them
 * - Checks state using [stateController]
 * - Checks zoom using [zoomManager]
 * - Applies updates using the [matrixController]
 */
internal class PinchDetector(
    context: Context,
    private val zoomManager: ZoomManager,
    private val panManager: PanManager,
    private val stateController: StateController,
    private val matrixController: MatrixController
) : ScaleGestureDetector.OnScaleGestureListener {

    private val detector = ScaleGestureDetector(context, this)
    init {
        if (Build.VERSION.SDK_INT >= 19) detector.isQuickScaleEnabled = false
    }

    /** Point holding a [AbsolutePan] coordinate */
    private val initialFocusPoint: AbsolutePoint = AbsolutePoint(Float.NaN, Float.NaN)

    /** Indicating the current pan offset introduced by a pinch focus shift as [AbsolutePan] value */
    private val currentFocusOffset: AbsolutePoint = AbsolutePoint(0F, 0F)

    /** Tracks the last scale factor to determine zoom direction */
    private var lastScaleFactor: Float = 1f

    /**
     * Starts a pinch gesture or continues an ongoing gesture.
     * Returns true if we are interested in the result.
     */
    internal fun maybeStart(event: MotionEvent): Boolean {
        return detector.onTouchEvent(event)
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        lastScaleFactor = 1f
        return true // We are interested in this gesture
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (!zoomManager.isEnabled) return false
        if (!stateController.setPinching()) return false

        // get the absolute pan position of the detector focus point
        // Must invert the point coordinates since we use negative coords inside
        val newAbsFocusPoint = containerPointToContentPoint(PointF(-detector.focusX, -detector.focusY))

        if (initialFocusPoint.x.isNaN()) {
            initialFocusPoint.set(newAbsFocusPoint)
            LOG.i("onScale:", "Setting initial focus:", initialFocusPoint)
        } else {
            // when the initial focus point is set, use it to
            // calculate the location difference to the current focus point
            currentFocusOffset.set(initialFocusPoint - newAbsFocusPoint)
            LOG.i("onScale:", "Got focus offset:", currentFocusOffset)
        }
        lastScaleFactor = detector.scaleFactor
        val newZoom = matrixController.zoom * detector.scaleFactor
        matrixController.applyUpdate {
            Log.d("TAG", "onScale: " + detector.scaleFactor
                    + " " + detector.focusX + " " + detector.focusY)
            zoomTo(newZoom, true)
            panBy(currentFocusOffset, true)
            pivot(detector.focusX, detector.focusY)
        }
        return true
    }

    /**
     * Resets the fields of this pinch gesture listener
     * to prepare it for the next pinch gesture detection
     * and remove any remaining data from the previous gesture.
     * 重置此捏合手势侦听器的字段，以便为下一次捏合手势检测做好准备，并从上一个手势中删除任何剩余数据
     */
    override fun onScaleEnd(detector: ScaleGestureDetector) {
        LOG.i("onScaleEnd:",
            "mInitialAbsFocusPoint.x:", initialFocusPoint.x,
            "mInitialAbsFocusPoint.y:", initialFocusPoint.y,
            "mOverZoomEnabled;", zoomManager.isOverEnabled)
        handleOnScaleEnd()
        initialFocusPoint.set(Float.NaN, Float.NaN)
        currentFocusOffset.set(0F, 0F)
    }

    private fun handleOnScaleEnd() {
        if (!zoomManager.isOverEnabled && !panManager.isOverEnabled) {
            stateController.makeIdle()
            return
        }

        @ZoomApi.RealZoom val maxZoom = zoomManager.getMaxZoom()
        @ZoomApi.RealZoom val minZoom = zoomManager.getMinZoom()
        @ZoomApi.RealZoom val currentZoom = matrixController.zoom
        @ZoomApi.RealZoom var newZoom = currentZoom

        if (lastScaleFactor > 1) {
            newZoom = maxZoom
        } else {
            newZoom = minZoom
        }

        Log.d("onScaleEnd:",
            "zoom:" + currentZoom +
                    "newZoom:" + newZoom +
                    "max:" + maxZoom +
                    "min:" + minZoom)

        var panFix = panManager.correction.toAbsolute(newZoom)
        if (panFix.x == 0F && panFix.y == 0F && newZoom.compareTo(currentZoom) == 0) {
            stateController.makeIdle()
            return
        }

        val zoomTarget = computeZoomPivot(panFix)
        val newPan = matrixController.pan + panFix
        if (newZoom.compareTo(currentZoom) != 0) {
            val oldPan = AbsolutePoint(matrixController.pan)
            val oldZoom = currentZoom

            matrixController.applyUpdate {
                zoomTo(newZoom, true)
                pivot(zoomTarget.x, zoomTarget.y)
                overPan = true
                notify = false
            }

            panFix = panManager.correction.toAbsolute(newZoom)
            newPan.set(matrixController.pan + panFix)

            matrixController.applyUpdate {
                zoomTo(oldZoom, true)
                panTo(oldPan, true)
                notify = false
            }
        }

        // New state will be ANIMATING
        if (panFix.x == 0F && panFix.y == 0F) {
            // no overpan to correct, only fix overzoom
            matrixController.animateUpdate { zoomTo(newZoom, true) }
        } else {
            // fix overpan (overzoom is also corrected in here if necessary)
            matrixController.animateUpdate {
                zoomTo(newZoom, true)
                panTo(newPan, true)
                pivot(zoomTarget.x, zoomTarget.y)
            }
        }
    }

    /**
     * Calculate pivot point to use for zoom based on pan fixes to be applied.
     *
     * @param fixPan the amount of pan to apply to get into a valid state (no overscroll)
     * @return x-axis and y-axis view coordinates
     */
    private fun computeZoomPivot(fixPan: AbsolutePoint): PointF {
        if (matrixController.zoom <= 1F) {
            // The zoom pivot point here should be based on the gravity that is used
            // to initially transform the content.
            // Currently this is always [View.Gravity.CENTER] as indicated by [mTransformationGravity]
            // but this might be changed by the user.
            val center = AbsolutePoint(-matrixController.contentWidth / 2F, -matrixController.contentHeight / 2F)
            val result = contentPointToContainerPoint(center)
            result.set(-result.x, -result.y)
            return result
        }

        val x = when {
            fixPan.x > 0 -> matrixController.containerWidth // content needs to be moved left, use the right border as target
            fixPan.x < 0 -> 0F // content needs to move right, use the left border as target
            else -> matrixController.containerWidth / 2F // axis is not changed, use center as target
        }

        val y = when {
            fixPan.y > 0 -> matrixController.containerHeight // content needs to be moved up, use the bottom border as target
            fixPan.y < 0 -> 0F // content needs to move down, use the top border as target
            else -> matrixController.containerHeight / 2F // axis is not changed, use center as target
        }
        return PointF(x, y)
    }

    /**
     * Calculates the content [AbsolutePoint] value for a container coordinate.
     * This is the reverse operation to [contentPointToContainerPoint].
     *
     * Example:
     * When the viewport is 1000x1000 and the [ZoomLayout] content is 3000x3000 and exactly centered
     * and you call [containerPointToContentPoint(-500,-500)] the result will be 1500x1500
     *
     * @param containerPoint screen point
     * @return the content absolute coordinate
     */
    private fun containerPointToContentPoint(containerPoint: PointF): AbsolutePoint {
        // Account for current pan.
        val scaledPoint = ScaledPoint(
            matrixController.scaledPanX + containerPoint.x,
            matrixController.scaledPanY + containerPoint.y)
        // Transform to an absolute, scale-independent value.
        return scaledPoint.toAbsolute(matrixController.zoom)
    }

    /**
     * Calculates the container coordinate from an [AbsolutePoint].
     * This is the reverse operation to [containerPointToContentPoint].
     *
     * @return the container coordinate
     */
    private fun contentPointToContainerPoint(contentPoint: AbsolutePoint): PointF {
        val result = contentPoint.toScaled(matrixController.zoom) - matrixController.scaledPan
        return PointF(result.x, result.y)
    }

    companion object {
        private val TAG = PinchDetector::class.java.simpleName
        private val LOG = ZoomLogger.create(TAG)
    }
}
