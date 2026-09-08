/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused coverage for the HUD stale-capture-state fix (Bug 1): a Capture/Use cycle completed on
 * the glasses HUD stages a request ([StreamViewModel.hudCaptureAcceptRequest]) that only a live
 * StreamScreen composable's LaunchedEffect ever consumed - if that never happens (StreamScreen not
 * composed, disposed mid-effect, or any other reason), the request sat forever and
 * ProjectHudCaptureStatus.AwaitingConfirmation never resolved, so ProjectContinuityHudController's
 * renderState() kept re-showing the stale "Photo captured - use this image?" screen on every
 * subsequent render (Display reattach, Refresh, reconnect) with no way out.
 *
 * [resolveIfStillPending] is extracted as a small, pure, directly-testable unit for the same
 * reason ProjectContinuityHudController.withOneRetry is (see ProjectContinuityHudRetryTest.kt) -
 * StreamViewModel itself needs a live DAT DeviceSession and isn't practically unit-testable here.
 */
class StreamViewModelHudUseTimeoutTest {

  @Test
  fun firesTimeoutWhenTheRequestIsNeverConsumed() = runBlocking {
    var timedOut = false
    val request = "pending-evidence"
    var current: String? = request // never cleared - simulates no live StreamScreen consumer

    resolveIfStillPending(
        delayMillis = 5,
        request = request,
        current = { current },
        onTimedOut = { timedOut = true },
    )

    assertTrue("A never-consumed request must eventually resolve, not stay stuck forever", timedOut)
  }

  @Test
  fun doesNotFireWhenALiveConsumerClearsTheRequestInTime() = runBlocking {
    var timedOut = false
    val request = "pending-evidence"
    var current: String? = request

    // Simulates StreamScreen's LaunchedEffect(hudCaptureAcceptRequest) completing the hand-off
    // (a purely local, synchronous operation per its own doc) well within the timeout window.
    current = null

    resolveIfStillPending(
        delayMillis = 5,
        request = request,
        current = { current },
        onTimedOut = { timedOut = true },
    )

    assertFalse("A request a live consumer already resolved must never be re-failed", timedOut)
  }

  @Test
  fun doesNotFireForADifferentLaterRequestReplacingTheOriginal() = runBlocking {
    var timedOut = false
    val originalRequest = "first-photo"
    var current: String? = "second-photo" // a NEW capture superseded the original before timeout

    resolveIfStillPending(
        delayMillis = 5,
        request = originalRequest,
        current = { current },
        onTimedOut = { timedOut = true },
    )

    // Identity comparison, not null-check: a genuinely different pending request must not be
    // mistaken for the stale one this particular timeout was guarding.
    assertFalse(timedOut)
    assertEquals("second-photo", current)
  }
}
