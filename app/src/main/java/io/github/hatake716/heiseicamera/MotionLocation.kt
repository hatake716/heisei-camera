package io.github.hatake716.heiseicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double,
    val timeMillis: Long,
)

/** Bearing is clockwise from true north; tilt is the back camera's elevation. */
data class ViewDirection(val bearing: Float, val tilt: Float, val accuracy: Int)

/**
 * Foreground-only device inputs. The activity owns permissions and must pair
 * start()/stop() with its visible lifecycle. All callbacks run on the main thread.
 * Calling start() again refreshes location subscriptions after permission changes.
 */
class MotionLocation(
    context: Context,
    private val onLocation: (GeoFix) -> Unit,
    private val onDirection: (ViewDirection) -> Unit,
    private val onStatus: (String) -> Unit,
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)

    val hasOrientationSensor: Boolean get() = rotationSensor != null

    private var running = false
    private var lastLocation: Location? = null
    private var magneticDeclination = 0f
    private var smoothedBearing: Float? = null
    private var smoothedTilt: Float? = null
    private var lastSampleNanos = 0L
    private var lastEmissionNanos = 0L
    private var lastStatus: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (running) acceptLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            if (running) status("現在地を確認しています")
        }

        override fun onProviderDisabled(provider: String) {
            if (running && !anyEnabledProvider()) {
                status("端末の位置情報をオンにしてください")
            }
        }

        // Implement explicitly for Android 10, where this is not a default method.
        @Deprecated("Required for the Android 10 LocationListener interface")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun start() {
        if (!running) {
            running = true
            smoothedBearing = null
            smoothedTilt = null
            lastSampleNanos = 0L
            lastEmissionNanos = 0L
            lastStatus = null
            val sensor = rotationSensor
            if (sensor == null) {
                status("方位センサーがありません。画面をドラッグして見回せます")
            } else if (sensorManager?.registerListener(
                    this, sensor, SensorManager.SENSOR_DELAY_GAME, mainHandler,
                ) != true
            ) {
                status("方位センサーを開始できません。画面をドラッグして見回せます")
            }
        }
        subscribeToLocation()
    }

    fun stop() {
        running = false
        sensorManager?.unregisterListener(this)
        // Permission can be revoked between start and stop.
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // No permission means no further location access is possible.
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToLocation() {
        val manager = locationManager ?: run {
            status("この端末では位置情報を取得できません")
            return
        }
        try {
            manager.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Re-check the current permission below.
        }
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            status("現在地の表示には位置情報の許可が必要です")
            return
        }

        val available = manager.allProviders
        val providers = buildList {
            if (fine && LocationManager.GPS_PROVIDER in available) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in available) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            status(if (fine) "利用できる位置情報プロバイダーがありません" else "正確な位置情報を許可してください")
            return
        }

        var registered = 0
        val cached = mutableListOf<Location>()
        providers.forEach { provider ->
            try {
                manager.getLastKnownLocation(provider)?.takeIf(::isUsableFix)?.let(cached::add)
                // Register even when disabled so enabling it while visible resumes updates.
                manager.requestLocationUpdates(provider, 1_000L, 1f, locationListener, Looper.getMainLooper())
                registered += 1
            } catch (_: SecurityException) {
                // Permission or provider access can change while the activity is visible.
            } catch (_: IllegalArgumentException) {
                // Some devices do not implement an advertised network provider.
            }
        }

        when {
            registered == 0 -> status("現在地を取得できません。位置情報の設定を確認してください")
            !anyEnabledProvider() -> status("端末の位置情報をオンにしてください")
            else -> status("現在地を確認しています")
        }
        // A recent accurate fix is a useful starting point; old caches are never emitted.
        cached.minByOrNull { it.accuracy + locationAgeMillis(it).coerceAtLeast(0) / 1_000f }
            ?.let(::acceptLocation)
    }

    private fun acceptLocation(candidate: Location) {
        if (!isUsableFix(candidate)) {
            if (locationAgeMillis(candidate) in 0..MAX_FIX_AGE_MILLIS &&
                candidate.hasAccuracy() && candidate.accuracy > MAX_ACCURACY_METERS
            ) {
                status("位置の精度を確認中です。正確な位置情報をオンにしてください")
            }
            return
        }
        val previous = lastLocation
        if (previous != null && isUsableFix(previous)) {
            val newerBy = fixElapsedMillis(candidate) - fixElapsedMillis(previous)
            if (newerBy <= 0L) return
            // Avoid jumping from a precise GPS fix to a worse network estimate.
            val accuracyLimit = max(previous.accuracy * 1.5f, previous.accuracy + 10f)
            if (newerBy < PREFER_ACCURATE_FOR_MILLIS && candidate.accuracy > accuracyLimit) return
        }
        val accepted = Location(candidate)
        lastLocation = accepted
        val altitude = accepted.altitude.takeIf { accepted.hasAltitude() && it.isFinite() } ?: 0.0
        val timestamp = accepted.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        magneticDeclination = GeomagneticField(
            accepted.latitude.toFloat(), accepted.longitude.toFloat(), altitude.toFloat(), timestamp,
        ).declination
        onLocation(GeoFix(accepted.latitude, accepted.longitude, accepted.accuracy, altitude, timestamp))
        status("現在地を取得しました")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != rotationSensor?.type) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // Rotation matrices map device axes to east/north/up. A back-facing
        // camera looks along -Z; this normal is unchanged by screen rotation.
        val east = -rotationMatrix[2]
        val north = -rotationMatrix[5]
        val up = -rotationMatrix[8]
        if (!east.isFinite() || !north.isFinite() || !up.isFinite()) return

        val previousBearing = smoothedBearing
        val bearing = if (hypot(east, north) > MIN_HORIZONTAL_COMPONENT) {
            normalizeDegrees(Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat() + magneticDeclination)
        } else {
            // Azimuth is undefined when pointing straight up/down; keep the last heading.
            previousBearing ?: return
        }
        val tilt = Math.toDegrees(asin(up.toDouble().coerceIn(-1.0, 1.0))).toFloat()
        val elapsed = if (lastSampleNanos == 0L) 0.0 else (event.timestamp - lastSampleNanos) / 1_000_000_000.0
        val weight = if (elapsed <= 0.0 || previousBearing == null) 1f
        else (1.0 - exp(-elapsed / SMOOTHING_SECONDS)).toFloat().coerceIn(0f, 1f)
        val filteredBearing = previousBearing?.let {
            val shortestDelta = ((bearing - it + 540f) % 360f) - 180f
            normalizeDegrees(it + shortestDelta * weight)
        } ?: bearing
        val filteredTilt = smoothedTilt?.let { it + (tilt - it) * weight } ?: tilt
        smoothedBearing = filteredBearing
        smoothedTilt = filteredTilt
        lastSampleNanos = event.timestamp
        if (lastEmissionNanos != 0L && event.timestamp - lastEmissionNanos < EMISSION_INTERVAL_NANOS) return
        lastEmissionNanos = event.timestamp
        onDirection(ViewDirection(filteredBearing, filteredTilt, event.accuracy))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (running && sensor?.type == rotationSensor?.type && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            status("方位を調整中です。端末をゆっくり動かしてください")
        }
    }

    private fun isUsableFix(location: Location): Boolean =
        location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.hasAccuracy() && location.accuracy.isFinite() &&
            location.accuracy in 0f..MAX_ACCURACY_METERS &&
            locationAgeMillis(location) in -2_000L..MAX_FIX_AGE_MILLIS

    private fun fixElapsedMillis(location: Location): Long =
        if (location.elapsedRealtimeNanos > 0L) location.elapsedRealtimeNanos / 1_000_000L
        else SystemClock.elapsedRealtime() - (System.currentTimeMillis() - location.time)

    private fun locationAgeMillis(location: Location): Long =
        SystemClock.elapsedRealtime() - fixElapsedMillis(location)

    private fun anyEnabledProvider(): Boolean = buildList {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
    }
        .any { provider ->
            try {
                locationManager?.isProviderEnabled(provider) == true
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    private fun hasPermission(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun status(message: String) {
        if (lastStatus == message) return
        lastStatus = message
        onStatus(message)
    }

    private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

    private companion object {
        const val MAX_FIX_AGE_MILLIS = 30_000L
        const val MAX_ACCURACY_METERS = 250f
        const val PREFER_ACCURATE_FOR_MILLIS = 15_000L
        const val EMISSION_INTERVAL_NANOS = 33_333_334L
        const val SMOOTHING_SECONDS = 0.12
        const val MIN_HORIZONTAL_COMPONENT = 0.05f
    }
}
