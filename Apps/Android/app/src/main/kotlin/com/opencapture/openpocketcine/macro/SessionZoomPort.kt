package com.opencapture.openpocketcine.macro

import com.opencapture.openpocketcine.AppModel

/**
 * [ZoomPort] over the live session's held zoom (the camera ramps the lens itself). The session
 * hops a D-Log2 body to D-Log before zooming in and back at 1× afterwards, and refuses that hop
 * while recording, so the floating zoom inherits all of that.
 */
class SessionZoomPort(private val model: AppModel) : ZoomPort {
    override fun currentFactor(): Double = model.session.status.value.zoomFactor ?: 1.0

    override fun maxFactor(): Double = model.zoomMax()

    override fun rate(zoomIn: Boolean, level: Int): Boolean = model.session.setZoomRate(level, zoomIn)

    override fun stopRate() = model.session.endZoomRate()
}
