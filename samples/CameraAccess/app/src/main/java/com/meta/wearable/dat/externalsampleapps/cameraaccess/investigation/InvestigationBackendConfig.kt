package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig

internal object InvestigationBackendConfig {
  const val DEFAULT_BASE_URL = "http://10.0.2.2:8001"

  fun resolveBaseUrl(rawBaseUrl: String = BuildConfig.INVESTIGATION_BACKEND_BASE_URL): String {
    val normalized = rawBaseUrl.trim()
    return if (normalized.isNotEmpty()) normalized else DEFAULT_BASE_URL
  }

  fun looksLikeTemporaryTunnel(baseUrl: String): Boolean {
    val normalized = baseUrl.trim().lowercase()
    return normalized.contains("cloudflare") || normalized.contains("ngrok") || normalized.contains("tunnel")
  }
}
