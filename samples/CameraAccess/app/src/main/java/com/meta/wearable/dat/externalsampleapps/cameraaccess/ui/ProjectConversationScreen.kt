package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationInteractionContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSessionDebugViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.investigationViewModelKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.reduceInvestigationSpeechState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationLoadState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationTurn
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectConversationViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectSummary

private const val TAG = "CameraAccess:ProjectConversation"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectConversationScreen(
    project: ProjectSummary,
    onBack: () -> Unit,
    onProjectDetails: () -> Unit,
    onUseGlasses: () -> Unit = {},
    glassesConnected: Boolean = false,
    glassesEvidencePending: Boolean = false,
    glassesContinuationSessionId: String? = null,
    onGlassesEvidenceAdopted: () -> Unit = {},
    modifier: Modifier = Modifier,
    conversationViewModel: ProjectConversationViewModel = viewModel(
        key = "conversation:${project.projectId}",
        factory = ProjectConversationViewModel.Factory(
            LocalContext.current.applicationContext as Application, project.projectId)),
    speechControllerFactory: (android.content.Context) -> InvestigationSpeechRecognizerController? =
        ::createInvestigationSpeechRecognizerController,
) {
  val state by conversationViewModel.state.collectAsState()
  val context = LocalContext.current
  val listState = rememberLazyListState()
  var priorCount by remember(project.projectId) { mutableIntStateOf(0) }
  val speechController = remember(project.projectId, context) { speechControllerFactory(context) }
  val glassesEvidenceViewModel: InvestigationSessionDebugViewModel? =
      if (glassesEvidencePending) {
        viewModel(
            // ADR-061 identity collision fix: always the CONVERSATION context - this resolves to
            // the SAME instance StreamScreen's own conversation-originated capture just populated
            // (it computes the identical key when returnToConversation = true - see
            // StreamScreen.kt), and deliberately NEVER the same instance a legacy Capture entry
            // for this Project would use, even though both pass glassesContinuationSessionId/
            // continuationSessionId = null. See investigationViewModelKey's doc.
            key = investigationViewModelKey(project.projectId, glassesContinuationSessionId, InvestigationInteractionContext.CONVERSATION),
            factory = InvestigationSessionDebugViewModel.factory(
                context.applicationContext as Application,
                project.projectId,
                glassesContinuationSessionId,
            ),
        )
      } else null
  var speechState by remember(project.projectId) { mutableStateOf(InvestigationSpeechUiState()) }
  var pendingCameraUri by remember(project.projectId) { mutableStateOf<android.net.Uri?>(null) }

  DisposableEffect(speechController) { onDispose { speechController?.destroy() } }
  val onSpeechEvent: (InvestigationSpeechEvent) -> Unit = { event ->
    val transition = reduceInvestigationSpeechState(speechState, event)
    speechState = transition.state
    transition.transcript?.let { conversationViewModel.updateDraft(appendTranscriptToDraft(state.draft, it)) }
  }
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) speechController?.startListening(onSpeechEvent)
    else onSpeechEvent(InvestigationSpeechEvent.PermissionDenied)
  }
  val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    uri?.let {
      conversationViewModel.attach(
          uri = it,
          displayName = it.lastPathSegment?.substringAfterLast('/') ?: "Photo",
          mimeType = context.contentResolver.getType(it) ?: "image/jpeg",
      )
    }
  }
  val phoneCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
    val uri = pendingCameraUri
    if (captured && uri != null) {
      conversationViewModel.attach(uri, "Phone photo.jpg", "image/jpeg")
    }
    pendingCameraUri = null
  }

  LaunchedEffect(state.turns.size) {
    val wasNearBottom = priorCount == 0 || listState.firstVisibleItemIndex >= (priorCount - 3).coerceAtLeast(0)
    if (state.turns.isNotEmpty() && wasNearBottom) listState.animateScrollToItem(state.turns.lastIndex)
    priorCount = state.turns.size
  }

  LaunchedEffect(glassesEvidencePending, glassesEvidenceViewModel) {
    if (!glassesEvidencePending || glassesEvidenceViewModel == null) return@LaunchedEffect
    Log.d(TAG, "adoption effect: staging pending glasses evidence for project=${project.projectId} continuationSessionId=$glassesContinuationSessionId")
    runCatching { glassesEvidenceViewModel.stagePendingEvidenceForConversation() }
        .onSuccess { staged ->
          val evidence = staged?.evidence?.lastOrNull()
          if (evidence == null) {
            Log.e(TAG, "adoption effect: stagePendingEvidenceForConversation returned no evidence (staged=$staged) - nothing adopted")
            return@onSuccess
          }
          Log.d(TAG, "adoption effect: adopting evidenceId=${evidence.evidenceId} sessionId=${staged.session.sessionId}")
          conversationViewModel.adoptAcceptedEvidence(
              com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ConversationEvidenceReference(
                  evidence.evidenceId,
                  staged.session.sessionId,
              ))
          onGlassesEvidenceAdopted()
        }
        .onFailure {
          Log.e(TAG, "adoption effect: stagePendingEvidenceForConversation failed", it)
          conversationViewModel.showAttachmentError(it)
        }
  }

  Scaffold(
      modifier = modifier.fillMaxSize().background(AppColor.Graphite).systemBarsPadding().imePadding(),
      containerColor = AppColor.Graphite,
      topBar = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = onBack) { Text("‹ Projects", color = AppColor.InkPrimary) }
          Text(
              project.name,
              color = AppColor.InkPrimary,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
              maxLines = 1,
          )
          TextButton(
              onClick = onUseGlasses,
              modifier = Modifier.testTag("use_glasses_action"),
          ) {
            Icon(
                Icons.Filled.Visibility,
                contentDescription = null,
                tint = AppColor.Accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (glassesConnected) "Glasses Ready" else "Use Glasses",
                color = AppColor.Accent,
                modifier = Modifier.padding(start = 6.dp),
            )
          }
          TextButton(onClick = onProjectDetails) { Text("Details", color = AppColor.Accent) }
        }
      },
      bottomBar = {
        ConversationComposer(
            state = state,
            onTextChange = conversationViewModel::updateDraft,
            onAttach = { photoPicker.launch("image/*") },
            onCamera = {
              val imageDirectory = File(context.cacheDir, "images").apply { mkdirs() }
              val uri = FileProvider.getUriForFile(
                  context,
                  "${context.packageName}.fileprovider",
                  File.createTempFile("conversation-", ".jpg", imageDirectory),
              )
              pendingCameraUri = uri
              phoneCamera.launch(uri)
            },
            onRemoveAttachment = conversationViewModel::removeAttachment,
            onMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            onSend = conversationViewModel::send,
            onRetry = conversationViewModel::retry,
        )
      },
  ) { padding ->
    when (state.loadState) {
      ConversationLoadState.LOADING -> Box(
          Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppColor.Accent)
      }
      ConversationLoadState.ERROR -> Column(
          Modifier.fillMaxSize().padding(padding).padding(24.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("Couldn't load this conversation.", color = AppColor.InkPrimary, fontWeight = FontWeight.Bold)
        state.errorMessage?.let { Text(it, color = AppColor.InkSecondary, modifier = Modifier.padding(top = 8.dp)) }
        TextButton(onClick = conversationViewModel::load) { Text("Try again") }
      }
      ConversationLoadState.READY -> LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().padding(padding).testTag("conversation_timeline"),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (state.turns.isEmpty()) item {
          Column(Modifier.fillParentMaxHeight(0.7f), verticalArrangement = Arrangement.Center) {
            Text("Ask about this Project", color = AppColor.InkPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Messages and photos stay with this Project.", color = AppColor.InkSecondary, modifier = Modifier.padding(top = 8.dp))
          }
        }
        items(state.turns, key = ConversationTurn::turnId) { ConversationBubble(it) }
        if (state.sending) item(key = "sending") {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColor.Accent)
            Text("Thinking…", color = AppColor.InkSecondary, modifier = Modifier.padding(start = 10.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun ConversationBubble(turn: ConversationTurn) {
  val user = turn.role == ConversationRole.USER
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
    Column(
        Modifier.widthIn(max = 340.dp)
            .background(if (user) AppColor.Accent else Color(0xFF252A31), RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag("conversation_turn_${turn.sequenceNumber}"),
    ) {
      Text(turn.text, color = if (user) AppColor.AccentInk else AppColor.InkPrimary)
      if (turn.evidenceRefs.isNotEmpty()) {
        Text("📷 ${turn.evidenceRefs.size} photo${if (turn.evidenceRefs.size == 1) "" else "s"}",
            color = if (user) AppColor.AccentInk else AppColor.InkSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
      }
    }
  }
}

@Composable
private fun ConversationComposer(
    state: com.meta.wearable.dat.externalsampleapps.cameraaccess.projects.ProjectConversationUiState,
    onTextChange: (String) -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
  Column(
      Modifier.fillMaxWidth().background(Color(0xFF181C21)).navigationBarsPadding().padding(12.dp)
          .testTag("conversation_composer")) {
    state.attachment?.let { attachment ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        val context = LocalContext.current
        val bitmap = remember(attachment.uri) {
          runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(attachment.uri))?.use(BitmapFactory::decodeStream)
          }.getOrNull()
        }
        bitmap?.let { Image(it.asImageBitmap(), "Attached photo", Modifier.size(54.dp), contentScale = ContentScale.Crop) }
        Text("1 photo", color = AppColor.InkPrimary, modifier = Modifier.padding(start = 10.dp).weight(1f))
        IconButton(onClick = onRemoveAttachment, enabled = !state.sending) { Icon(Icons.Default.Close, "Remove photo", tint = AppColor.InkPrimary) }
      }
    }
    state.errorMessage?.let {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(it, color = Color(0xFFFF9B9B), modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, enabled = !state.sending) { Text("Try again") }
      }
    }
    Row(verticalAlignment = Alignment.Bottom) {
      IconButton(onClick = onAttach, enabled = !state.sending) { Icon(Icons.Default.AttachFile, "Add photo", tint = AppColor.Accent) }
      IconButton(onClick = onCamera, enabled = !state.sending) { Icon(Icons.Default.PhotoCamera, "Take phone photo", tint = AppColor.Accent) }
      OutlinedTextField(
          value = state.draft,
          onValueChange = onTextChange,
          modifier = Modifier.weight(1f).testTag("conversation_input"),
          placeholder = { Text("Ask about this Project") },
          minLines = 1,
          maxLines = 5,
          enabled = !state.sending,
      )
      IconButton(onClick = onMic, enabled = !state.sending) { Icon(Icons.Default.Mic, "Speak", tint = AppColor.Accent) }
      IconButton(
          onClick = onSend,
          enabled = !state.sending && state.draft.isNotBlank(),
          modifier = Modifier.testTag("conversation_send"),
      ) { Icon(Icons.Default.Send, "Send", tint = AppColor.Accent) }
    }
  }
}
