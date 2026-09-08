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
 * ADR-061 reconciliation (identity collision fix): which product surface a
 * [investigationViewModelKey] belongs to. Before this existed, StreamScreen computed the exact
 * same key for a conversation-originated capture (`returnToConversation = true`) as it did - and
 * still does - for the legacy entry point, because both passed the same (sourceProjectId, null)
 * pair into the same 2-argument key function with no way to tell them apart. Since this app has no
 * NavHost/per-destination ViewModelStoreOwner (a single Activity ViewModelStore backs every
 * `viewModel(key = ...)` call), that collision meant a conversation-originated capture and a LATER
 * legacy Capture entry for the same Project - still unmigrated and fully supported, see
 * docs/ROADMAP.md's locked migration sequence - could silently resolve to the SAME
 * InvestigationSessionDebugViewModel instance and inherit its already-adopted evidence. See
 * InvestigationProductStateTest's collision-reproduction and isolation tests.
 */
internal enum class InvestigationInteractionContext { LEGACY, CONVERSATION }

/**
 * The Compose `viewModel(key = ...)` key for [InvestigationSessionDebugViewModel]. [interactionContext]
 * defaults to [InvestigationInteractionContext.LEGACY] so every pre-existing call site (StreamScreen's
 * legacy entry, ProjectDetailScreen's ContinueInvestigationSection) keeps computing the exact same
 * key it always has, unchanged - preserving Option B's closed-loop continuity guarantee (see AppRoot's
 * doc: StreamScreen glasses Capture and ProjectDetailScreen's "Continue on phone"/"Resume on glasses"
 * section must resolve to the SAME retained instance for the SAME Project). A conversation-originated
 * capture (StreamScreen with `returnToConversation = true`) and its ProjectConversationScreen handoff
 * counterpart must both instead pass [InvestigationInteractionContext.CONVERSATION] explicitly - they
 * still resolve to each other (required for the glasses-to-conversation evidence handoff to work at
 * all), but never to a same-Project legacy session, and vice versa. This app has no
 * NavHost/per-destination ViewModelStoreOwner - AppRoot switches top-level screens on a plain Compose
 * state var - so the single Activity's ViewModelStore is what actually carries evidence/explanation
 * across either handoff, not any new persistence.
 */
internal fun investigationViewModelKey(
    sourceProjectId: String?,
    continuationSessionId: String?,
    interactionContext: InvestigationInteractionContext = InvestigationInteractionContext.LEGACY,
): String =
    "${sourceProjectId ?: "unscoped"}:${interactionContext.name.lowercase()}:${continuationSessionId.orEmpty()}"

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

/**
 * ADR-061 architect review, Item 2: the conversation-mode equivalent of
 * [investigationReopenAffordanceLabel] - same underlying capability/state
 * ([deriveInvestigationProductState]'s [InvestigationProductState.capturedViewCount], via the SAME
 * InvestigationSessionDebugViewModel a conversation-originated capture's evidence is appended
 * into), worded without "Investigation" - the reused CAPABILITY must not carry the retired
 * WORKFLOW's branding into the primary conversation path. Only ever called with
 * COLLECTING_EVIDENCE/READY phase state in practice: a conversation-originated session never
 * reaches Analyze (that decision point is legacy-only - see shouldShowInvestigationPanel), so this
 * only needs to report how many accepted captures are pending.
 */
internal fun conversationAcceptedCaptureLabel(uiState: InvestigationSessionDebugUiState): String {
  val count = deriveInvestigationProductState(uiState).capturedViewCount
  val noun = if (count == 1) "photo" else "photos"
  return "$count $noun accepted"
}
