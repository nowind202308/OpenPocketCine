package com.opencapture.openpocketcine.macro

import com.opencapture.openpocketcine.AppModel

/**
 * [ZoomPort] over the live session's pinch zoom, the path the app's two-finger zoom takes. The
 * session paces lens writes, hops a D-Log2 body to D-Log past 1× and back at the end, and
 * refuses a colour hop while recording, so the floating zoom inherits all of that.
 */
class SessionZoomPort(private val model: AppModel) : ZoomPort {
    override fun currentFactor(): Double = model.session.status.value.zoomFactor ?: 1.0

    override fun maxFactor(): Double = model.zoomMax()

    override fun update(magnification: Double) = model.updateZoomPinch(magnification)

    override fun end() = model.endZoomPinch()
}
