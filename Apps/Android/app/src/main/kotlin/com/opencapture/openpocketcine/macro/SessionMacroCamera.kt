package com.opencapture.openpocketcine.macro

import com.opencapture.opencine.vocabulary.ConnectionPhase
import com.opencapture.openpocketcine.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** [MacroCamera] over the live Pocket session; every write goes through an existing AppModel setter. */
class SessionMacroCamera(private val model: AppModel, scope: CoroutineScope) : MacroCamera {
    override val state: StateFlow<MacroCameraState> =
        combine(model.session.status, model.session.phaseFlow) { s, phase ->
            MacroCameraState(
                live = phase == ConnectionPhase.LIVE,
                recording = s.isRecording,
                colorMode = s.colorMode,
                zoomFactor = s.zoomFactor,
                expoMode = s.expoMode,
                isoLimit = s.isoLimit,
                isoIndex = s.isoIndex,
                availableColorModes = s.availableColorModes,
            )
        }.stateIn(scope, SharingStarted.Eagerly, MacroCameraState())

    override fun send(step: MacroStep) {
        when (step) {
            is MacroStep.Zoom -> model.setZoom(step.factor)
            is MacroStep.ColorMode -> model.setColorMode(step.mode)
            is MacroStep.ExposureMode -> model.setExpoMode(step.mode)
            is MacroStep.IsoCeiling -> model.setIsoLimit(step.raw)
            is MacroStep.IsoFixed -> model.setIsoIndex(step.index)
        }
    }

    override suspend fun refreshIsoCeiling(): Boolean = model.refreshIsoLimitNow()
}
