package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

internal enum class InvestigationProductPhase {
  READY,
  COLLECTING_EVIDENCE,
  READY_TO_ANALYZE,
  ANALYZING,
  COMPLETED,
  FAILED,
}

internal data class InvestigationProductState(
    val phase: InvestigationProductPhase,
    val capturedViewCount: Int,
    val hasCaptureCapacity: Boolean,
    val nextViewNumber: Int?,
    val hasExplanation: Boolean,
    val canAnalyze: Boolean,
) {
  val captureActionLabel: String =
      when {
        !hasCaptureCapacity -> "Investigation full"
        capturedViewCount == 0 -> "Capture first view"
        else -> "Capture another view"
      }
}

internal fun deriveInvestigationProductState(
    uiState: InvestigationSessionDebugUiState,
): InvestigationProductState {
  val capturedCount = uiState.activeCaptureCount.coerceIn(0, InvestigationCaptureSlots.MAX_CAPTURE_SLOTS)
  val hasExplanation = uiState.explanationText.trim().isNotEmpty()
  val hasEvidence = capturedCount > 0
  val isAnalyzing =
      uiState.clientState in
          setOf(
              InvestigationClientState.PREPARING,
              InvestigationClientState.CREATING_SESSION,
              InvestigationClientState.UPLOADING_EVIDENCE,
              InvestigationClientState.INITIATING_ANALYSIS,
              InvestigationClientState.POLLING,
          ) || uiState.isRunning
  val canAnalyze = hasEvidence && hasExplanation && !isAnalyzing

  val phase =
      when {
        isAnalyzing -> InvestigationProductPhase.ANALYZING
        uiState.clientState == InvestigationClientState.COMPLETED && uiState.compactResult != null -> InvestigationProductPhase.COMPLETED
        uiState.clientState == InvestigationClientState.FAILED -> InvestigationProductPhase.FAILED
        canAnalyze -> InvestigationProductPhase.READY_TO_ANALYZE
        hasEvidence -> InvestigationProductPhase.COLLECTING_EVIDENCE
        else -> InvestigationProductPhase.READY
      }

  return InvestigationProductState(
      phase = phase,
      capturedViewCount = capturedCount,
      hasCaptureCapacity = uiState.hasCaptureCapacity,
      nextViewNumber = if (uiState.hasCaptureCapacity) capturedCount + 1 else null,
      hasExplanation = hasExplanation,
      canAnalyze = canAnalyze,
  )
}

/**
 * The Compose `viewModel(key = ...)` key for [InvestigationSessionDebugViewModel], shared by every
 * entry point that must resolve to the SAME retained instance rather than a fresh one - notably
 * StreamScreen (glasses Capture) and ProjectDetailScreen's "Continue on phone"/"Resume on glasses"
 * section (see AppRoot's Option B closed loop). This app has no NavHost/per-destination
 * ViewModelStoreOwner - AppRoot switches top-level screens on a plain Compose state var - so the
 * single Activity's ViewModelStore is what actually carries evidence/explanation across that
 * handoff, not any new persistence: two call sites computing this identical string are guaranteed
 * by Compose to get back the literal same ViewModel instance. See
 * InvestigationProductStateTest's key-continuity test for the proof this holds for the pre-Analyze
 * handoff (both sides pass a null continuationSessionId at that point in the flow).
 */
internal fun investigationViewModelKey(sourceProjectId: String?, continuationSessionId: String?): String =
    "${sourceProjectId ?: "unscoped"}:${continuationSessionId.orEmpty()}"

internal fun hasActiveInvestigation(uiState: InvestigationSessionDebugUiState): Boolean {
  val hasEvidence = uiState.activeCaptureCount > 0
  val hasExplanation = uiState.explanationText.trim().isNotEmpty()
  val hasIdentifiers = uiState.sessionId != null || uiState.investigationId != null
  val hasResult = uiState.compactResult != null
  val hasProgress = uiState.clientState != InvestigationClientState.IDLE || uiState.isRunning
  return hasEvidence || hasExplanation || hasIdentifiers || hasResult || hasProgress
}

internal fun investigationReopenAffordanceLabel(uiState: InvestigationSessionDebugUiState): String {
  val productState = deriveInvestigationProductState(uiState)
  return when (productState.phase) {
    InvestigationProductPhase.COLLECTING_EVIDENCE -> {
      val noun = if (productState.capturedViewCount == 1) "view" else "views"
      "Investigation · ${productState.capturedViewCount} $noun"
    }
    InvestigationProductPhase.READY_TO_ANALYZE -> "Investigation · Ready to analyze"
    InvestigationProductPhase.ANALYZING -> "Investigation · Analyzing..."
    InvestigationProductPhase.COMPLETED -> "Investigation · Result ready"
    InvestigationProductPhase.FAILED -> "Investigation · Needs attention"
    InvestigationProductPhase.READY -> "Investigation"
  }
}
