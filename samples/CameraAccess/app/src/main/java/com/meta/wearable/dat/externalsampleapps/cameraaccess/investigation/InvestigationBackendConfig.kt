package com.meta.wearable.dat.externalsampleapps.cameraaccess.investigation

import android.os.Build
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.net.URI

internal data class InvestigationBackendUrlValidation(
    val isAllowed: Boolean,
    val errorCategory: String? = null,
    val message: String? = null,
)

internal object InvestigationBackendConfig {
  const val DEFAULT_BASE_URL = "http://10.0.2.2:8001"

  private const val EMULATOR_HOST_ALIAS = "10.0.2.2"
  private const val INVALID_BACKEND_URL_CATEGORY = "backend_unreachable_configuration"
  private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")

  fun resolveBaseUrl(rawBaseUrl: String = BuildConfig.INVESTIGATION_BACKEND_BASE_URL): String {
    val normalized = rawBaseUrl.trim()
    return if (normalized.isNotEmpty()) normalized else DEFAULT_BASE_URL
  }

  fun looksLikeTemporaryTunnel(baseUrl: String): Boolean {
    val normalized = baseUrl.trim().lowercase()
    return normalized.contains("cloudflare") || normalized.contains("ngrok") || normalized.contains("tunnel")
  }

  fun validateConnectivityBaseUrl(
      baseUrl: String,
      isEmulator: Boolean = isProbablyAndroidEmulator(),
  ): InvestigationBackendUrlValidation {
    return validateBaseUrlForAndroidRuntime(baseUrl = baseUrl, isEmulator = isEmulator)
  }

  fun validateSubmissionBaseUrl(
      baseUrl: String,
      isEmulator: Boolean = isProbablyAndroidEmulator(),
  ): InvestigationBackendUrlValidation {
    return validateBaseUrlForAndroidRuntime(baseUrl = baseUrl, isEmulator = isEmulator)
  }

  fun isProbablyAndroidEmulator(
      fingerprint: String = Build.FINGERPRINT,
      model: String = Build.MODEL,
      manufacturer: String = Build.MANUFACTURER,
      brand: String = Build.BRAND,
      device: String = Build.DEVICE,
      product: String = Build.PRODUCT,
      hardware: String = Build.HARDWARE,
  ): Boolean {
    val normalizedFingerprint = fingerprint.lowercase()
    val normalizedModel = model.lowercase()
    val normalizedManufacturer = manufacturer.lowercase()
    val normalizedBrand = brand.lowercase()
    val normalizedDevice = device.lowercase()
    val normalizedProduct = product.lowercase()
    val normalizedHardware = hardware.lowercase()

    return normalizedFingerprint.startsWith("generic") ||
        normalizedFingerprint.contains("emulator") ||
        normalizedFingerprint.contains("virtual") ||
        normalizedModel.contains("android sdk built for") ||
        normalizedModel.contains("emulator") ||
        normalizedModel.contains("sdk_gphone") ||
        normalizedManufacturer.contains("genymotion") ||
        (normalizedBrand.startsWith("generic") && normalizedDevice.startsWith("generic")) ||
        normalizedProduct.contains("sdk_gphone") ||
        normalizedProduct.contains("emulator") ||
        normalizedHardware.contains("goldfish") ||
        normalizedHardware.contains("ranchu")
  }

  private fun validateBaseUrlForAndroidRuntime(
      baseUrl: String,
      isEmulator: Boolean,
  ): InvestigationBackendUrlValidation {
    val host =
        try {
          URI(baseUrl.trim()).host?.lowercase()
        } catch (_: Exception) {
          null
        }

    if (host in loopbackHosts) {
      return InvestigationBackendUrlValidation(
          isAllowed = false,
          errorCategory = INVALID_BACKEND_URL_CATEGORY,
          message =
              "Backend URL points to a device-local loopback host. Use 10.0.2.2 on the Android emulator, or a LAN IP / HTTPS tunnel on a physical phone.",
      )
    }

    if (host == EMULATOR_HOST_ALIAS && !isEmulator) {
      return InvestigationBackendUrlValidation(
          isAllowed = false,
          errorCategory = INVALID_BACKEND_URL_CATEGORY,
          message =
              "Backend URL points to the Android emulator host alias. Use a LAN IP or an HTTPS tunnel URL on a physical phone.",
      )
    }

    return InvestigationBackendUrlValidation(isAllowed = true)
  }
}
