/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectContinuityHudContractTest {
  private val root = File("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess")

  @Test
  fun controllerIsReadOnlyAndDoesNotOwnDeviceSession() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    assertTrue(controller.contains("repository.getProjectOverview(request.projectId)"))
    assertTrue(controller.contains("fun attachTo(session: DeviceSession)"))
    assertFalse(controller.contains("Wearables.createSession"))
    assertFalse(controller.contains("setActiveProject("))
    assertFalse(controller.contains("applyCheckpointProposal("))
    assertFalse(controller.contains("rejectCheckpointProposal("))
    assertFalse(controller.contains("capturePhoto("))
  }

  @Test
  fun cameraSessionRemainsTheSingleSessionOwner() {
    val stream = File(root, "stream/StreamViewModel.kt").readText()
    assertTrue(stream.contains("session?.let(projectHudController::attachTo)"))
    assertTrue(stream.contains("Wearables.createSession(deviceSelector)"))
    assertFalse(stream.contains("DisplayCapture"))
    assertFalse(stream.contains("captureFromDisplay"))
    assertFalse(stream.contains("CapabilityHandoff"))
    val detachIndex = stream.indexOf("projectHudController.detach()")
    val selectionResetIndex = stream.indexOf("projectHudProjectId = null", startIndex = detachIndex)
    assertTrue(detachIndex >= 0)
    assertTrue(selectionResetIndex > detachIndex)
  }

  @Test
  fun phoneHandoffCarriesProjectAndReviewDestination() {
    val state = File(root, "display/ProjectContinuityHudState.kt").readText()
    val appRoot = File(root, "ui/AppRoot.kt").readText()
    assertTrue(state.contains("val projectId: String"))
    assertTrue(state.contains("PROJECT_REVIEW"))
    assertTrue(appRoot.contains("focusReview = needsReview"))
  }

  @Test
  fun hudContainsNoForbiddenTechnicalOrCaptureSpikePresentation() {
    val controller = File(root, "display/ProjectContinuityHudController.kt").readText()
    listOf(
            "interaction_id",
            "Context Pack",
            "checkpoint revision",
            "source_type",
            "API status",
            "Band capture",
            "capturePhoto",
        )
        .forEach { forbidden -> assertFalse("forbidden HUD text: $forbidden", controller.contains(forbidden)) }
  }
}
