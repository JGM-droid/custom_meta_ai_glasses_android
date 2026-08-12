package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvestigationSpeechStateTest {
  @Test
  fun startRequestedMovesToListening() {
    val transition =
        reduceInvestigationSpeechState(
            current = InvestigationSpeechUiState(),
            event = InvestigationSpeechEvent.StartRequested,
        )

    assertEquals(InvestigationSpeechUiPhase.LISTENING, transition.state.phase)
    assertEquals("Listening...", transition.state.speakButtonLabel)
    assertEquals(true, transition.state.canCancel)
  }

  @Test
  fun finalTranscriptReturnsToIdleAndProvidesTranscript() {
    val transition =
        reduceInvestigationSpeechState(
            current = InvestigationSpeechUiState(phase = InvestigationSpeechUiPhase.LISTENING),
            event = InvestigationSpeechEvent.FinalTranscript("  spoken context  "),
        )

    assertEquals(InvestigationSpeechUiPhase.IDLE, transition.state.phase)
    assertEquals("Speak", transition.state.speakButtonLabel)
    assertEquals("spoken context", transition.transcript)
  }

  @Test
  fun noMatchKeepsExistingExplanationSafeByReturningNoTranscript() {
    val transition =
        reduceInvestigationSpeechState(
            current = InvestigationSpeechUiState(phase = InvestigationSpeechUiPhase.LISTENING),
            event = InvestigationSpeechEvent.NoMatch,
        )

    assertEquals(InvestigationSpeechUiPhase.ERROR, transition.state.phase)
    assertEquals("Try again", transition.state.speakButtonLabel)
    assertNull(transition.transcript)
  }

  @Test
  fun permissionDeniedKeepsTypedFallbackAvailable() {
    val transition =
        reduceInvestigationSpeechState(
            current = InvestigationSpeechUiState(phase = InvestigationSpeechUiPhase.LISTENING),
            event = InvestigationSpeechEvent.PermissionDenied,
        )

    assertEquals(InvestigationSpeechUiPhase.ERROR, transition.state.phase)
    assertEquals("Microphone permission denied. You can type instead.", transition.state.feedbackMessage)
    assertNull(transition.transcript)
  }

  @Test
  fun cancelledReturnsToIdleAndClearsListeningState() {
    val transition =
        reduceInvestigationSpeechState(
            current = InvestigationSpeechUiState(phase = InvestigationSpeechUiPhase.LISTENING),
            event = InvestigationSpeechEvent.Cancelled,
        )

    assertEquals(InvestigationSpeechUiPhase.IDLE, transition.state.phase)
    assertEquals(false, transition.state.canCancel)
  }
}
