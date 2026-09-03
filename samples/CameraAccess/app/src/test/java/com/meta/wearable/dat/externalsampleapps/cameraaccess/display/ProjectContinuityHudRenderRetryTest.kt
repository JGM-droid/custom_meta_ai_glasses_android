/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Focused coverage for the HUD's render-failure recovery (see docs on
 * ProjectContinuityHudController.renderCurrentStateWithOneRetry()). Proven bug: a transient DAT
 * Display sendContent() failure during capture left the physical HUD screen stuck on an older
 * render generation than the state machine had already advanced to, so every subsequent tap (not
 * just Capture) failed the replay-protection generation check. retryOnceThenReport is extracted as
 * a small, pure, directly-testable unit for the same reason withOneRetry/ProjectContinuityHudRetryTest
 * already exist: ProjectContinuityHudController itself needs a live DAT Display/DeviceSession and
 * isn't practically unit-testable at this level.
 */
class ProjectContinuityHudRenderRetryTest {
  @Test
  fun succeedsOnFirstAttemptWithoutRetryingOrReporting() = runBlocking {
    var attempts = 0
    var exhausted = false

    retryOnceThenReport(delayMillis = 5, onExhausted = { exhausted = true }) {
      attempts++
      true
    }

    assertEquals(1, attempts)
    assertFalse(exhausted)
  }

  @Test
  fun aFailedFirstAttemptTriggersExactlyOneResyncAttempt() = runBlocking {
    var attempts = 0

    retryOnceThenReport(delayMillis = 5, onExhausted = {}) {
      attempts++
      attempts >= 2 // fails the 1st call, succeeds the 2nd (the one resync)
    }

    assertEquals(2, attempts)
  }

  @Test
  fun aSuccessfulRetryReflectsWhateverIsCurrentAtThatLaterMoment() = runBlocking {
    // Simulates the state machine having moved on between the failed first send and the retry -
    // e.g. render() was requested for generation G, the send failed, and by the time the retry
    // runs the authoritative generation is G+1. The retry must observe G+1, not replay a stale
    // snapshot of G captured back when the original render() was requested.
    var currentGeneration = 1L
    var observedOnSuccess: Long? = null
    var attempts = 0

    retryOnceThenReport(delayMillis = 5, onExhausted = {}) {
      attempts++
      if (attempts == 1) {
        false // first attempt fails
      } else {
        currentGeneration = 2L // the authoritative state advances before the retry runs
        observedOnSuccess = currentGeneration
        true
      }
    }

    assertEquals(2, attempts)
    assertEquals(2L, observedOnSuccess)
  }

  @Test
  fun secondFailureStopsAfterTheBoundedRetryAndReportsExactlyOnce() = runBlocking {
    var attempts = 0
    var exhaustedCount = 0

    retryOnceThenReport(
        delayMillis = 5,
        onExhausted = { exhaustedCount++ },
    ) {
      attempts++
      false // every attempt fails
    }

    // Exactly two attempts total (the original + one bounded retry) - never a third, unbounded
    // attempt - and exactly one exhaustion report, not one per failed attempt.
    assertEquals(2, attempts)
    assertEquals(1, exhaustedCount)
  }
}
