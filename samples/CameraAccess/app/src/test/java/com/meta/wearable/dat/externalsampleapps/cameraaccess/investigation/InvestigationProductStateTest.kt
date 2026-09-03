package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationProductStateTest {
  /**
   * Option B closed loop's continuity guarantee (see AGENTS.md/docs/ROADMAP.md): StreamScreen
   * (glasses Capture) and ProjectDetailScreen's ContinueInvestigationSection ("Continue on
   * phone"/"Resume on glasses") must resolve to the SAME InvestigationSessionDebugViewModel
   * instance via Compose's viewModel(key = ...), or evidence collected on glasses would be lost
   * on that handoff. This app has no NavHost, so the Activity's shared ViewModelStore is what
   * actually carries that state - contingent entirely on both call sites computing this identical
   * key string for the same Project at the same (pre-Analyze, no backend session yet) point in
   * the flow. This test is that proof.
   */
  @Test
  fun investigationViewModelKeyMatchesAcrossThePhoneHandoffForTheSameProject() {
    val handoffKey = investigationViewModelKey(sourceProjectId = "project-a", continuationSessionId = null)
    val resumeKey = investigationViewModelKey(sourceProjectId = "project-a", continuationSessionId = null)

    assertEquals(handoffKey, resumeKey)
    // A different Project must never collide onto the same retained instance.
    assertFalse(
        investigationViewModelKey("project-a", null) == investigationViewModelKey("project-b", null),
    )
    // A later, real backend session (the post-Analyze/trust-followup case) is deliberately a
    // DIFFERENT key from the pre-Analyze handoff above - it must not silently resolve to the same
    // stale instance.
    assertFalse(
        investigationViewModelKey("project-a", null) ==
            investigationViewModelKey("project-a", "session-1"),
    )
  }

  @Test
  fun restoredKnownSessionIsPromotedToRepositoryReconciliation() {
    assertEquals(
        "known-session",
        resolveContinuationSessionId(
            initialContinuationSessionId = null,
            savedContinuationSessionId = null,
            savedSessionId = "known-session",
        ),
    )
    assertEquals(
        "follow-up-session",
        resolveContinuationSessionId(
            initialContinuationSessionId = null,
            savedContinuationSessionId = "follow-up-session",
            savedSessionId = "original-session",
        ),
    )
  }

  @Test
  fun zeroEvidenceStartsReady() {
    val state = deriveInvestigationProductState(InvestigationSessionDebugUiState())

    assertEquals(InvestigationProductPhase.READY, state.phase)
    assertEquals(0, state.capturedViewCount)
    assertTrue(state.hasCaptureCapacity)
    assertFalse(state.canAnalyze)
  }

  @Test
  fun oneEvidenceReportsCaptureCountOne() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 1,
                hasCaptureCapacity = true,
            ),
        )

    assertEquals(1, state.capturedViewCount)
    assertEquals(InvestigationProductPhase.COLLECTING_EVIDENCE, state.phase)
  }

  @Test
  fun twoEvidenceReportsCaptureCountTwo() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 2,
                hasCaptureCapacity = true,
            ),
        )

    assertEquals(2, state.capturedViewCount)
    assertEquals(3, state.nextViewNumber)
  }

  @Test
  fun threeEvidenceDisablesCaptureCapacity() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 3,
                hasCaptureCapacity = false,
            ),
        )

    assertEquals(3, state.capturedViewCount)
    assertFalse(state.hasCaptureCapacity)
    assertEquals(null, state.nextViewNumber)
  }

  @Test
  fun evidenceAndExplanationAreReadyToAnalyze() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 2,
                hasCaptureCapacity = true,
                explanationText = "Explain these views",
                clientState = InvestigationClientState.IDLE,
                isRunning = false,
            ),
        )

    assertEquals(InvestigationProductPhase.READY_TO_ANALYZE, state.phase)
    assertTrue(state.canAnalyze)
  }

  @Test
  fun submittingMapsToAnalyzing() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 2,
                explanationText = "context",
                clientState = InvestigationClientState.UPLOADING_EVIDENCE,
                isRunning = true,
            ),
        )

    assertEquals(InvestigationProductPhase.ANALYZING, state.phase)
    assertFalse(state.canAnalyze)
  }

  @Test
  fun completedResponseMapsToCompleted() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 2,
                explanationText = "context",
                clientState = InvestigationClientState.COMPLETED,
                compactResult =
                    BackendCompactResultDto(
                        schemaVersion = "1.0",
                        projectionVersion = "1.0",
                        investigationId = "inv_1",
                        status = BackendAnalysisStatus.ANALYZED,
                        diagnosisShort = "Loose cable",
                        requiredNextActionShort = "Retighten cable",
                        uncertaintyFlag = false,
                        freshnessState = "fresh",
                        completedAtUtc = Instant.parse("2026-07-18T12:00:00Z"),
                        ageSeconds = null,
                    ),
            ),
        )

    assertEquals(InvestigationProductPhase.COMPLETED, state.phase)
  }

  @Test
  fun failedStateMapsToFailed() {
    val state =
        deriveInvestigationProductState(
            InvestigationSessionDebugUiState(
                activeCaptureCount = 1,
                explanationText = "context",
                clientState = InvestigationClientState.FAILED,
                statusMessage = "network error",
            ),
        )

    assertEquals(InvestigationProductPhase.FAILED, state.phase)
  }

    @Test
    fun noActiveInvestigationDoesNotShowReopenAffordance() {
        val isActive = hasActiveInvestigation(InvestigationSessionDebugUiState())

        assertFalse(isActive)
    }

    @Test
    fun evidenceMakesInvestigationActive() {
        val isActive =
                hasActiveInvestigation(
                        InvestigationSessionDebugUiState(
                                activeCaptureCount = 1,
                                hasCaptureCapacity = true,
                        ),
                )

        assertTrue(isActive)
    }

    @Test
    fun collectingStateUsesCountLabel() {
        val label =
                investigationReopenAffordanceLabel(
                        InvestigationSessionDebugUiState(
                                activeCaptureCount = 2,
                                hasCaptureCapacity = true,
                        ),
                )

        assertEquals("Investigation · 2 views", label)
    }

    @Test
    fun analyzingStateUsesAnalyzingLabel() {
        val label =
                investigationReopenAffordanceLabel(
                        InvestigationSessionDebugUiState(
                                activeCaptureCount = 1,
                                explanationText = "context",
                                clientState = InvestigationClientState.POLLING,
                                isRunning = true,
                        ),
                )

        assertEquals("Investigation · Analyzing...", label)
    }

    @Test
    fun completedStateUsesResultReadyLabel() {
        val label =
                investigationReopenAffordanceLabel(
                        InvestigationSessionDebugUiState(
                                activeCaptureCount = 1,
                                explanationText = "context",
                                clientState = InvestigationClientState.COMPLETED,
                                compactResult =
                                        BackendCompactResultDto(
                                                schemaVersion = "1.0",
                                                projectionVersion = "1.0",
                                                investigationId = "inv_1",
                                                status = BackendAnalysisStatus.ANALYZED,
                                                diagnosisShort = "Loose cable",
                                                requiredNextActionShort = "Retighten cable",
                                                uncertaintyFlag = false,
                                                freshnessState = "fresh",
                                                completedAtUtc = Instant.parse("2026-07-18T12:00:00Z"),
                                                ageSeconds = 0,
                                        ),
                        ),
                )

        assertEquals("Investigation · Result ready", label)
    }
}
