package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLifecycleStopObserverTest {
  @Test
  fun onStopEventDelegatesToStopCallback() {
    var stopCalls = 0
    val observer = createStreamLifecycleStopObserver { stopCalls += 1 }
    val owner = TestLifecycleOwner()

    observer.onStateChanged(owner, Lifecycle.Event.ON_START)
    observer.onStateChanged(owner, Lifecycle.Event.ON_STOP)

    assertEquals(1, stopCalls)
  }

  private class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
      get() = registry
  }
}
