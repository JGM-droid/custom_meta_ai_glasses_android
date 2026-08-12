/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationEvidenceSource
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.bitmapToInvestigationEvidence
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.heicBytesToInvestigationEvidence
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.liveCaptureFilename
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("AutoCloseableUse")
internal class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamState.CLOSED)
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null
  private var display: Display? = null
  private var displayStateJob: Job? = null
  private var previousDeviceSessionState: DeviceSessionState? = null
  private val lifecycleController = StreamSessionLifecycleController()
  private var investigationActiveForDisplay: Boolean = false
  private var activeCaptureCountForDisplay: Int = 0
  private var hasCaptureCapacityForDisplay: Boolean = true
  private var displayState: DisplayState? = null
  private var lastDisplayCaptureModel: InvestigationDisplayCaptureModel? = null

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  fun startStream() {
    val hasActivePipeline =
        (sessionStateJob?.isActive == true) ||
            (videoJob?.isActive == true) ||
            (stateJob?.isActive == true) ||
            (errorJob?.isActive == true) ||
            stream != null

    val (decision, startToken) =
        lifecycleController.requestStart(
            hasSessionReference = session != null,
            hasActivePipeline = hasActivePipeline,
        )

    if (decision == StreamSessionLifecycleController.StartDecision.IGNORE || startToken == null) {
      Log.d(TAG, "Ignoring startStream() because stream/session is already managed")
      return
    }

    resetUiForNewStart()

    // Initialize presentation queue - frames are presented based on timestamp, not arrival time
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                _uiState.update {
                  it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
                }
              }
            },
        )
    presentationQueue = queue
    queue.start()

    if (decision == StreamSessionLifecycleController.StartDecision.CREATE_SESSION) {
      previousDeviceSessionState = null
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            if (!lifecycleController.shouldAcceptCreateResult(startToken)) {
              createdSession.stop()
              return@onSuccess
            }
            session = createdSession
            lifecycleController.onSessionCreated(startToken)
            sessionErrorJob = viewModelScope.launch {
              createdSession.errors.collect { error -> handleSessionError(error) }
            }
            session?.start()
            startStreamInternal(startToken)
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to create session: ${error.description}")
            lifecycleController.onCreateFailed(startToken)
            handleSessionError(error)
          }
      return
    }

    previousDeviceSessionState = null
    startStreamInternal(startToken)
  }

  private fun startStreamInternal(startToken: Long) {
    Log.d(TAG, "startStreamInternal() - collecting session state")
    lifecycleController.onStartAttached(startToken)
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        val prevState = previousDeviceSessionState
        previousDeviceSessionState = currentState

        if (currentState == DeviceSessionState.STARTED) {
          wearablesViewModel.setDatAppUpdateRequired(false)
          if (prevState == DeviceSessionState.PAUSED && stream != null) {
            // PAUSED → STARTED: device-initiated resume (tap gesture).
            // The SDK handles resume internally via requestCameraOn() → resumeStreaming().
            // Do NOT recreate the stream — just let the SDK resume it.
            Log.d(TAG, "Session resumed from PAUSED — stream stays alive")
            return@collect
          }

          videoJob?.cancel()
          stateJob?.cancel()
          errorJob?.cancel()
          stream?.stop()
          stream = null
          session
              ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))
              ?.onSuccess { addedStream ->
                stream = addedStream
                videoJob = viewModelScope.launch {
                  Log.d(TAG, "Collecting video frames from stream")
                  stream?.videoStream?.collect { handleVideoFrame(it) }
                  Log.d(TAG, "Video stream collection ended")
                }
                stateJob = viewModelScope.launch {
                  stream?.state?.collect { streamState ->
                    val prevStreamState = _uiState.value.streamState
                    Log.d(TAG, "Stream state changed: $prevStreamState -> $streamState")
                    _uiState.update { it.copy(streamState = streamState) }

                    val wasActive = prevStreamState !in SESSION_TERMINAL_STATES
                    val isTerminated = streamState in SESSION_TERMINAL_STATES
                    if (wasActive && isTerminated) {
                      Log.d(TAG, "Terminal state reached, navigating back")
                      stopStream()
                      wearablesViewModel.navigateToDeviceSelection()
                    }
                  }
                }
                errorJob = viewModelScope.launch {
                  stream?.errorStream?.collect { error ->
                    Log.d(TAG, "Stream error received: $error (description: ${error.description})")
                    if (error == StreamError.STREAM_ERROR) {
                      Log.d(TAG, "Non-critical error, stream continues")
                      return@collect
                    }
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                    // Use `getLocalizedDescription(context)` for user-facing text —
                    // `description` is always English and intended for logs.
                    wearablesViewModel.setRecentError(
                        error.getLocalizedDescription(getApplication())
                    )
                  }
                }
                stream?.start()
                attachDisplayIfAvailable()
              }
              ?.onFailure { error, _ ->
                Log.e(TAG, "Failed to add stream to session: ${error.description}")
              }
        } else if (currentState == DeviceSessionState.PAUSED) {
          // Tap gesture paused the session — keep the stream alive.
          // The SDK transitions StreamState to PAUSED internally.
          Log.d(TAG, "Session paused (tap gesture) — keeping stream alive for resume")
        }
      }
    }
  }

  fun stopStream() {
    if (
        !lifecycleController.requestStop(
            hasSessionReference = session != null,
            hasStreamReference = stream != null,
        )
    ) {
      Log.d(TAG, "Ignoring stopStream() because no stream/session is active")
      return
    }

    var teardownFailure: Throwable? = null

    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    displayStateJob?.cancel()
    displayStateJob = null
    presentationQueue?.stop()
    presentationQueue = null
    _uiState.update { INITIAL_STATE }
    previousDeviceSessionState = null
    try {
      session?.removeDisplay()
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to detach display cleanly", t)
    }
    display = null
    displayState = null
    lastDisplayCaptureModel = null

    try {
      stream?.stop()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to stop stream cleanly", t)
      teardownFailure = t
    }
    stream = null
    try {
      session?.stop()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to stop session cleanly", t)
      teardownFailure = teardownFailure ?: t
    }
    session = null
    lifecycleController.onStopCompleted()

    teardownFailure?.let {
      wearablesViewModel.setRecentError(
          "Failed to fully release the camera session. Try starting the stream again."
      )
    }
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    val alreadyShowingUpdateRequired =
        wearablesViewModel.uiState.value.isFirmwareUpdateRequired ||
            wearablesViewModel.uiState.value.isDatAppUpdateRequired

    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
    }
    if (alreadyShowingUpdateRequired && error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      _uiState.update { it.copy(captureErrorMessage = "Capture already in progress.") }
      return
    }

    if (uiState.value.streamState != StreamState.STREAMING || stream == null) {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamState})",
      )
      _uiState.update { it.copy(captureErrorMessage = "Start the live stream before capturing a still image.") }
      return
    }

    Log.d(TAG, "Starting photo capture")
    _uiState.update { it.copy(isCapturing = true, captureErrorMessage = null) }

    viewModelScope.launch {
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            Log.d(TAG, "Photo capture successful")
            val normalized = withContext(Dispatchers.Default) { normalizeCapturedPhoto(photoData) }
            val returnToInvestigation = uiState.value.shouldReturnToInvestigationAfterCapture
            _uiState.update {
              it.copy(
                  isCapturing = false,
                  capturedPhoto = normalized.previewBitmap,
                  capturedInvestigationEvidence = normalized.investigationEvidence,
                  isShareDialogVisible = !returnToInvestigation,
                  isInvestigationPanelVisible = returnToInvestigation,
                  shouldReturnToInvestigationAfterCapture = false,
                  captureErrorMessage = null,
              )
            }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update {
              it.copy(
                  isCapturing = false,
                  captureErrorMessage = error.getLocalizedDescription(getApplication()),
              )
            }
          }
          ?: run {
            _uiState.update {
              it.copy(
                  isCapturing = false,
                  captureErrorMessage = "No active stream is available for capture.",
              )
            }
          }
    }
  }

  fun updateDisplayCaptureControl(
      investigationActive: Boolean,
      activeCaptureCount: Int,
      hasCaptureCapacity: Boolean,
  ) {
    investigationActiveForDisplay = investigationActive
    activeCaptureCountForDisplay = activeCaptureCount
    hasCaptureCapacityForDisplay = hasCaptureCapacity

    viewModelScope.launch { renderDisplayCaptureControlIfNeeded() }
  }

  fun onDisplayCaptureAction() {
    if (!hasCaptureCapacityForDisplay) {
      return
    }

    prepareForAdditionalInvestigationCapture()
    capturePhoto()
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun showInvestigationPanel() {
    _uiState.update { it.copy(isInvestigationPanelVisible = true) }
  }

  fun hideInvestigationPanel() {
    _uiState.update { it.copy(isInvestigationPanelVisible = false) }
  }

  fun consumeCapturedInvestigationEvidence() {
    _uiState.update { it.copy(capturedInvestigationEvidence = null) }
  }

  fun prepareForAdditionalInvestigationCapture() {
    _uiState.update {
      it.copy(
          isInvestigationPanelVisible = false,
          isShareDialogVisible = false,
          shouldReturnToInvestigationAfterCapture = true,
          captureErrorMessage = null,
      )
    }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private data class NormalizedCapture(
      val previewBitmap: Bitmap,
      val investigationEvidence: InvestigationEvidenceInput,
  )

  private fun normalizeCapturedPhoto(photo: PhotoData): NormalizedCapture {
    return when (photo) {
      is PhotoData.Bitmap -> {
        val previewBitmap = photo.bitmap
        val evidence =
            bitmapToInvestigationEvidence(
                bitmap = previewBitmap,
                slotIndex = 0,
                filename = liveCaptureFilename(slotIndex = 0, extension = "png"),
                source = InvestigationEvidenceSource.LIVE_GLASSES,
            )
        NormalizedCapture(previewBitmap = previewBitmap, investigationEvidence = evidence)
      }

      is PhotoData.HEIC -> {
        val byteArray = ByteArray(photo.data.remaining())
        photo.data.get(byteArray)

        // Extract EXIF transformation matrix and apply to bitmap
        val exifInfo = getExifInfo(byteArray)
        val transform = getTransform(exifInfo)
        val previewBitmap = decodeHeic(byteArray, transform)
        val evidence =
            heicBytesToInvestigationEvidence(
                heicBytes = byteArray,
                slotIndex = 0,
                filename = liveCaptureFilename(slotIndex = 0, extension = "heic"),
                source = InvestigationEvidenceSource.LIVE_GLASSES,
            )
        NormalizedCapture(previewBitmap = previewBitmap, investigationEvidence = evidence)
      }
    }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
  }

  private fun attachDisplayIfAvailable() {
    if (display != null) {
      return
    }

    session
        ?.addDisplay()
        ?.onSuccess { addedDisplay ->
          display = addedDisplay
          displayStateJob?.cancel()
          displayStateJob =
              viewModelScope.launch {
                addedDisplay.state.collect { state ->
                  displayState = state
                  if (state == DisplayState.STARTED) {
                    renderDisplayCaptureControlIfNeeded()
                  }
                }
              }
        }
        ?.onFailure { error, _ ->
          Log.d(TAG, "Display capability unavailable for this session: ${error.description}")
        }
  }

  private suspend fun renderDisplayCaptureControlIfNeeded() {
    val currentDisplay = display ?: return
    if (displayState != DisplayState.STARTED) {
      return
    }

    val model =
        InvestigationDisplayCaptureModelFactory.create(
            investigationActive = investigationActiveForDisplay,
            activeCaptureCount = activeCaptureCountForDisplay,
            hasCaptureCapacity = hasCaptureCapacityForDisplay,
        )

    if (model == lastDisplayCaptureModel) {
      return
    }

    lastDisplayCaptureModel = model
    if (model == null) {
      return
    }

    currentDisplay
        .sendContent {
          flexBox(gap = 12, padding = 24, background = FlexBoxBackground.CARD) {
            text("Investigation", style = TextStyle.HEADING)
            text(model.statusText, style = TextStyle.BODY)
            if (model.showCaptureAction) {
              button(
                  label = "Capture",
                  style = ButtonStyle.PRIMARY,
                  onClick = { onDisplayCaptureAction() },
              )
            }
          }
        }
        .onFailure { error, _ ->
          Log.d(TAG, "Display content update failed: ${error.description}")
        }
  }

  private fun resetUiForNewStart() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionErrorJob?.cancel()
    sessionStateJob?.cancel()
    displayStateJob?.cancel()
    displayStateJob = null
    display = null
    displayState = null
    lastDisplayCaptureModel = null
    investigationActiveForDisplay = false
    activeCaptureCountForDisplay = 0
    hasCaptureCapacityForDisplay = true
    presentationQueue?.stop()
    presentationQueue = null
    previousDeviceSessionState = null
    _uiState.update {
      it.copy(
          videoFrame = null,
          videoFrameCount = 0,
          capturedPhoto = null,
          capturedInvestigationEvidence = null,
          isShareDialogVisible = false,
          isInvestigationPanelVisible = false,
          shouldReturnToInvestigationAfterCapture = false,
          isCapturing = false,
          captureErrorMessage = null,
      )
    }
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
