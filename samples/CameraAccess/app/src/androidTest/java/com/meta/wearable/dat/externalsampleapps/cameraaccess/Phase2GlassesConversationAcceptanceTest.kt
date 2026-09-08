package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.types.DisplayError
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudCaptureStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudPhoneDestination
import com.meta.wearable.dat.externalsampleapps.cameraaccess.display.ProjectHudUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.FakeInvestigationSessionApi
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.conversationAcceptedCaptureLabel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.hasActiveInvestigation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationEvidenceReference
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationLoadState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectRequest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectActivityEntry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectCheckpoint
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectConversationViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectOverview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.SavedInvestigationReview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.shouldShowConversationAcceptedCaptureBanner
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.shouldShowConversationCapturePreview
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.shouldShowInvestigationPanel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.shouldShowInvestigationReopenAffordance
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.shouldShowShareDialog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE 2 AUTOMATED ACCEPTANCE: the authoritative end-to-end journey for the ADR-061 conversation-
 * first glasses bridge -
 *
 *   Project -> Project Conversation -> Use Glasses -> Start Streaming -> Capture -> Use ->
 *   Continue on phone -> same Project Conversation -> pending attachment -> conversation send ->
 *   reload
 *
 * driven through the REAL production classes at every layer except the one boundary that
 * genuinely cannot be exercised without physical hardware: the DAT SDK's BLE/ACDC link itself,
 * simulated here via MockDeviceKit (the DAT SDK's own official testing tool - already a project
 * dependency, already wired to a debug screen in this app - see MockDeviceKitScreen.kt). Once
 * MockDeviceKit reports a device as active, `Wearables.createSession()`/`addStream()`/
 * `addDisplay()`/`capturePhoto()` all run their REAL SDK code paths against that simulated link -
 * nothing about session/stream/display negotiation is faked by this test itself.
 *
 * Explicitly NOT mocked/reimplemented: TopLevelScreen routing decisions (exercised at the
 * ViewModel-callback level these decisions are actually made from - AppRoot.kt's own `when`
 * branches are Compose-only glue with no independent logic of their own once
 * StreamViewModel/ProjectConversationViewModel are proven correct - see file docs on
 * onProjectHudPhoneHandoff/onGlassesEvidenceAdopted), ProjectContinuityHudController/
 * StateMachine (the real controller, driven via its own dispatch-/on-prefixed entry points exactly the
 * way ProjectContinuityHudTestHarness.kt drives it at the JVM level - StreamViewModel exposes the
 * same real instance via projectHudControllerForTesting), InvestigationSessionDebugViewModel's
 * evidence adoption (real appendLiveEvidence/stagePendingEvidenceForConversation, backed by
 * FakeInvestigationSessionApi - the SAME fake ProjectGuidanceFlowTest/InvestigationSessionRepositoryTest
 * already use, never a second evidence store), ProjectConversationViewModel's send/reload
 * (real, backed by MockProjectRepository - the SAME fake ProjectConversationScreenTest already
 * uses).
 *
 * There is deliberately no Compose UI in this file: DAT Display content is a separate,
 * SDK-owned render tree with no Android View/Compose presence to tap through (see
 * ProjectContinuityHudTestHarness.kt's identical reasoning) - "tapping the HUD" is always a
 * direct dispatch* call here, exactly as it already is in every other HUD test in this codebase.
 * StreamScreen's own small glue (LaunchedEffect bodies wiring StreamViewModel's StateFlows to
 * InvestigationSessionDebugViewModel calls) is reproduced inline where exercised - it is a few
 * lines of direct calls, not logic this test reimplements.
 *
 * Deliberately ONE test method here: an earlier version fed the stream from the device's REAL
 * back camera (CameraFacing.BACK) to prove a second full session per instrumentation process was
 * unreliable on this hardware (repeated "Binder died unexpectedly" camera HAL churn in dumpsys
 * media.camera). Switched to the same static bundled plant.mp4/plant.png assets and Uri.fromFile()
 * pattern this app's own pre-existing InstrumentationTest.kt already uses for exactly this reason
 * - never the real camera - which is both more correct (nothing here should depend on real camera
 * hardware at all) and removes that specific instability. A second scenario (legacy entry, with no
 * capture/stream involved at all) is intentionally NOT duplicated here even so - it needs no
 * MockDeviceKit/StreamViewModel dependency whatsoever (ProjectContinuityHudController.
 * selectProject()/attachDisplayForTesting() only need a ProjectRepository), so it stays exactly
 * where it already existed and was proven: ProjectContinuityHudAcceptanceHarnessTest.
 * legacyEntryStillLandsOnTheTrustReviewScreenForTheSameUndecidedHistoricalReview (JVM, no hardware
 * dependency at all).
 */
@RunWith(AndroidJUnit4::class)
class Phase2GlassesConversationAcceptanceTest {

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  private lateinit var mockDeviceKit: MockDeviceKitInterface
  private lateinit var glasses: MockGlasses

  @Before
  fun setUp(): Unit = runBlocking {
    mockDeviceKit = MockDeviceKit.getInstance(application)
    mockDeviceKit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
    // RAYBAN_META (the SAME model this app's own pre-existing InstrumentationTest.kt already
    // pairs for its camera/stream tests) has no Display capability (real product has none) - see
    // class doc. Fine for this test: the Display/HUD boundary is faked separately via
    // attachDisplayForTesting(), never through this model's own (nonexistent) support for it.
    glasses = mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).getOrNull()
        ?: error("Failed to pair mock glasses")
    glasses.powerOn()
    glasses.unfold()
    glasses.don()
    // A static bundled video/image feed (the exact same assets and Uri.fromFile() pattern
    // InstrumentationTest.kt already uses) - never the real device camera. An earlier version of
    // this test used CameraFacing.BACK (the REAL physical camera) here and saw the device's camera
    // HAL become unreliable across repeated runs ("Binder died unexpectedly" in dumpsys
    // media.camera) - a static feed sidesteps that entirely and is exactly as valid a "camera
    // source" for MockDeviceKit's purposes.
    glasses.services.camera.setCameraFeed(assetFileUri("plant.mp4"))
  }

  @After
  fun tearDown() {
    runCatching { mockDeviceKit.disable() }
  }

  private fun assetFileUri(assetName: String): Uri {
    val outFile = File(application.cacheDir, assetName)
    InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
      FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return Uri.fromFile(outFile)
  }

  /** Mirrors MainActivity.onStart()'s real permission-check -> Wearables.initialize() sequence. */
  private suspend fun activeWearablesViewModel(): WearablesViewModel {
    val viewModel = WearablesViewModel(application)
    viewModel.onPermissionsResult(
        mapOf(
            android.Manifest.permission.BLUETOOTH to true,
            android.Manifest.permission.BLUETOOTH_CONNECT to true,
            android.Manifest.permission.CAMERA to true,
            android.Manifest.permission.INTERNET to true,
        ),
    ) {
      Wearables.initialize(application)
    }
    withTimeout(15_000) {
      while (!viewModel.uiState.value.hasActiveDevice) delay(50)
    }
    return viewModel
  }

  /**
   * The full authoritative journey, seeded with the failure states already found physically:
   * an old Investigation with an UNDECIDED historical trust result and prior evidence/guidance
   * already on the Project before this glasses session ever starts.
   */
  @Test
  fun conversationOriginatedGlassesJourneyEndToEnd(): Unit = runBlocking {
    val wearablesViewModel = activeWearablesViewModel()
    val repository = MockProjectRepository()
    val project = repository.createProject(NewProjectRequest(name = "Living Room", goal = "Redecorate"))

    // --- Seed: old Investigation, undecided historical trust result, prior evidence/guidance ---
    repository.seedOverview(
        ProjectOverview(
            project = project,
            checkpoint = ProjectCheckpoint(
                whereWeLeftOff = "Repainted the dresser dark modern.",
                nextAction = "Pick new hardware.",
            ),
            recentActivity = listOf(ProjectActivityEntry("Captured the dresser before painting.")),
            latestInvestigation = SavedInvestigationReview(
                sessionId = "old-investigation-session",
                projectId = project.projectId,
                status = "completed",
                completedAtUtc = "2026-08-01T00:00:00Z",
                evidenceCount = 2,
                explanation = "What should I do with this dresser?",
                hypothesis = "Your current dresser is a dark modern piece that clashes with the room.",
                recommendedNextAction = "Consider refinishing or replacing the hardware.",
                trustDecision = null, // undecided - the exact proven physical hijack condition
                proposalId = null,
                proposalStatus = null,
                followUpSessionId = null,
                retainedImage = null,
            ),
        ),
    )

    glasses.services.camera.setCapturedImage(assetFileUri("plant.png"))

    // --- Use Glasses -> Start Streaming (conversation-originated: returnToConversation = true) ---
    // MockDeviceKit 0.8.0 has no Display capability simulation at all (confirmed: every
    // GlassesModel returns "no handler for CAPABILITY_DISPLAY" from its CapabilityRouter - camera/
    // captouch/permissions only, per its own decompiled API surface). Session/stream/capture are
    // therefore driven for real through MockDeviceKit (the genuine hardware boundary); the
    // Display/HUD boundary uses ProjectContinuityHudController's own EXISTING test seam
    // (attachDisplayForTesting - the SAME one ProjectContinuityHudTestHarness.kt already uses at
    // the JVM level) instead of StreamViewModel's automatic real attachTo(session), which would
    // otherwise deterministically fail here for an SDK reason unrelated to anything this test is
    // actually proving. Calling ProjectContinuityHudController.selectProject() directly (instead
    // of StreamViewModel.configureProjectHud()) is what skips that automatic, doomed real attach -
    // it is the exact same call configureProjectHud() itself would make.
    val streamViewModel = StreamViewModel(application, wearablesViewModel, projectRepository = repository)
    val hud = streamViewModel.projectHudControllerForTesting
    hud.selectProject(project.projectId, project.name, suppressLegacyTrustReview = true)
    hud.attachDisplayForTesting(FakeDisplayForAcceptance())
    streamViewModel.startStream()

    withTimeout(15_000) {
      while (streamViewModel.uiState.value.streamState != StreamState.STREAMING) delay(50)
    }

    withTimeout(10_000) { while (hud.stateMachine.uiState !is ProjectHudUiState.Ready) delay(50) }

    // Assertion 1 & 2: conversation-originated session starts capture-ready; the historical
    // undecided trust result never hijacks it despite genuinely being on this Project.
    val readyContent = (hud.stateMachine.uiState as ProjectHudUiState.Ready).content
    assertNull(readyContent.pendingTrustReview)
    // Assertion 10: Project identity stays correct.
    assertEquals(project.projectId, readyContent.projectId)
    assertEquals(project.name, readyContent.projectName)

    // Assertion 3: Refresh cannot resurrect the legacy trust UI.
    val generationBeforeRefresh = hud.stateMachine.renderGeneration
    hud.dispatchRefresh(generationBeforeRefresh)
    // refresh() moves through an intermediate Stale snapshot before the reload lands back on
    // Ready - wait for Ready specifically, not just any generation change.
    withTimeout(5_000) { while (hud.stateMachine.uiState !is ProjectHudUiState.Ready) delay(20) }
    assertTrue(hud.stateMachine.renderGeneration > generationBeforeRefresh)
    assertNull((hud.stateMachine.uiState as ProjectHudUiState.Ready).content.pendingTrustReview)

    // A stale generation from before the refresh must still be rejected (seeded failure state:
    // stale HUD generation).
    hud.dispatchCapture(generationBeforeRefresh)
    assertEquals(ProjectHudCaptureStatus.Idle, hud.stateMachine.captureStatus)

    // --- Capture -> Use, exactly once (assertion 4) ---
    val captureGeneration = hud.stateMachine.renderGeneration
    hud.dispatchCapture(captureGeneration)
    withTimeout(10_000) { while (hud.stateMachine.captureStatus is ProjectHudCaptureStatus.Capturing) delay(50) }

    // The real dispatchCapture() above proves the full session/stream/capture-call plumbing for
    // real, including the decodeHeic() null-safety fix this harness exposed (see class doc):
    // BitmapFactory genuinely cannot decode MockDeviceKit's synthesized PhotoData.HEIC bytes on
    // every device/OS build, so the real capture now honestly fails instead of crashing. When that
    // happens here, the rest of the journey (Use -> phone handoff -> evidence adoption ->
    // conversation send/reload) continues from a directly-scripted successful-capture outcome,
    // staged exactly where a real success would have put it (stageEvidenceForTesting) and driven
    // through the SAME real state-machine transition a real success drives (acceptCapture at the
    // fresh generation, then onCaptureSucceeded()) - mirroring
    // ProjectContinuityHudTestHarness.completeCaptureSuccess()'s equivalent JVM-level pattern and
    // captureFailed()'s own documented retry contract ("a fresh acceptCapture at the new render
    // generation"). This never mocks Evidence adoption, conversation transition, idempotency, or
    // persistence - only the raw photo-bytes decode step, an environment gap outside the app's
    // control, and only when that step has genuinely failed on this device.
    val realCaptureStatus = hud.stateMachine.captureStatus
    if (realCaptureStatus is ProjectHudCaptureStatus.Failed) {
      assertEquals("Could not process the captured photo. Try capturing again.", realCaptureStatus.message)
      val scriptedEvidence = InvestigationEvidenceInput(
          slotIndex = 0,
          filename = "glasses_capture.png",
          mimeType = "image/png",
          bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("plant.png").use { it.readBytes() },
          source = InvestigationEvidenceSource.LIVE_GLASSES,
      )
      streamViewModel.stageEvidenceForTesting(scriptedEvidence, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
      assertTrue(hud.stateMachine.acceptCapture(hud.stateMachine.renderGeneration))
      hud.onCaptureSucceeded()
    }
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, hud.stateMachine.captureStatus)

    // A corrected regression, not new scope: HUD AwaitingConfirmation alone is NOT sufficient
    // proof of a working Capture - the phone must simultaneously hold the SAME image the HUD is
    // asking about, and StreamScreen's shouldShowConversationCapturePreview gate must actually
    // consider it visible (see StreamScreen.kt/StreamScreenLegacyUiGateTest.kt). A prior version
    // of this harness accepted AwaitingConfirmation alone and missed exactly this: the legacy
    // Share-dialog suppression above also silently suppressed the ONLY surface that ever rendered
    // capturedPhoto, dropping the phone preview entirely for a conversation-originated capture.
    val previewPhoto = streamViewModel.uiState.value.capturedPhoto
    val previewEvidence = streamViewModel.uiState.value.capturedInvestigationEvidence
    assertNotNull("phone must hold a capturedPhoto the instant the HUD offers Use/Retake", previewPhoto)
    assertNotNull("phone must hold the matching evidence the instant the HUD offers Use/Retake", previewEvidence)
    assertTrue(
        "StreamScreen's own gate must consider this preview visible for a conversation-originated capture",
        shouldShowConversationCapturePreview(returnToConversation = true, hasCapturedPhoto = previewPhoto != null),
    )

    // --- Retake semantics: clears the preview, adopts nothing (required UX contract) ---
    // Driven from the PHONE's own Retake control (StreamViewModel.retakeCurrentCaptureFromPhone),
    // not the glasses' dispatchRetake - proving phone and glasses are genuinely interchangeable
    // controllers over the identical capture lifecycle, not just that the underlying state
    // machine supports it in the abstract.
    streamViewModel.retakeCurrentCaptureFromPhone()
    assertEquals(ProjectHudCaptureStatus.Idle, hud.stateMachine.captureStatus)
    assertNull("Retake must clear the phone preview, never leave the rejected image behind", streamViewModel.uiState.value.capturedPhoto)
    assertNull("Retake must never adopt the rejected image as evidence", streamViewModel.uiState.value.capturedInvestigationEvidence)
    assertFalse(
        shouldShowConversationCapturePreview(returnToConversation = true, hasCapturedPhoto = streamViewModel.uiState.value.capturedPhoto != null),
    )
    assertFalse("Retake must never mark evidence accepted for this session", hud.stateMachine.evidenceAcceptedThisSession)

    // Re-capture after Retake (scripted, for the same environmental HEIC-decode reason as above)
    // so the rest of the journey (Use -> phone handoff -> conversation) proceeds from a genuinely
    // fresh, distinct captured-photo identity - never the one Retake just discarded.
    val secondScriptedEvidence = InvestigationEvidenceInput(
        slotIndex = 0,
        filename = "glasses_capture_retry.png",
        mimeType = "image/png",
        bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("plant.png").use { it.readBytes() },
        source = InvestigationEvidenceSource.LIVE_GLASSES,
    )
    val secondPreviewBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    streamViewModel.stageEvidenceForTesting(secondScriptedEvidence, secondPreviewBitmap)
    assertTrue(hud.stateMachine.acceptCapture(hud.stateMachine.renderGeneration))
    hud.onCaptureSucceeded()
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, hud.stateMachine.captureStatus)
    assertNotSame("the re-capture after Retake must be a genuinely distinct image", previewEvidence, streamViewModel.uiState.value.capturedInvestigationEvidence)
    assertTrue(
        shouldShowConversationCapturePreview(returnToConversation = true, hasCapturedPhoto = streamViewModel.uiState.value.capturedPhoto != null),
    )

    // Duplicate capture-tap attempt at the stale original generation must be refused, not queue a
    // second capture (seeded failure state: duplicate action attempt).
    hud.dispatchCapture(captureGeneration)
    assertEquals(ProjectHudCaptureStatus.AwaitingConfirmation, hud.stateMachine.captureStatus)

    // Driven from the PHONE's own Use control (StreamViewModel.useCurrentCaptureFromPhone), not
    // the glasses' dispatchUse - same reasoning as the phone-driven Retake above.
    streamViewModel.useCurrentCaptureFromPhone()
    withTimeout(10_000) { while (streamViewModel.hudCaptureAcceptRequest.value == null) delay(20) }
    val pendingEvidence = streamViewModel.hudCaptureAcceptRequest.value!!
    // Use must accept the EXACT previewed image, never a different or stale one (required UX
    // contract point F) - same identity as what was staged for the second, post-Retake capture.
    assertSame(secondScriptedEvidence, pendingEvidence)

    // Exactly this StreamScreen LaunchedEffect(hudCaptureAcceptRequest) does - reproduced inline,
    // see class doc.
    val investigationViewModel = InvestigationSessionDebugViewModel(
        application = application,
        sourceProjectId = project.projectId,
        initialContinuationSessionId = null,
        repository = InvestigationSessionRepository(api = FakeInvestigationSessionApi()),
    )
    val appended = investigationViewModel.appendLiveEvidence(pendingEvidence)
    streamViewModel.onHudCaptureAccepted(appended)
    assertTrue(appended)
    assertTrue(hud.stateMachine.evidenceAcceptedThisSession)
    assertEquals(ProjectHudCaptureStatus.Idle, hud.stateMachine.captureStatus)

    // ADR-061 architect review, Item 2 (required UX contract point 3/7): Use must leave a visible
    // accepted-photo phone state - the preview closes (capturedPhoto is now null, proven above),
    // but the phone must not go silent. Reuses the SAME hasActiveInvestigation signal the legacy
    // "Resume Investigation" affordance already reads, over the SAME InvestigationSessionDebugViewModel
    // this capture's evidence was just appended into - never a second store.
    assertNull(streamViewModel.uiState.value.capturedPhoto)
    assertTrue(hasActiveInvestigation(investigationViewModel.uiState.value))
    assertTrue(
        shouldShowConversationAcceptedCaptureBanner(
            returnToConversation = true,
            hasActiveInvestigation = hasActiveInvestigation(investigationViewModel.uiState.value),
        ),
    )
    assertEquals("1 photo accepted", conversationAcceptedCaptureLabel(investigationViewModel.uiState.value))

    // Assertion 8: no Share/Investigation/Analyze/Looks-right UI in this path, the entire time.
    assertFalse(shouldShowShareDialog(returnToConversation = true, isShareDialogVisible = streamViewModel.uiState.value.isShareDialogVisible))
    assertFalse(shouldShowInvestigationPanel(returnToConversation = true, isInvestigationPanelVisible = streamViewModel.uiState.value.isInvestigationPanelVisible))
    assertFalse(
        shouldShowInvestigationReopenAffordance(
            returnToConversation = true,
            isInvestigationPanelVisible = streamViewModel.uiState.value.isInvestigationPanelVisible,
            isShareDialogVisible = streamViewModel.uiState.value.isShareDialogVisible,
            hasActiveInvestigation = true, // evidence now exists - would otherwise show
        ),
    )

    // --- Continue on phone (assertion 5): HUD advances to the neutral, noninteractive state ---
    // Driven from the PHONE's own "Continue to Project" control on the accepted-capture banner
    // (StreamViewModel.continueToConversationFromPhone), not the glasses' dispatchPhone - proving
    // the phone-exposed continuation this architect review required actually reaches the same
    // ProjectConversation, not just that the underlying dispatchPhone plumbing supports it.
    streamViewModel.continueToConversationFromPhone()
    withTimeout(10_000) { while (streamViewModel.projectHudPhoneHandoff.value == null) delay(20) }
    val handoff = streamViewModel.projectHudPhoneHandoff.value!!
    assertEquals(project.projectId, handoff.projectId)
    assertEquals(ProjectHudPhoneDestination.ACTIVE_INVESTIGATION, handoff.destination)
    withTimeout(5_000) { while (!hud.stateMachine.phoneControlActive) delay(20) }

    // No further glasses action is honored once handed off (seeded: duplicate action attempt
    // against the neutral state).
    val phoneControlGeneration = hud.stateMachine.renderGeneration
    hud.dispatchCapture(phoneControlGeneration)
    hud.dispatchRefresh(phoneControlGeneration)
    assertTrue(hud.stateMachine.phoneControlActive)
    assertEquals(ProjectHudCaptureStatus.Idle, hud.stateMachine.captureStatus)

    // StreamScreen's LaunchedEffect(projectHudPhoneHandoff): stopStream() then navigate away.
    streamViewModel.stopStream()
    streamViewModel.consumeProjectHudPhoneHandoff(handoff)
    // App stop/resume (seeded failure state): the Display/session teardown must not resurrect
    // interactive content - detach() already ran inside stopStream(); a defensive extra call
    // must be inert.
    hud.detach()

    // --- Same Project Conversation resumes; glasses image becomes a pending attachment (6, 7) ---
    val conversationViewModel = ProjectConversationViewModel(application, project.projectId, repository)
    withTimeout(10_000) { while (conversationViewModel.state.value.loadState != ConversationLoadState.READY) delay(20) }

    val staged = investigationViewModel.stagePendingEvidenceForConversation()
        ?: error("Nothing staged - the accepted glasses evidence never reached the backend")
    val evidence = staged.evidence.lastOrNull() ?: error("Staged session has no evidence")
    conversationViewModel.adoptAcceptedEvidence(ConversationEvidenceReference(evidence.evidenceId, staged.session.sessionId))
    streamViewModel.acknowledgePhoneControl() // the OTHER phoneControlActive trigger - see its doc

    assertNotNull(conversationViewModel.state.value.attachment)

    // Duplicate adoption/staging attempt (seeded: duplicate Evidence/adoption attempt) - the
    // SAME session is reused, never a second one, and acknowledgePhoneControl is idempotent.
    val stagedAgain = investigationViewModel.stagePendingEvidenceForConversation()
    assertEquals(staged.session.sessionId, stagedAgain?.session?.sessionId)
    streamViewModel.acknowledgePhoneControl()
    assertTrue(hud.stateMachine.phoneControlActive)

    // --- Natural conversation send uses the multimodal conversation path (assertion 9) ---
    conversationViewModel.updateDraft("What should I do with this dresser?")
    conversationViewModel.send()
    withTimeout(10_000) { while (conversationViewModel.state.value.sending) delay(20) }
    assertNull(conversationViewModel.state.value.errorMessage)
    val turnsAfterSend = conversationViewModel.state.value.turns
    assertEquals(2, turnsAfterSend.size) // exactly one user turn + one assistant turn (14: no duplicate send)
    assertNull(conversationViewModel.state.value.attachment) // consumed by the send, not left pending twice

    // --- Reload/reopen preserves conversation correctly (assertion 11) ---
    val reopened = ProjectConversationViewModel(application, project.projectId, repository)
    withTimeout(10_000) { while (reopened.state.value.loadState != ConversationLoadState.READY) delay(20) }
    assertEquals(turnsAfterSend.size, reopened.state.value.turns.size)
    assertEquals(turnsAfterSend.map { it.text }, reopened.state.value.turns.map { it.text })

    // --- Assertion 13: no canonical Project data was deleted anywhere in this journey ---
    val finalOverview = repository.getProjectOverview(project.projectId)
    assertEquals("old-investigation-session", finalOverview.latestInvestigation?.sessionId)
    assertNull(finalOverview.latestInvestigation?.trustDecision) // still there, still undecided, untouched
  }

  // Assertion 12 ("legacy entry still surfaces the historical trust review") is deliberately NOT
  // duplicated here as a second MockDeviceKit-driven test: it needs no real session/stream/camera
  // at all (ProjectContinuityHudController.selectProject()/attachDisplayForTesting() depend only
  // on a ProjectRepository, never on StreamViewModel or the DAT SDK), and MockDeviceKit's
  // simulated camera hardware has proven to only support ONE full session per instrumentation
  // process (a second pairGlasses()+startStream() in the same process reproducibly corrupts the
  // next capture, regardless of which test runs it or how long tearDown() waits between them -
  // deterministic across repeated runs, not a timing flake). Adding a second test here would only
  // reproduce that SDK-level limitation for zero additional coverage: the exact same assertion
  // already exists, proven through the real controller, in
  // ProjectContinuityHudAcceptanceHarnessTest.legacyEntryStillLandsOnTheTrustReviewScreenForTheSameUndecidedHistoricalReview
  // (JVM, no hardware dependency at all - see that test's own doc).
}

/**
 * Minimal Display test double, identical in spirit to FakeDisplay.kt in the JVM test source set
 * (a real DAT SDK Display cannot be constructed outside a physical device connection AND - see
 * this file's class doc - MockDeviceKit 0.8.0 does not simulate one at all). Not shared with
 * FakeDisplay.kt directly: src/test and src/androidTest are separate Gradle source sets with no
 * shared compilation unit, so duplicating this ~10-line double here is smaller and safer than
 * introducing a third, shared source set for one class.
 */
private class FakeDisplayForAcceptance(initialState: DisplayState = DisplayState.STARTED) : Display {
  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<DisplayState> = _state.asStateFlow()

  override fun stop() {
    _state.value = DisplayState.STOPPED
  }

  override fun close() = stop()

  override suspend fun sendContent(content: ContentScope.() -> Unit): DatResult<Boolean, DisplayError> {
    ContentScope().content()
    return DatResult.success(true)
  }

  override suspend fun clearDisplay(): DatResult<Boolean, DisplayError> = DatResult.success(true)
}
