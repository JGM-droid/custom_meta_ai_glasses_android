package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

internal data class InvestigationDisplayCaptureModel(
    val statusText: String,
    val nextViewIndex: Int?,
    val showCaptureAction: Boolean,
)

internal object InvestigationDisplayCaptureModelFactory {
  fun create(
      investigationActive: Boolean,
      activeCaptureCount: Int,
      hasCaptureCapacity: Boolean,
  ): InvestigationDisplayCaptureModel? {
    if (!investigationActive) {
      return null
    }

    val normalizedCount = activeCaptureCount.coerceIn(0, 3)
    if (!hasCaptureCapacity || normalizedCount >= 3) {
      return InvestigationDisplayCaptureModel(
          statusText = "3 views captured. Ready to analyze.",
          nextViewIndex = null,
          showCaptureAction = false,
      )
    }

    val nextView = normalizedCount + 1
    val statusText =
        if (normalizedCount == 0) {
          "Capture View 1"
        } else {
          "View $normalizedCount captured. Capture View $nextView"
        }

    return InvestigationDisplayCaptureModel(
        statusText = statusText,
        nextViewIndex = nextView,
        showCaptureAction = true,
    )
  }
}
