package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation.InvestigationSpeechEvent

internal interface InvestigationSpeechRecognizerController {
  fun startListening(onEvent: (InvestigationSpeechEvent) -> Unit): Boolean

  fun cancel()

  fun destroy()
}

internal fun createInvestigationSpeechRecognizerController(
    context: Context,
): InvestigationSpeechRecognizerController? {
  if (!SpeechRecognizer.isRecognitionAvailable(context)) {
    return null
  }
  return AndroidInvestigationSpeechRecognizerController(context)
}

private class AndroidInvestigationSpeechRecognizerController(
    context: Context,
) : InvestigationSpeechRecognizerController {
  private val appContext = context.applicationContext
  private var recognizer: SpeechRecognizer = createRecognizer(appContext)
  private var isListening: Boolean = false
  private var callback: ((InvestigationSpeechEvent) -> Unit)? = null

  private val recognitionListener: RecognitionListener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          callback?.invoke(InvestigationSpeechEvent.Ready)
        }

        override fun onBeginningOfSpeech() {
          callback?.invoke(InvestigationSpeechEvent.Listening)
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
          isListening = false
          callback?.invoke(error.toSpeechEvent())
        }

        override fun onResults(results: Bundle?) {
          isListening = false
          val transcript =
              results
                  ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  ?.firstOrNull { !it.isNullOrBlank() }
                  ?.trim()
          if (transcript.isNullOrBlank()) {
            callback?.invoke(InvestigationSpeechEvent.NoMatch)
            return
          }
          callback?.invoke(InvestigationSpeechEvent.FinalTranscript(transcript))
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }

  init {
    recognizer.setRecognitionListener(recognitionListener)
  }

  override fun startListening(onEvent: (InvestigationSpeechEvent) -> Unit): Boolean {
    if (isListening) {
      onEvent(InvestigationSpeechEvent.Busy)
      return false
    }

    callback = onEvent
    return try {
      recognizer.startListening(buildRecognizerIntent())
      isListening = true
      onEvent(InvestigationSpeechEvent.StartRequested)
      true
    } catch (_: SecurityException) {
      isListening = false
      onEvent(InvestigationSpeechEvent.PermissionDenied)
      false
    } catch (_: IllegalStateException) {
      isListening = false
      onEvent(InvestigationSpeechEvent.Busy)
      false
    } catch (error: Throwable) {
      isListening = false
      onEvent(InvestigationSpeechEvent.UnknownError(error.message ?: "Speech recognition failed."))
      false
    }
  }

  override fun cancel() {
    if (isListening) {
      recognizer.cancel()
      isListening = false
      callback?.invoke(InvestigationSpeechEvent.Cancelled)
    }
  }

  override fun destroy() {
    isListening = false
    callback = null
    recognizer.destroy()
  }

  private fun createRecognizer(context: Context): SpeechRecognizer {
    return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
      SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
      SpeechRecognizer.createSpeechRecognizer(context)
    }
  }

  private fun buildRecognizerIntent(): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
  }

  private fun Int.toSpeechEvent(): InvestigationSpeechEvent {
    return when (this) {
      SpeechRecognizer.ERROR_NO_MATCH -> InvestigationSpeechEvent.NoMatch
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> InvestigationSpeechEvent.Timeout
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> InvestigationSpeechEvent.Busy
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> InvestigationSpeechEvent.PermissionDenied
      SpeechRecognizer.ERROR_CLIENT -> InvestigationSpeechEvent.Cancelled
      else -> InvestigationSpeechEvent.UnknownError("Speech recognition failed. Try again.")
    }
  }
}