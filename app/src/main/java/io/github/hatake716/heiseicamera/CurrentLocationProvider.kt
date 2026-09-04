package io.github.hatake716.heiseicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface CurrentLocationResult {
    data class Success(val location: SceneLocation, val accuracyMeters: Float) : CurrentLocationResult
    data object PermissionDenied : CurrentLocationResult
    data object Disabled : CurrentLocationResult
    data object Unavailable : CurrentLocationResult
}

/** Foreground single fix. No cached location is read, persisted, or used as a fallback. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): CurrentLocationResult = withContext(Dispatchers.Main.immediate) {
    val appContext = context.applicationContext
    if (!hasLocationPermission(appContext)) return@withContext CurrentLocationResult.PermissionDenied
    val manager = appContext.getSystemService(LocationManager::class.java)
        ?: return@withContext CurrentLocationResult.Unavailable
    val providers = try {
        if (!manager.isLocationEnabled) return@withContext CurrentLocationResult.Disabled
        // "fused" is the platform provider name, also usable on devices below API 31 when present.
        val available = manager.getProviders(true).toSet()
        listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in available }
    } catch (_: SecurityException) {
        return@withContext CurrentLocationResult.PermissionDenied
    } catch (_: RuntimeException) {
        return@withContext CurrentLocationResult.Unavailable
    }
    if (providers.isEmpty()) return@withContext CurrentLocationResult.Unavailable

    val startedNanos = SystemClock.elapsedRealtimeNanos()
    var listener: LocationListener? = null
    try {
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                val registered = mutableSetOf<String>()
                val callbacks = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!continuation.isActive) return
                        if (!hasLocationPermission(appContext)) {
                            continuation.resume(CurrentLocationResult.PermissionDenied)
                            return
                        }
                        val accuracy = location.accuracy.takeIf { location.hasAccuracy() }
                        if (isFreshLocationFix(location.latitude, location.longitude, accuracy,
                                location.elapsedRealtimeNanos, startedNanos, SystemClock.elapsedRealtimeNanos())) {
                            continuation.resume(CurrentLocationResult.Success(
                                SceneLocation(location.latitude, location.longitude), checkNotNull(accuracy),
                            ))
                        }
                    }

                    override fun onProviderDisabled(provider: String) {
                        registered.remove(provider)
                        if (registered.isEmpty() && continuation.isActive) {
                            continuation.resume(if (hasLocationPermission(appContext)) CurrentLocationResult.Disabled
                            else CurrentLocationResult.PermissionDenied)
                        }
                    }

                    override fun onProviderEnabled(provider: String) = Unit

                    @Deprecated("Required callback for Android 10 and earlier")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                listener = callbacks
                var permissionFailure = false
                for (provider in providers) {
                    if (!continuation.isActive) break
                    try {
                        manager.requestLocationUpdates(provider, 0L, 0f, callbacks, Looper.getMainLooper())
                        registered += provider
                    } catch (_: SecurityException) {
                        // A coarse-only grant can reject one provider while allowing another.
                        permissionFailure = true
                    } catch (_: IllegalArgumentException) {
                        // A provider may disappear between discovery and registration.
                    } catch (_: RuntimeException) {
                        // Device/provider failure is recoverable through the user's retry action.
                    }
                }
                if (registered.isEmpty() && continuation.isActive) {
                    continuation.resume(if (permissionFailure) CurrentLocationResult.PermissionDenied
                    else CurrentLocationResult.Unavailable)
                }
            }
        } ?: when {
            !hasLocationPermission(appContext) -> CurrentLocationResult.PermissionDenied
            !runCatching { manager.isLocationEnabled }.getOrDefault(true) -> CurrentLocationResult.Disabled
            else -> CurrentLocationResult.Unavailable
        }
    } finally {
        // Runs on the main dispatcher after setup, including timeout/cancellation. Registering a
        // provider cannot race past cleanup and leave a listener active after the request ends.
        listener?.let { callbacks -> runCatching { manager.removeUpdates(callbacks) } }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Compare monotonic timestamps so wall-clock changes never make an old fix appear current. */
internal fun isFreshLocationFix(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float?,
    fixElapsedRealtimeNanos: Long,
    requestStartedElapsedRealtimeNanos: Long,
    nowElapsedRealtimeNanos: Long,
): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
    longitude.isFinite() && longitude in -180.0..180.0 &&
    accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters >= 0f &&
    requestStartedElapsedRealtimeNanos > 0 && nowElapsedRealtimeNanos >= requestStartedElapsedRealtimeNanos &&
    fixElapsedRealtimeNanos in requestStartedElapsedRealtimeNanos..nowElapsedRealtimeNanos
