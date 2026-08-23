package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-testable core of the Voice-to-Text composer merge behavior (Phase 5 of the Voice-to-Text
 * slice). appendTranscriptToDraft is the only place transcript text ever touches draft state -
 * everything else (askProject() not being called, no backend/Project Memory mutation) is
 * structurally guaranteed rather than behaviorally tested here: the voice event handling path in
 * ProjectWorkspaceScreen.kt only ever calls this pure function and setValue on local Compose
 * state - it has no reference to ProjectDetailViewModel.askProject or any repository/network
 * call, so there is nothing for a unit test of this function to accidentally trigger. The
 * end-to-end "voice never auto-submits, never mutates the backend" proof is covered live in
 * ui/ProjectWorkspaceScreenTest.kt (instrumented) and via physical-device verification.
 */
class ProjectWorkspaceScreenLogicTest {

  @Test
  fun emptyDraftIsReplacedOutrightByTranscript() {
    val result = appendTranscriptToDraft(currentDraft = "", transcript = "Check voltage at the contactor.")

    assertEquals("Check voltage at the contactor.", result)
  }

  @Test
  fun whitespaceOnlyDraftIsTreatedAsEmpty() {
    // A user who typed only spaces/newlines and then spoke should get a clean transcript, not
    // stray leading whitespace glued onto it.
    val result = appendTranscriptToDraft(currentDraft = "   \n  ", transcript = "Check voltage at the contactor.")

    assertEquals("Check voltage at the contactor.", result)
  }

  @Test
  fun nonEmptyDraftIsPreservedAndTranscriptIsAppendedOnANewLine() {
    val result =
        appendTranscriptToDraft(
            currentDraft = "Additional context:",
            transcript = "The capacitor was replaced yesterday.",
        )

    assertEquals("Additional context:\nThe capacitor was replaced yesterday.", result)
  }

  @Test
  fun appendingTwiceInARowKeepsBothTranscriptsAndTheOriginalText() {
    val afterFirst = appendTranscriptToDraft(currentDraft = "Typed note.", transcript = "First spoken part.")
    val afterSecond = appendTranscriptToDraft(currentDraft = afterFirst, transcript = "Second spoken part.")

    assertEquals("Typed note.\nFirst spoken part.\nSecond spoken part.", afterSecond)
  }
}
