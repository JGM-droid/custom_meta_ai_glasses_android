package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

internal enum class InvestigationSpeechUiPhase {
  IDLE,
  LISTENING,
  ERROR,
}

internal data class InvestigationSpeechUiState(
    val phase: InvestigationSpeechUiPhase = InvestigationSpeechUiPhase.IDLE,
    val feedbackMessage: String? = null,
) {
  val speakButtonLabel: String =
      when (phase) {
        InvestigationSpeechUiPhase.IDLE -> "Speak"
        InvestigationSpeechUiPhase.LISTENING -> "Listening..."
        InvestigationSpeechUiPhase.ERROR -> "Try again"
      }

  val canCancel: Boolean = phase == InvestigationSpeechUiPhase.LISTENING
}

internal sealed interface InvestigationSpeechEvent {
  data object StartRequested : InvestigationSpeechEvent

  data object Ready : InvestigationSpeechEvent

  data object Listening : InvestigationSpeechEvent

  data class FinalTranscript(val text: String) : InvestigationSpeechEvent

  data object NoMatch : InvestigationSpeechEvent

  data object Timeout : InvestigationSpeechEvent

  data object Busy : InvestigationSpeechEvent

  data object PermissionDenied : InvestigationSpeechEvent

  data class UnknownError(val message: String) : InvestigationSpeechEvent

  data object Cancelled : InvestigationSpeechEvent
}

internal data class InvestigationSpeechTransition(
    val state: InvestigationSpeechUiState,
    val transcript: String? = null,
)

internal fun reduceInvestigationSpeechState(
    current: InvestigationSpeechUiState,
    event: InvestigationSpeechEvent,
): InvestigationSpeechTransition {
  return when (event) {
    InvestigationSpeechEvent.StartRequested,
    InvestigationSpeechEvent.Ready,
    InvestigationSpeechEvent.Listening -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.LISTENING,
                  feedbackMessage = "Listening for a short explanation...",
              ),
      )
    }

    is InvestigationSpeechEvent.FinalTranscript -> {
      val transcript = event.text.trim()
      if (transcript.isBlank()) {
        InvestigationSpeechTransition(
            state =
                current.copy(
                    phase = InvestigationSpeechUiPhase.ERROR,
                    feedbackMessage = "No speech detected. Try again.",
                ),
            transcript = null,
        )
      } else {
        InvestigationSpeechTransition(
            state =
                current.copy(
                    phase = InvestigationSpeechUiPhase.IDLE,
                    feedbackMessage = "Transcript captured.",
                ),
            transcript = transcript,
        )
      }
    }

    InvestigationSpeechEvent.NoMatch -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.ERROR,
                  feedbackMessage = "Could not recognize speech. Try again.",
              ),
      )
    }

    InvestigationSpeechEvent.Timeout -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.ERROR,
                  feedbackMessage = "Speech timed out. Try again.",
              ),
      )
    }

    InvestigationSpeechEvent.Busy -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.ERROR,
                  feedbackMessage = "Speech recognizer busy. Try again.",
              ),
      )
    }

    InvestigationSpeechEvent.PermissionDenied -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.ERROR,
                  feedbackMessage = "Microphone permission denied. You can type instead.",
              ),
      )
    }

    is InvestigationSpeechEvent.UnknownError -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.ERROR,
                  feedbackMessage = event.message,
              ),
      )
    }

    InvestigationSpeechEvent.Cancelled -> {
      InvestigationSpeechTransition(
          state =
              current.copy(
                  phase = InvestigationSpeechUiPhase.IDLE,
                  feedbackMessage = "Voice capture cancelled.",
              ),
      )
    }
  }
}