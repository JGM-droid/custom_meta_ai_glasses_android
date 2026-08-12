package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCaptureWiringContractTest {
  @Test
  fun displayCaptureActionRoutesThroughExistingCaptureMethod() {
    val streamViewModelPath =
        Paths.get(
            "src",
            "main",
            "java",
            "com",
            "meta",
            "wearable",
            "dat",
            "externalsampleapps",
            "cameraaccess",
            "stream",
            "StreamViewModel.kt",
        )
    val streamViewModel =
        String(Files.readAllBytes(streamViewModelPath), StandardCharsets.UTF_8)

    assertTrue(streamViewModel.contains("fun onDisplayCaptureAction()"))
    assertTrue(streamViewModel.contains("prepareForAdditionalInvestigationCapture()"))
    assertTrue(streamViewModel.contains("capturePhoto()"))
    assertTrue(streamViewModel.contains("button("))
    assertTrue(streamViewModel.contains("onClick = { onDisplayCaptureAction() }"))
  }

  @Test
  fun phoneCaptureFallbackRemainsWired() {
    val streamScreenPath =
        Paths.get(
            "src",
            "main",
            "java",
            "com",
            "meta",
            "wearable",
            "dat",
            "externalsampleapps",
            "cameraaccess",
            "ui",
            "StreamScreen.kt",
        )
    val streamScreen = String(Files.readAllBytes(streamScreenPath), StandardCharsets.UTF_8)

    assertTrue(streamScreen.contains("CaptureButton("))
    assertTrue(streamScreen.contains("onClick = { streamViewModel.capturePhoto() }"))
    assertTrue(streamScreen.contains("updateDisplayCaptureControl("))
  }
}
