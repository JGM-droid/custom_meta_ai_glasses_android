package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationBackendConfigTest {
  @Test
  fun emulatorAllowsEmulatorHostAlias() {
    val validation =
        InvestigationBackendConfig.validateSubmissionBaseUrl(
            baseUrl = "http://10.0.2.2:8001",
            isEmulator = true,
        )

    assertTrue(validation.isAllowed)
  }

  @Test
  fun physicalDeviceRejectsEmulatorHostAlias() {
    val validation =
        InvestigationBackendConfig.validateSubmissionBaseUrl(
            baseUrl = "http://10.0.2.2:8001",
            isEmulator = false,
        )

    assertFalse(validation.isAllowed)
    assertEquals("backend_unreachable_configuration", validation.errorCategory)
  }

  @Test
  fun physicalDeviceRejectsLocalhost() {
    val validation =
        InvestigationBackendConfig.validateSubmissionBaseUrl(
            baseUrl = "http://localhost:8001",
            isEmulator = false,
        )

    assertFalse(validation.isAllowed)
    assertEquals("backend_unreachable_configuration", validation.errorCategory)
  }

  @Test
  fun physicalDeviceAllowsHttpsTunnel() {
    val validation =
        InvestigationBackendConfig.validateSubmissionBaseUrl(
            baseUrl = "https://journalists-unwrap-reasonable-pixel.trycloudflare.com",
            isEmulator = false,
        )

    assertTrue(validation.isAllowed)
  }

  @Test
  fun connectivityCheckAndSubmissionShareSameValidationResult() {
    val physicalDeviceUrl = "http://10.0.2.2:8001"
    val tunnelUrl = "https://journalists-unwrap-reasonable-pixel.trycloudflare.com"

    assertEquals(
        InvestigationBackendConfig.validateConnectivityBaseUrl(physicalDeviceUrl, isEmulator = false),
        InvestigationBackendConfig.validateSubmissionBaseUrl(physicalDeviceUrl, isEmulator = false),
    )
    assertEquals(
        InvestigationBackendConfig.validateConnectivityBaseUrl(tunnelUrl, isEmulator = false),
        InvestigationBackendConfig.validateSubmissionBaseUrl(tunnelUrl, isEmulator = false),
    )
    assertEquals(
        InvestigationBackendConfig.validateConnectivityBaseUrl(physicalDeviceUrl, isEmulator = true),
        InvestigationBackendConfig.validateSubmissionBaseUrl(physicalDeviceUrl, isEmulator = true),
    )
  }
}