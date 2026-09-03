/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Focused coverage for the bounded initial-load retry (see docs/ROADMAP.md's "DAT 0.8 Capture
 * Capability Gate" status update and ProjectContinuityHudController.load()). withOneRetry is
 * extracted as a small, pure, directly-testable unit specifically because
 * ProjectContinuityHudController itself needs a live DAT Display/DeviceSession to render into and
 * isn't practically unit-testable at this level - see ProjectContinuityHudStateTest.kt for the
 * same reasoning applied to the state machine.
 */
class ProjectContinuityHudRetryTest {
  @Test
  fun succeedsOnFirstAttemptWithoutRetrying() = runBlocking {
    var calls = 0

    val result = withOneRetry(delayMillis = 5) {
      calls++
      "overview"
    }

    assertEquals("overview", result)
    assertEquals(1, calls)
  }

  @Test
  fun retriesExactlyOnceAfterATransientFailure() = runBlocking {
    var calls = 0

    val result =
        withOneRetry(delayMillis = 5) {
          calls++
          if (calls == 1) throw IOException("transient network blip") else "overview"
        }

    assertEquals("overview", result)
    assertEquals(2, calls)
  }

  @Test
  fun propagatesTheSecondFailureRatherThanRetryingAgain() = runBlocking {
    var calls = 0

    try {
      withOneRetry(delayMillis = 5) {
        calls++
        throw IOException("still unreachable")
      }
      fail("Expected the second failure to propagate")
    } catch (error: IOException) {
      assertEquals("still unreachable", error.message)
    }

    // Exactly two attempts total - never a third, unbounded retry.
    assertEquals(2, calls)
  }
}
