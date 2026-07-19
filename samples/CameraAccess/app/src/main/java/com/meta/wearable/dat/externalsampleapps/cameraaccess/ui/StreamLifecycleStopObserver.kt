package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal fun createStreamLifecycleStopObserver(
    onBackgroundStopRequested: () -> Unit
): LifecycleEventObserver {
  return LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_STOP) {
      onBackgroundStopRequested()
    }
  }
}