package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationInteractionContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationEvidenceReference
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationImageUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationSendResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationTurnStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.MockProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationVisualArtifactReference
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectConversation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectConversationViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.VisualArtifactImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectConversationScreenTest {
  @get:Rule val compose = createComposeRule()
  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
  private val project = ProjectSummary("upstairs-ac-repair", "Upstairs AC Repair", "active")

  @Test
  fun loadsSendsFollowUpAndRendersOrderedConversation() {
    val repository = MockProjectRepository()
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }

    compose.onNodeWithTag("conversation_input").performTextInput("What next?")
    compose.onNodeWithTag("conversation_send").assertIsEnabled().performClick()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }
    compose.onNodeWithText("Mock assistant response to: What next?").assertExists()

    compose.onNodeWithTag("conversation_input").performTextInput("Why?")
    compose.onNodeWithTag("conversation_send").performClick()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 4 }
    assertEquals(listOf(1, 2, 3, 4), viewModel.state.value.turns.map { it.sequenceNumber })
  }

  @Test
  fun retryReusesIdempotencyKeyAndDoesNotDuplicateAfterRecomposition() {
    val repository = RetryRecordingRepository()
    val savedState = SavedStateHandle()
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository, savedState = savedState)
    setContent(viewModel)
    compose.waitUntil(10_000) { !viewModel.state.value.sending }
    compose.onNodeWithTag("conversation_input").performTextInput("Retry me")
    compose.onNodeWithTag("conversation_send").performClick()
    compose.waitUntil(10_000) { viewModel.state.value.errorMessage != null }
    compose.onNodeWithText("Try again").performClick()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }
    assertEquals(2, repository.keys.size)
    assertEquals(repository.keys[0], repository.keys[1])
    assertEquals(2, viewModel.state.value.turns.size)
  }

  @Test
  fun reopenAndProjectSwitchReadOnlyTheirOwnBackendConversation() {
    val repository = MockProjectRepository()
    val first = ProjectConversationViewModel(application, project.projectId, repository)
    compose.waitUntil(10_000) { first.state.value.loadState.name == "READY" }
    first.updateDraft("Persist me")
    first.send()
    compose.waitUntil(10_000) { first.state.value.turns.size == 2 }

    val reopened = ProjectConversationViewModel(application, project.projectId, repository)
    val other = ProjectConversationViewModel(application, "custom-meta-ai-glasses", repository)
    compose.waitUntil(10_000) { reopened.state.value.turns.size == 2 && other.state.value.loadState.name == "READY" }
    assertEquals("Persist me", reopened.state.value.turns.first().text)
    assertEquals(emptyList<Any>(), other.state.value.turns)
  }

  @Test
  fun glassesActionIsDiscoverableAndKeepsPhoneAttachmentSeparate() {
    val viewModel = ProjectConversationViewModel(application, project.projectId, MockProjectRepository())
    var openedGlasses = false
    compose.setContent {
      ProjectConversationScreen(
          project = project,
          onBack = {},
          onProjectDetails = {},
          onUseGlasses = { openedGlasses = true },
          glassesConnected = true,
          conversationViewModel = viewModel,
          speechControllerFactory = { null },
      )
    }
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }

    compose.onNodeWithTag("use_glasses_action").assertExists().performClick()
    compose.onNodeWithText("Glasses Ready").assertExists()
    compose.onNodeWithContentDescription("Add photo").assertExists()
    compose.onNodeWithContentDescription("Take phone photo").assertExists()
    compose.onAllNodesWithText(project.name).assertCountEquals(1)
    assertTrue(openedGlasses)
  }

  @Test
  fun acceptedGlassesEvidenceSurvivesDraftEditingAndIsSentOnceForTheSameProject() {
    val repository = EvidenceRecordingRepository()
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    val reference = ConversationEvidenceReference("evidence-glasses-1", "session-project-a")
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }

    viewModel.adoptAcceptedEvidence(reference)
    viewModel.updateDraft("What do you notice in this room?")
    viewModel.send()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }

    viewModel.updateDraft("And what should I do next?")
    viewModel.send()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 4 }

    assertEquals(project.projectId, repository.sentProjectId)
    assertEquals(listOf(listOf(reference), emptyList()), repository.sentEvidenceRefs)
    assertEquals(2, repository.sendCount)
  }

  // --- Phase 3A closeout: persistent conversation image thumbnails/viewer ---

  @Test
  fun userTurnWithEvidenceReferenceRendersThumbnailAndOpensFullScreenViewerThenReturnsToConversation() {
    val reference = ConversationEvidenceReference("evidence-thumb-1", "session-thumb-1")
    val repository = ImageBackedRepository(mapOf(reference.evidenceId to fakePngBytes()))
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }

    viewModel.adoptAcceptedEvidence(reference)
    viewModel.updateDraft("What do you think of this room?")
    viewModel.send()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }

    val thumbnailTag = "conversation_evidence_thumbnail_${reference.evidenceId}"
    compose.waitUntil(10_000) { viewModel.turnImages.value.values.any { it is ConversationImageUiState.Ready } }
    compose.onNodeWithTag(thumbnailTag).assertExists().performClick()
    compose.onNodeWithTag("conversation_evidence_viewer").assertExists()

    compose.onNodeWithTag("conversation_evidence_viewer_close").performClick()
    compose.onNodeWithTag("conversation_evidence_viewer").assertDoesNotExist()
    // Closing the viewer returns to the same, still-intact conversation.
    compose.onNodeWithTag("conversation_timeline").assertExists()
    compose.onNodeWithTag(thumbnailTag).assertExists()
  }

  @Test
  fun textOnlyTurnRendersWithoutAnyEvidenceThumbnail() {
    val viewModel = ProjectConversationViewModel(application, project.projectId, MockProjectRepository())
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }

    viewModel.updateDraft("Just a question, no photo")
    viewModel.send()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }

    compose.onNodeWithTag("conversation_turn_1_images").assertDoesNotExist()
    compose.onNodeWithTag("conversation_turn_2_images").assertDoesNotExist()
  }

  @Test
  fun turnWithMultipleEvidenceReferencesRendersEachThumbnailDeterministically() {
    val refA = ConversationEvidenceReference("evidence-multi-a", "session-multi-1")
    val refB = ConversationEvidenceReference("evidence-multi-b", "session-multi-1")
    val conversation = ProjectConversation(
        conversationId = "conversation-multi",
        projectId = project.projectId,
        turns = listOf(
            ConversationTurn(
                turnId = "turn-multi-user",
                projectId = project.projectId,
                sequenceNumber = 1,
                role = ConversationRole.USER,
                status = ConversationTurnStatus.COMPLETED,
                text = "Compare these two",
                evidenceRefs = listOf(refA, refB),
                idempotencyKey = "multi-key",
            ),
            ConversationTurn(
                turnId = "turn-multi-assistant",
                projectId = project.projectId,
                sequenceNumber = 2,
                role = ConversationRole.ASSISTANT,
                status = ConversationTurnStatus.COMPLETED,
                text = "They look similar.",
                evidenceRefs = emptyList(),
                idempotencyKey = "multi-key",
            ),
        ),
    )
    val repository = PreloadedConversationRepository(
        conversation, mapOf(refA.evidenceId to fakePngBytes(), refB.evidenceId to fakePngBytes()))
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)

    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }
    compose.onNodeWithTag("conversation_evidence_thumbnail_${refA.evidenceId}").assertExists()
    compose.onNodeWithTag("conversation_evidence_thumbnail_${refB.evidenceId}").assertExists()
  }

  /**
   * The image must come back from persisted turn data alone on a fresh ViewModel/composition -
   * simulating conversation reload / app relaunch - never from the transient staged reference or
   * any live-capture state the sending ViewModel happened to hold.
   */
  @Test
  fun reloadedConversationStillRendersEvidenceThumbnailFromPersistedTurnDataAlone() {
    val reference = ConversationEvidenceReference("evidence-reload-1", "session-reload-1")
    val repository = ImageBackedRepository(mapOf(reference.evidenceId to fakePngBytes()))
    val first = ProjectConversationViewModel(application, project.projectId, repository)
    compose.waitUntil(10_000) { first.state.value.loadState.name == "READY" }
    first.adoptAcceptedEvidence(reference)
    first.updateDraft("Persisted photo")
    first.send()
    compose.waitUntil(10_000) { first.state.value.turns.size == 2 }

    // A brand-new ViewModel instance with no attach()/adoptAcceptedEvidence() call and no staged
    // reference of its own - only the persisted turn it reads back via getProjectConversation.
    val reopened = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(reopened)
    compose.waitUntil(10_000) { reopened.state.value.turns.size == 2 }
    compose.onNodeWithTag("conversation_evidence_thumbnail_${reference.evidenceId}").assertExists()
  }

  @Test
  fun evidenceImageLoadFailureShowsFallbackAndLeavesConversationIntact() {
    val reference = ConversationEvidenceReference("evidence-missing-1", "session-missing-1")
    val repository = ImageBackedRepository(imagesByEvidenceId = emptyMap())
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }

    viewModel.adoptAcceptedEvidence(reference)
    viewModel.updateDraft("Broken photo")
    viewModel.send()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }
    compose.waitUntil(10_000) { viewModel.turnImages.value.values.any { it is ConversationImageUiState.Failed } }

    compose.onNodeWithTag("conversation_evidence_thumbnail_${reference.evidenceId}").assertExists()
    compose.onNodeWithTag("conversation_timeline").assertExists()
  }

  // --- Phase 3B: AI-generated Visualization rendering in conversation ---

  private fun visualArtifactConversation(
      projectId: String, artifactRef: ConversationVisualArtifactReference,
  ) = ProjectConversation(
      conversationId = "conversation-visual-$projectId",
      projectId = projectId,
      turns = listOf(
          ConversationTurn(
              turnId = "turn-visual-user",
              projectId = projectId,
              sequenceNumber = 1,
              role = ConversationRole.USER,
              status = ConversationTurnStatus.COMPLETED,
              text = "I like option 3. Show me what that would look like in my room.",
              evidenceRefs = emptyList(),
              idempotencyKey = "visual-key",
          ),
          ConversationTurn(
              turnId = "turn-visual-assistant",
              projectId = projectId,
              sequenceNumber = 2,
              role = ConversationRole.ASSISTANT,
              status = ConversationTurnStatus.COMPLETED,
              text = "Here's a visualization of option 3.",
              evidenceRefs = emptyList(),
              idempotencyKey = "visual-key",
              visualArtifactRef = artifactRef,
          ),
      ),
  )

  @Test
  fun assistantTurnWithVisualArtifactReferenceRendersLabeledThumbnailAndOpensViewerThenReturnsToConversation() {
    val artifactRef = ConversationVisualArtifactReference("result-1", "option-3", "artifact-1")
    val conversation = visualArtifactConversation(project.projectId, artifactRef)
    val repository = PreloadedConversationRepository(
        conversation, imagesByEvidenceId = emptyMap(),
        visualizationsByArtifactId = mapOf(artifactRef.artifactId to fakePngBytes()),
    )
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }

    // Labeled distinctly from a Source Evidence thumbnail - never presented as if it were the
    // original photo.
    compose.onNodeWithText("AI visualization").assertExists()
    val thumbnailTag = "conversation_visual_artifact_thumbnail_${artifactRef.artifactId}"
    compose.waitUntil(10_000) { viewModel.turnImages.value.values.any { it is ConversationImageUiState.Ready } }
    compose.onNodeWithTag(thumbnailTag).assertExists().performClick()
    compose.onNodeWithTag("conversation_evidence_viewer").assertExists()

    compose.onNodeWithTag("conversation_evidence_viewer_close").performClick()
    compose.onNodeWithTag("conversation_evidence_viewer").assertDoesNotExist()
    compose.onNodeWithTag("conversation_timeline").assertExists()
    compose.onNodeWithTag(thumbnailTag).assertExists()
  }

  @Test
  fun visualArtifactThumbnailSurvivesReloadFromPersistedTurnDataAlone() {
    val artifactRef = ConversationVisualArtifactReference("result-2", "option-1", "artifact-reload-1")
    val conversation = visualArtifactConversation(project.projectId, artifactRef)
    val repository = PreloadedConversationRepository(
        conversation, imagesByEvidenceId = emptyMap(),
        visualizationsByArtifactId = mapOf(artifactRef.artifactId to fakePngBytes()),
    )
    // A brand-new ViewModel with no live generation state of its own - only the persisted
    // VISUAL_ARTIFACT_REFERENCE it reads back via getProjectConversation.
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }
    compose.onNodeWithTag("conversation_visual_artifact_thumbnail_${artifactRef.artifactId}").assertExists()
  }

  @Test
  fun sourceEvidenceAndGeneratedVisualizationRemainDistinguishableInTheSameConversation() {
    val evidenceRef = ConversationEvidenceReference("evidence-source-1", "session-source-1")
    val artifactRef = ConversationVisualArtifactReference("result-3", "option-2", "artifact-distinct-1")
    val conversation = ProjectConversation(
        conversationId = "conversation-distinct",
        projectId = project.projectId,
        turns = listOf(
            ConversationTurn(
                turnId = "turn-distinct-user",
                projectId = project.projectId,
                sequenceNumber = 1,
                role = ConversationRole.USER,
                status = ConversationTurnStatus.COMPLETED,
                text = "Here's my dresser. Give me some ideas.",
                evidenceRefs = listOf(evidenceRef),
                idempotencyKey = "distinct-key",
            ),
            ConversationTurn(
                turnId = "turn-distinct-assistant",
                projectId = project.projectId,
                sequenceNumber = 2,
                role = ConversationRole.ASSISTANT,
                status = ConversationTurnStatus.COMPLETED,
                text = "Here's a visualization of option 2.",
                evidenceRefs = emptyList(),
                idempotencyKey = "distinct-key",
                visualArtifactRef = artifactRef,
            ),
        ),
    )
    val repository = PreloadedConversationRepository(
        conversation,
        imagesByEvidenceId = mapOf(evidenceRef.evidenceId to fakePngBytes()),
        visualizationsByArtifactId = mapOf(artifactRef.artifactId to fakePngBytes()),
    )
    val viewModel = ProjectConversationViewModel(application, project.projectId, repository)
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }

    compose.onNodeWithTag("conversation_evidence_thumbnail_${evidenceRef.evidenceId}").assertExists()
    compose.onNodeWithTag("conversation_visual_artifact_thumbnail_${artifactRef.artifactId}").assertExists()
  }

  @Test
  fun turnWithoutVisualArtifactReferenceNeverRendersTheVisualizationLabel() {
    val viewModel = ProjectConversationViewModel(application, project.projectId, MockProjectRepository())
    setContent(viewModel)
    compose.waitUntil(10_000) { viewModel.state.value.loadState.name == "READY" }
    viewModel.updateDraft("Just a question, no visualization")
    viewModel.send()
    compose.waitUntil(10_000) { viewModel.state.value.turns.size == 2 }
    compose.onNodeWithText("AI visualization").assertDoesNotExist()
  }

  private fun fakePngBytes(): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
    val stream = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  private class ImageBackedRepository(
      private val imagesByEvidenceId: Map<String, ByteArray>,
  ) : ProjectRepository by MockProjectRepository() {
    private val delegate = MockProjectRepository()

    override suspend fun getProjectConversation(projectId: String) = delegate.getProjectConversation(projectId)

    override suspend fun sendProjectConversationMessage(
        projectId: String,
        text: String,
        evidenceRefs: List<ConversationEvidenceReference>,
        idempotencyKey: String,
    ) = delegate.sendProjectConversationMessage(projectId, text, evidenceRefs, idempotencyKey)

    override suspend fun getConversationEvidenceImage(projectId: String, reference: ConversationEvidenceReference): ByteArray =
        imagesByEvidenceId[reference.evidenceId] ?: throw NoSuchElementException("No fake image for ${reference.evidenceId}")
  }

  private class PreloadedConversationRepository(
      private val conversation: ProjectConversation,
      private val imagesByEvidenceId: Map<String, ByteArray>,
      private val visualizationsByArtifactId: Map<String, ByteArray> = emptyMap(),
  ) : ProjectRepository by MockProjectRepository() {
    override suspend fun getProjectConversation(projectId: String) = conversation

    override suspend fun getConversationEvidenceImage(projectId: String, reference: ConversationEvidenceReference): ByteArray =
        imagesByEvidenceId[reference.evidenceId] ?: throw NoSuchElementException("No fake image for ${reference.evidenceId}")

    override suspend fun getVisualArtifactImages(
        projectId: String, resultId: String, optionId: String, artifactId: String,
    ): VisualArtifactImages = VisualArtifactImages(
        source = ByteArray(0),
        visualization = visualizationsByArtifactId[artifactId]
            ?: throw NoSuchElementException("No fake visualization for $artifactId"),
    )
  }

  /**
   * Closes a real gap found during a physical Phase 2 retest (real glasses capture never appeared
   * as a phone-side conversation attachment): every other glasses-evidence test in this file and
   * in the Phase2GlassesConversationAcceptanceTest instrumented harness calls
   * ProjectConversationViewModel.adoptAcceptedEvidence()/InvestigationSessionDebugViewModel calls
   * DIRECTLY, scripting past the actual production wiring - this test is the only one that
   * mounts the REAL ProjectConversationScreen composable with glassesEvidencePending=true and lets
   * its own LaunchedEffect(glassesEvidencePending, glassesEvidenceViewModel) resolve the
   * InvestigationSessionDebugViewModel by the SAME investigationViewModelKey(...) a real
   * StreamScreen capture already populated, exactly like the real app does across its
   * Capture-to-ProjectConversation navigation swap (both resolve through the same
   * ViewModelStoreOwner by key - here, this ComposeTestRule's own hosting Activity across two
   * sequential setContent calls). If ProjectConversationScreen's key/gating logic ever regresses,
   * this is the one test that would catch it.
   */
  @Test
  fun glassesEvidencePendingAdoptsRealLiveEvidenceThroughTheActualComposableWiring() {
    // InvestigationSessionDebugViewModel's default repository hits the REAL backend (never
    // FakeInvestigationSessionApi - see class doc), which validates project_id as a genuine UUID.
    // A real backend-created project (like any real glasses-originated Project) rather than this
    // file's other tests' MockProjectRepository slug id, so this test exercises the true
    // production network path end-to-end.
    val realProject = runBlocking {
      com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.HttpUrlProjectRepository()
          .createProject(
              com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectRequest(
                  name = "Glasses Evidence Wiring Test",
                  goal = "Verify real adoption wiring",
              ),
          )
    }
    val conversationViewModel = ProjectConversationViewModel(application, realProject.projectId, MockProjectRepository())
    lateinit var evidenceViewModel: InvestigationSessionDebugViewModel
    // A ComposeTestRule only accepts one setContent() call per test, unlike the real app's single
    // long-lived Activity - so both phases share one composition, gated by this Compose State
    // (a plain var wouldn't trigger recomposition when flipped from outside the composable below).
    val showConversation = mutableStateOf(false)
    var adopted = false

    // One composition throughout: it always resolves the same InvestigationSessionDebugViewModel
    // by investigationViewModelKey(...) - exactly like StreamScreen (returnToConversation = true)
    // and ProjectConversationScreen resolve the SAME instance by that key across a real
    // Capture-to-ProjectConversation navigation swap in the running app (same ViewModelStoreOwner,
    // same key). CONVERSATION context explicitly - see investigationViewModelKey's doc on the
    // identity-collision fix this proves: a LEGACY-context instance for the same Project would be
    // a silently different ViewModel, and this test would then fail to observe the appended
    // evidence at all once ProjectConversationScreen resolves its own CONVERSATION-context one.
    compose.setContent {
      evidenceViewModel = viewModel(
          key = investigationViewModelKey(realProject.projectId, null, InvestigationInteractionContext.CONVERSATION),
          factory = InvestigationSessionDebugViewModel.factory(application, realProject.projectId, null),
      )
      if (showConversation.value) {
        ProjectConversationScreen(
            project = realProject,
            onBack = {},
            onProjectDetails = {},
            glassesEvidencePending = true,
            glassesContinuationSessionId = null,
            onGlassesEvidenceAdopted = { adopted = true },
            conversationViewModel = conversationViewModel,
            speechControllerFactory = { null },
        )
      }
    }
    compose.waitForIdle()

    // Populate it with live evidence exactly as a real glasses capture's StreamScreen would via
    // appendLiveEvidence, THEN flip to the real ProjectConversationScreen composable and let its
    // own, unmodified LaunchedEffect(glassesEvidencePending, glassesEvidenceViewModel) resolve and
    // adopt it - never scripted/called directly, unlike every other glasses-evidence test in this
    // file and in Phase2GlassesConversationAcceptanceTest.
    val appended = evidenceViewModel.appendLiveEvidence(
        InvestigationEvidenceInput(
            slotIndex = 0,
            filename = "glasses_capture.png",
            mimeType = "image/png",
            // A real, decodable PNG - this test's LaunchedEffect drives an actual upload through
            // InvestigationSessionRepository.uploadEvidence, which now (Phase 3C phone-photo-
            // ingestion fix) decodes bounds/orientation on-device via BitmapFactory/ExifInterface
            // before deciding whether to pass evidence through unchanged; placeholder junk bytes
            // would now fail that real decode instead of passing through as before.
            bytes = validPngBytes(),
            source = InvestigationEvidenceSource.LIVE_GLASSES,
        ),
    )
    assertTrue(appended)
    showConversation.value = true
    // Wait on the callback (fired strictly after adoptAcceptedEvidence() in production code - see
    // ProjectConversationScreen.kt's LaunchedEffect) rather than the attachment field alone: this
    // test's real network round trip resumes the LaunchedEffect off the main thread, and polling
    // only the state field risked observing it between those two same-block statements.
    compose.waitUntil(10_000) { adopted }
    assertNotNull(conversationViewModel.state.value.attachment)
    assertEquals("Glasses photo", conversationViewModel.state.value.attachment?.displayName)
  }

  /**
   * ADR-061 architect review, Item 1: concrete, composable-level proof (not just the pure-function
   * proof in InvestigationProductStateTest) that a conversation-originated capture's
   * InvestigationSessionDebugViewModel and a legacy Capture entry's for the SAME Project, both with
   * no backend session yet (continuationSessionId = null), are genuinely distinct Compose-retained
   * instances, and that evidence appended to one never becomes visible through the other - the
   * exact leak this reconciliation pass exists to remove.
   */
  @Test
  fun conversationContextAndLegacyContextNeverShareTheSameInvestigationViewModelInstanceForTheSameProject() {
    val realProject = runBlocking {
      com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.HttpUrlProjectRepository()
          .createProject(
              com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.NewProjectRequest(
                  name = "Identity Collision Isolation Test",
                  goal = "Verify legacy/conversation ViewModel isolation",
              ),
          )
    }
    lateinit var legacyEvidenceViewModel: InvestigationSessionDebugViewModel
    lateinit var conversationEvidenceViewModel: InvestigationSessionDebugViewModel
    compose.setContent {
      legacyEvidenceViewModel = viewModel(
          // No explicit context - exactly what ProjectDetailScreen's ContinueInvestigationSection
          // and the legacy global Capture entry compute (default LEGACY).
          key = investigationViewModelKey(realProject.projectId, null),
          factory = InvestigationSessionDebugViewModel.factory(application, realProject.projectId, null),
      )
      conversationEvidenceViewModel = viewModel(
          // Exactly what StreamScreen (returnToConversation = true) and ProjectConversationScreen
          // compute.
          key = investigationViewModelKey(realProject.projectId, null, InvestigationInteractionContext.CONVERSATION),
          factory = InvestigationSessionDebugViewModel.factory(application, realProject.projectId, null),
      )
    }
    compose.waitForIdle()

    assertNotSame(
        "conversation-originated and legacy Capture entries for the same Project must never share an InvestigationSessionDebugViewModel instance",
        legacyEvidenceViewModel,
        conversationEvidenceViewModel,
    )

    val appended = conversationEvidenceViewModel.appendLiveEvidence(
        InvestigationEvidenceInput(
            slotIndex = 0,
            filename = "glasses_capture.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3),
            source = InvestigationEvidenceSource.LIVE_GLASSES,
        ),
    )
    assertTrue(appended)

    assertEquals(1, conversationEvidenceViewModel.uiState.value.activeCaptureCount)
    assertEquals(
        "evidence accepted through a conversation-originated capture must never leak into a same-Project legacy Investigation session",
        0,
        legacyEvidenceViewModel.uiState.value.activeCaptureCount,
    )
  }

  /** A minimal, genuinely decodable PNG - unlike a raw junk byte array, this survives the real
   * on-device BitmapFactory/ExifInterface decode that InvestigationCaptureNormalizer's
   * normalizeImageEvidenceForBackend now performs on every uploaded evidence item. */
  private fun validPngBytes(): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
    bitmap.recycle()
    return output.toByteArray()
  }

  private fun setContent(viewModel: ProjectConversationViewModel) {
    compose.setContent {
      ProjectConversationScreen(
          project = project,
          onBack = {},
          onProjectDetails = {},
          conversationViewModel = viewModel,
          speechControllerFactory = { null },
      )
    }
  }

  private class RetryRecordingRepository : ProjectRepository by MockProjectRepository() {
    private val delegate = MockProjectRepository()
    val keys = mutableListOf<String>()
    private var fail = true

    override suspend fun getProjectConversation(projectId: String) = delegate.getProjectConversation(projectId)

    override suspend fun sendProjectConversationMessage(
        projectId: String,
        text: String,
        evidenceRefs: List<ConversationEvidenceReference>,
        idempotencyKey: String,
    ): ConversationSendResult {
      keys += idempotencyKey
      if (fail) {
        fail = false
        throw IllegalStateException("Temporary failure")
      }
      return delegate.sendProjectConversationMessage(projectId, text, evidenceRefs, idempotencyKey)
    }
  }

  private class EvidenceRecordingRepository : ProjectRepository by MockProjectRepository() {
    private val delegate = MockProjectRepository()
    var sentProjectId: String? = null
    val sentEvidenceRefs = mutableListOf<List<ConversationEvidenceReference>>()
    var sendCount = 0

    override suspend fun getProjectConversation(projectId: String) =
        delegate.getProjectConversation(projectId)

    override suspend fun sendProjectConversationMessage(
        projectId: String,
        text: String,
        evidenceRefs: List<ConversationEvidenceReference>,
        idempotencyKey: String,
    ): ConversationSendResult {
      sentProjectId = projectId
      sentEvidenceRefs += evidenceRefs
      sendCount += 1
      return delegate.sendProjectConversationMessage(projectId, text, evidenceRefs, idempotencyKey)
    }
  }
}
