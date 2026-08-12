package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationDisplayCaptureModelTest {
  @Test
  fun inactiveInvestigationReturnsNoDisplayModel() {
    val model =
        InvestigationDisplayCaptureModelFactory.create(
            investigationActive = false,
            activeCaptureCount = 0,
            hasCaptureCapacity = true,
        )

    assertNull(model)
  }

  @Test
  fun zeroCapturesTargetsView1() {
    val model =
        InvestigationDisplayCaptureModelFactory.create(
            investigationActive = true,
            activeCaptureCount = 0,
            hasCaptureCapacity = true,
        )

    requireNotNull(model)
    assertEquals("Capture View 1", model.statusText)
    assertEquals(1, model.nextViewIndex)
    assertTrue(model.showCaptureAction)
  }

  @Test
  fun oneCaptureTargetsView2() {
    val model =
        InvestigationDisplayCaptureModelFactory.create(
            investigationActive = true,
            activeCaptureCount = 1,
            hasCaptureCapacity = true,
        )

    requireNotNull(model)
    assertEquals("View 1 captured. Capture View 2", model.statusText)
    assertEquals(2, model.nextViewIndex)
    assertTrue(model.showCaptureAction)
  }

  @Test
  fun twoCapturesTargetsView3() {
    val model =
        InvestigationDisplayCaptureModelFactory.create(
            investigationActive = true,
            activeCaptureCount = 2,
            hasCaptureCapacity = true,
        )

    requireNotNull(model)
    assertEquals("View 2 captured. Capture View 3", model.statusText)
    assertEquals(3, model.nextViewIndex)
    assertTrue(model.showCaptureAction)
  }

  @Test
  fun threeCapturesDisablesCaptureAction() {
    val model =
        InvestigationDisplayCaptureModelFactory.create(
            investigationActive = true,
            activeCaptureCount = 3,
            hasCaptureCapacity = false,
        )

    requireNotNull(model)
    assertEquals("3 views captured. Ready to analyze.", model.statusText)
    assertNull(model.nextViewIndex)
    assertFalse(model.showCaptureAction)
  }
}
