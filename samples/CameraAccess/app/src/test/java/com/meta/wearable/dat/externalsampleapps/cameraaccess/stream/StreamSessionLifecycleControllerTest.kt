package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionLifecycleControllerTest {
  @Test
  fun twoImmediateStartRequestsCreateAtMostOneSession() {
    val controller = StreamSessionLifecycleController()

    val (firstDecision, firstToken) =
        controller.requestStart(hasSessionReference = false, hasActivePipeline = false)
    val (secondDecision, secondToken) =
        controller.requestStart(hasSessionReference = false, hasActivePipeline = false)

    assertEquals(StreamSessionLifecycleController.StartDecision.CREATE_SESSION, firstDecision)
    assertTrue(firstToken != null)
    assertEquals(StreamSessionLifecycleController.StartDecision.IGNORE, secondDecision)
    assertTrue(secondToken == null)
  }

  @Test
  fun startRequestWhileAlreadyStreamingDoesNotCreateAnotherSession() {
    val controller = StreamSessionLifecycleController()
    val (_, token) = controller.requestStart(hasSessionReference = false, hasActivePipeline = false)
    val startToken = requireNotNull(token)
    controller.onSessionCreated(startToken)
    controller.onStartAttached(startToken)

    val (decision, nextToken) =
        controller.requestStart(hasSessionReference = true, hasActivePipeline = true)

    assertEquals(StreamSessionLifecycleController.StartDecision.IGNORE, decision)
    assertTrue(nextToken == null)
  }

  @Test
  fun repeatedStopRequestsAreSafe() {
    val controller = StreamSessionLifecycleController()
    val (_, token) = controller.requestStart(hasSessionReference = false, hasActivePipeline = false)
    val startToken = requireNotNull(token)
    controller.onSessionCreated(startToken)
    controller.onStartAttached(startToken)

    val firstStop = controller.requestStop(hasSessionReference = true, hasStreamReference = true)
    val secondStop = controller.requestStop(hasSessionReference = false, hasStreamReference = false)

    assertTrue(firstStop)
    assertFalse(secondStop)

    controller.onStopCompleted()
    val thirdStop = controller.requestStop(hasSessionReference = false, hasStreamReference = false)
    assertFalse(thirdStop)
  }

  @Test
  fun stopThenStartAllowsCleanNewSessionCreate() {
    val controller = StreamSessionLifecycleController()
    val (_, token1) = controller.requestStart(hasSessionReference = false, hasActivePipeline = false)
    val firstStartToken = requireNotNull(token1)
    controller.onSessionCreated(firstStartToken)
    controller.onStartAttached(firstStartToken)

    assertTrue(controller.requestStop(hasSessionReference = true, hasStreamReference = true))
    controller.onStopCompleted()

    val (decision2, token2) =
        controller.requestStart(hasSessionReference = false, hasActivePipeline = false)

    assertEquals(StreamSessionLifecycleController.StartDecision.CREATE_SESSION, decision2)
    assertTrue(token2 != null)
    assertTrue(token2 != firstStartToken)
  }

  @Test
  fun failedCreateDoesNotLeaveControllerLockedInStarting() {
    val controller = StreamSessionLifecycleController()
    val (_, firstToken) =
        controller.requestStart(hasSessionReference = false, hasActivePipeline = false)
    val startToken = requireNotNull(firstToken)

    controller.onCreateFailed(startToken)

    val (decision2, secondToken) =
        controller.requestStart(hasSessionReference = false, hasActivePipeline = false)

    assertEquals(StreamSessionLifecycleController.StartDecision.CREATE_SESSION, decision2)
    assertTrue(secondToken != null)
  }
}
