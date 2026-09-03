/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.types.DisplayError
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A real DAT SDK [Display] cannot be constructed or mocked outside a physical device connection
 * (see [ProjectContinuityHudController.attachDisplayForTesting]'s doc) - this is the fake that
 * seam is for. It genuinely invokes the `content` lambda against a real
 * [com.meta.wearable.dat.display.views.ContentScope] on every [sendContent] call, so a crash in
 * the real render logic (a null field access, an unhandled branch) still fails tests loudly - it
 * just cannot introspect what UI tree that lambda built, since `ContentScope`/`FlexBoxScope`'s own
 * tree-reading accessors are internal to the DAT SDK's module, not ours. Tests instead assert on
 * [ProjectContinuityHudStateMachine]'s own state (the thing that determines what would be shown)
 * and on this fake's own call/failure bookkeeping (the thing that proves rendering was attempted
 * and how many times).
 */
internal class FakeDisplay(initialState: DisplayState = DisplayState.STARTED) : Display {
  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<DisplayState> = _state.asStateFlow()

  var sendContentCallCount = 0
    private set
  var stopCallCount = 0
    private set

  // Queued pass/fail results for upcoming sendContent() calls, consumed in order. Empty queue ->
  // succeed (the common case) - only scenarios that specifically need a Display failure/timeout
  // (the render-retry recovery scenario) ever need to queue anything.
  private val scriptedResults = ArrayDeque<Boolean>()

  fun scriptNextSendContentResult(succeeds: Boolean) {
    scriptedResults.addLast(succeeds)
  }

  fun setState(newState: DisplayState) {
    _state.value = newState
  }

  override fun stop() {
    stopCallCount++
    _state.value = DisplayState.STOPPED
  }

  override fun close() = stop()

  override suspend fun sendContent(content: ContentScope.() -> Unit): DatResult<Boolean, DisplayError> {
    sendContentCallCount++
    // Genuinely runs the real render logic - see class doc.
    ContentScope().content()
    val succeeds = if (scriptedResults.isEmpty()) true else scriptedResults.removeFirst()
    return if (succeeds) {
      DatResult.success(true)
    } else {
      DatResult.failure(DisplayError.RENDERING_FAILED, null)
    }
  }

  override suspend fun clearDisplay(): DatResult<Boolean, DisplayError> = DatResult.success(true)
}
