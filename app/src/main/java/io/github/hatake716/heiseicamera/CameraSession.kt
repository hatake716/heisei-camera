package io.github.hatake716.heiseicamera

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.StreetViewPanorama
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StreetViewPanoramaCamera
import com.google.android.gms.maps.model.StreetViewPanoramaLocation
import com.google.android.gms.maps.model.StreetViewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val panoId: String, val bearing: Float, val tilt: Float, val createdAt: Long)

/** The selected historical panorama stays pinned when the device moves. */
class CameraSession(private val context: Context, private val scope: CoroutineScope) {
    var fix by mutableStateOf<GeoFix?>(null)
        private set
    var direction by mutableStateOf<ViewDirection?>(null)
        private set
    var locationStatus by mutableStateOf("現在地はまだ取得していません")
    var tracking by mutableStateOf(true)
    var selectedPano by mutableStateOf<String?>(null)
        private set
    var currentMode by mutableStateOf(false)
        private set
    var activePano by mutableStateOf<String?>(null)
        private set
    var date by mutableStateOf<CaptureDate?>(null)
        private set
    var dateStatus by mutableStateOf("風景を選ぶと撮影年月を表示します")
        private set
    var message by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set
    var panoramaPosition by mutableStateOf<LatLng?>(null)
        private set
    var bookmarks by mutableStateOf(readBookmarks())
        private set
    var pendingLink by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    private var panorama: StreetViewPanorama? = null
    private var metadataJob: Job? = null
    private var timeoutJob: Job? = null
    private var importJob: Job? = null
    private var selectionVersion = 0L
    private var lastPosition: GeoFix? = null
    private var requestedBearing: Float? = null
    private var requestedTilt: Float? = null
    private val metadata = PanoramaMetadataClient(context, BuildConfig.METADATA_API_KEY)
    private val prefs = context.getSharedPreferences("panorama_bookmarks", Context.MODE_PRIVATE)

    fun attach(value: StreetViewPanorama) {
        panorama = value
        value.isUserNavigationEnabled = false
        value.isStreetNamesEnabled = false
        value.isPanningGesturesEnabled = !tracking
        value.setOnStreetViewPanoramaChangeListener { onPanoramaChanged(it) }
        if (selectedPano != null) requestSelected() else if (currentMode) requestCurrent(force = true)
    }

    fun detach() {
        panorama?.setOnStreetViewPanoramaChangeListener(null)
        panorama = null
        clearImage()
    }

    fun pauseInputs() {
        fix = null
        direction = null
    }

    fun freshFix(): GeoFix? = fix?.takeIf { System.currentTimeMillis() - it.timeMillis in -2_000..30_000 }

    private fun cancelImport() {
        selectionVersion++
        importJob?.cancel()
        importJob = null
        importing = false
    }

    fun onLocation(value: GeoFix) {
        fix = value
        if (currentMode) requestCurrent()
    }

    fun onDirection(value: ViewDirection) {
        direction = value
        if (tracking && activePano != null) applyDirection(value)
    }

    fun toggleTracking() {
        tracking = !tracking
        panorama?.isPanningGesturesEnabled = !tracking
        if (tracking) direction?.let(::applyDirection)
    }

    private fun applyDirection(value: ViewDirection) {
        val view = panorama ?: return
        view.animateTo(StreetViewPanoramaCamera.Builder()
            .bearing(value.bearing).tilt(value.tilt.coerceIn(-90f, 90f))
            .zoom(view.panoramaCamera.zoom).build(), 0)
    }

    fun chooseCurrent() {
        cancelImport()
        selectedPano = null
        currentMode = true
        pendingLink = null
        requestedBearing = null
        requestedTilt = null
        clearImage()
        message = if (fix == null) "現在地を取得しています" else null
        requestCurrent(force = true)
    }

    fun importLink(text: String) {
        cancelImport()
        val version = selectionVersion
        importJob = scope.launch {
            importing = true
            message = "共有リンクを確認しています"
            try {
                val reference = PanoramaLinks.resolve(text)
                if (version != selectionVersion) return@launch
                if (reference == null) {
                    message = "パノラマを特定できませんでした。Google マップのストリートビューを開き、もう一度共有してください。"
                } else {
                    pendingLink = text
                    importJob = null
                    open(reference.panoId)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                if (version == selectionVersion) message = "リンクを読み込めませんでした。通信を確認して再度お試しください。"
            } finally {
                if (version == selectionVersion) importing = false
            }
        }
    }

    fun open(panoId: String, bearing: Float? = null, tilt: Float? = null) {
        cancelImport()
        currentMode = false
        selectedPano = panoId
        requestedBearing = bearing
        requestedTilt = tilt
        if (bearing != null) {
            tracking = false
            panorama?.isPanningGesturesEnabled = true
        }
        clearImage()
        if (!BuildConfig.HAS_MAPS_KEY) {
            message = "リンクを受け取りました。アプリ内表示には Maps API キーを設定したビルドが必要です。"
        } else requestSelected()
    }

    private fun clearImage() {
        metadataJob?.cancel()
        timeoutJob?.cancel()
        activePano = null
        date = null
        panoramaPosition = null
        dateStatus = "撮影年月を確認中"
        loading = false
    }

    private fun startLoading() {
        clearImage()
        loading = true
        message = null
        timeoutJob = scope.launch {
            delay(20_000)
            loading = false
            dateStatus = "撮影年月を確認できません"
            message = "風景を読み込めませんでした。接続や API キーを確認するか、Google マップで開いてください。"
        }
    }

    private fun requestSelected() {
        val view = panorama ?: return
        val id = selectedPano ?: return
        startLoading()
        if (view.location?.panoId == id) onPanoramaChanged(view.location)
        else view.setPosition(id)
    }

    private fun requestCurrent(force: Boolean = false) {
        val view = panorama ?: return
        val position = freshFix() ?: return
        if (loading && !force) return
        val previous = lastPosition
        if (!force && previous != null && (position.timeMillis - previous.timeMillis < 5_000 ||
                    distance(previous.latitude, previous.longitude, position.latitude, position.longitude) < 20)) return
        lastPosition = position
        startLoading()
        view.setPosition(LatLng(position.latitude, position.longitude), 75, StreetViewSource.OUTDOOR)
    }

    private fun onPanoramaChanged(value: StreetViewPanoramaLocation?) {
        // A native SDK callback has no request token. Ignore late responses before
        // touching the active request's timers or its date metadata.
        if (!currentMode && selectedPano == null) return
        // Null callbacks cannot be attributed to a request; only the request timeout
        // reports failure, including when an old null arrives after a newer success.
        if (value == null) return
        if (selectedPano != null && value.panoId != selectedPano) return
        if (currentMode) {
            val requested = lastPosition ?: return
            if (distance(requested.latitude, requested.longitude, value.position.latitude, value.position.longitude) > 100f) return
        }
        timeoutJob?.cancel()
        metadataJob?.cancel()
        date = null
        loading = false
        activePano = value.panoId
        panoramaPosition = value.position
        message = null
        val bearing = requestedBearing
        val tilt = requestedTilt
        if (bearing != null && tilt != null) {
            panorama?.animateTo(StreetViewPanoramaCamera.Builder().bearing(bearing).tilt(tilt).zoom(0f).build(), 0)
            requestedBearing = null
            requestedTilt = null
        } else if (tracking) direction?.let(::applyDirection)
        if (BuildConfig.METADATA_API_KEY.isBlank()) {
            dateStatus = "撮影年月不明 · 年月情報の接続が未設定"
            return
        }
        dateStatus = "撮影年月を確認中"
        val displayedId = value.panoId
        metadataJob = scope.launch {
            when (val result = metadata.fetch(displayedId)) {
                is MetadataResult.Success -> if (activePano == displayedId) {
                    date = result.date
                    dateStatus = result.date?.eraLabel ?: "撮影年月不明"
                }
                is MetadataResult.Failure -> if (activePano == displayedId) {
                    dateStatus = "撮影年月不明"
                    message = result.message
                }
            }
        }
    }

    fun retry() {
        if (selectedPano != null) requestSelected() else if (currentMode) requestCurrent(force = true)
    }

    fun saveBookmark(): Boolean {
        val id = activePano ?: return false
        val camera = panorama?.panoramaCamera ?: return false
        val entry = Bookmark(id, camera.bearing, camera.tilt, System.currentTimeMillis())
        bookmarks = (listOf(entry) + bookmarks.filterNot { it.panoId == id }).take(100)
        persistBookmarks()
        return true
    }

    fun deleteBookmark(value: Bookmark) {
        bookmarks = bookmarks.filterNot { it == value }
        persistBookmarks()
    }

    private fun persistBookmarks() {
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("id", it.panoId).put("bearing", it.bearing)
            .put("tilt", it.tilt).put("createdAt", it.createdAt)) }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun readBookmarks(): List<Bookmark> = runCatching {
        val storage = context.getSharedPreferences("panorama_bookmarks", Context.MODE_PRIVATE)
        val entries = JSONArray(storage.getString("entries", "[]"))
        (0 until minOf(entries.length(), 100)).mapNotNull { index ->
            runCatching {
                val item = entries.getJSONObject(index)
                Bookmark(item.getString("id"), item.getDouble("bearing").toFloat(),
                    item.getDouble("tilt").toFloat(), item.getLong("createdAt"))
            }.getOrNull()?.takeIf { PanoramaLinks.validPanoId(it.panoId) && it.bearing.isFinite() &&
                it.tilt.isFinite() && it.tilt in -90f..90f && it.createdAt > 0 }
        }.distinctBy { it.panoId }
    }.getOrDefault(emptyList())

    fun distanceToPanorama(): Int? {
        val a = freshFix() ?: return null
        val b = panoramaPosition ?: return null
        return distance(a.latitude, a.longitude, b.latitude, b.longitude).toInt()
    }

    companion object {
        private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val result = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, result)
            return result[0]
        }
    }
}
