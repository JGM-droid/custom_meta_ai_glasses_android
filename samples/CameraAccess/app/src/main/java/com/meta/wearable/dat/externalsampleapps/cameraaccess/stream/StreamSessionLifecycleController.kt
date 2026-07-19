package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

internal class StreamSessionLifecycleController {

  enum class StartDecision {
    IGNORE,
    START_WITH_EXISTING_SESSION,
    CREATE_SESSION,
  }

  private var currentStartToken: Long? = null
  private var nextToken: Long = 0L
  private var stopInProgress = false
  private var ownsSession = false

  @Synchronized
  fun requestStart(hasSessionReference: Boolean, hasActivePipeline: Boolean): Pair<StartDecision, Long?> {
    if (stopInProgress || currentStartToken != null) {
      return StartDecision.IGNORE to null
    }

    if (hasSessionReference || ownsSession) {
      ownsSession = true
      if (hasActivePipeline) {
        return StartDecision.IGNORE to null
      }
      val token = nextToken()
      currentStartToken = token
      return StartDecision.START_WITH_EXISTING_SESSION to token
    }

    val token = nextToken()
    currentStartToken = token
    return StartDecision.CREATE_SESSION to token
  }

  @Synchronized
  fun shouldAcceptCreateResult(token: Long): Boolean {
    return !stopInProgress && currentStartToken == token
  }

  @Synchronized
  fun onSessionCreated(token: Long) {
    if (currentStartToken == token && !stopInProgress) {
      ownsSession = true
    }
  }

  @Synchronized
  fun onCreateFailed(token: Long) {
    if (currentStartToken == token) {
      currentStartToken = null
      ownsSession = false
    }
  }

  @Synchronized
  fun onStartAttached(token: Long) {
    if (currentStartToken == token) {
      currentStartToken = null
    }
  }

  @Synchronized
  fun requestStop(hasSessionReference: Boolean, hasStreamReference: Boolean): Boolean {
    if (stopInProgress) {
      return false
    }

    val hasOwnedState = ownsSession || currentStartToken != null
    if (!hasOwnedState && !hasSessionReference && !hasStreamReference) {
      return false
    }

    stopInProgress = true
    currentStartToken = null
    ownsSession = false
    nextToken()
    return true
  }

  @Synchronized
  fun onStopCompleted() {
    stopInProgress = false
  }

  @Synchronized
  fun isStopInProgress(): Boolean = stopInProgress

  private fun nextToken(): Long {
    nextToken += 1L
    return nextToken
  }
}