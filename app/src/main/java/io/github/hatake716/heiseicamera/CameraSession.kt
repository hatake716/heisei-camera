package io.github.hatake716.heiseicamera

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A saved selection, never a claim about the embedded viewer's current image or camera pose. */
data class Bookmark(val panoId: String, val createdAt: Long)

class CameraSession(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("panorama_bookmarks", Context.MODE_PRIVATE)
    private var viewer by mutableStateOf(CameraViewerState())
    var selectedPano by mutableStateOf(readLastSelection())
        private set
    var isAutomatic by mutableStateOf(true)
        private set
    val target: StreetViewTarget? get() = viewer.target
    val viewEpoch: Int get() = viewer.epoch
    val pageState: EmbedPageState get() = viewer.pageState

    var message by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set
    /** Saved only with Activity state so interrupted short-link resolution can be resumed. */
    var pendingImportLink by mutableStateOf<String?>(null)
        private set
    /** A successful import can open the result once, including after Activity recreation. */
    var importedSelectionPending by mutableStateOf(false)
        private set
    var bookmarks by mutableStateOf(readBookmarks())
        private set

    private var importVersion = 0L
    private var importJob: Job? = null

    private fun cancelImport() {
        importVersion++
        importJob?.cancel()
        importJob = null
        importing = false
        pendingImportLink = null
        importedSelectionPending = false
    }

    fun importLink(text: String) {
        cancelImport()
        val version = importVersion
        importing = true
        pendingImportLink = text
        message = "共有リンクを確認しています"
        importJob = scope.launch {
            var completed = false
            try {
                val reference = PanoramaLinks.resolve(text)
                completed = true
                if (version != importVersion) return@launch
                if (reference == null) {
                    message = "パノラマを特定できませんでした。Google マップのストリートビューを開き、もう一度共有してください。"
                } else {
                    // open() invalidates earlier imports without cancelling this successful job.
                    importJob = null
                    open(reference.panoId)
                    importedSelectionPending = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                completed = true
                if (version == importVersion) {
                    message = "リンクを読み込めませんでした。通信を確認して再度お試しください。"
                }
            } finally {
                if (version == importVersion) {
                    importing = false
                    // Coroutine cancellation during Activity teardown must retain this for Saver.
                    if (completed) pendingImportLink = null
                }
            }
        }
    }

    fun open(panoId: String) {
        cancelImport()
        if (!PanoramaLinks.validPanoId(panoId)) {
            message = "このリンクのパノラマを開けません。Google マップからもう一度共有してください。"
            return
        }
        selectedPano = panoId
        isAutomatic = false
        viewer = viewer.select(StreetViewTarget.Panorama(panoId))
        prefs.edit().putString("selected_pano", panoId).apply()
        message = "風景を選びました。"
    }

    fun retry() {
        target?.let { viewer = viewer.select(it) }
        message = null
    }

    fun selectAutomatic() {
        cancelImport()
        isAutomatic = true
        viewer = viewer.select(null)
        message = null
    }

    /** Restore only a request consistent with the restored mode and manual selection. */
    fun restoreTarget(value: StreetViewTarget) {
        val matchesMode = when (value) {
            is StreetViewTarget.Nearby -> isAutomatic
            is StreetViewTarget.Panorama -> !isAutomatic && selectedPano == value.panoId
        }
        if (matchesMode) viewer = viewer.select(value)
    }

    /** Call after restoring the mode and target, before restoring any newer pending import. */
    fun restoreImportedSelectionPending(pending: Boolean) {
        importedSelectionPending = pending && hasSelectedPanorama()
    }

    /** The UI shows the result only when this succeeds; stale or cancelled events are discarded. */
    fun consumeImportedSelection(): Boolean {
        val shouldShow = importedSelectionPending && hasSelectedPanorama()
        importedSelectionPending = false
        return shouldShow
    }

    private fun hasSelectedPanorama(): Boolean {
        val panorama = target as? StreetViewTarget.Panorama ?: return false
        return !isAutomatic && !importing && panorama.panoId == selectedPano
    }

    fun markPageState(target: StreetViewTarget, epoch: Int, state: EmbedPageState) {
        if (!viewer.matches(target, epoch)) return
        viewer = viewer.pageChanged(target, epoch, state)
    }

    /** Opens a read-only live WebView result; no bitmap or image file is created. */
    fun shutter(location: SceneLocation? = null): Boolean {
        if (importing) return false
        val selected = shutterTarget(isAutomatic, selectedPano, location) ?: return false
        importedSelectionPending = false
        // Each shutter press creates a fresh iframe, including repeated requests for one place.
        viewer = viewer.select(selected)
        message = null
        return true
    }

    fun resume() {
        cancelImport()
        viewer = viewer.select(null)
        message = null
    }

    fun saveBookmark(): Boolean {
        if (isAutomatic) return false
        val id = (target as? StreetViewTarget.Panorama)?.panoId ?: return false
        if (id != selectedPano) return false
        val entry = Bookmark(id, System.currentTimeMillis())
        bookmarks = (listOf(entry) + bookmarks.filterNot { it.panoId == id }).take(100)
        persistBookmarks()
        message = "選んだ風景をしおりに残しました。"
        return true
    }

    fun deleteBookmark(value: Bookmark) {
        bookmarks = bookmarks.filterNot { it == value }
        persistBookmarks()
    }

    private fun persistBookmarks() {
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("id", it.panoId).put("createdAt", it.createdAt)) }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun readBookmarks(): List<Bookmark> = runCatching {
        val storage = context.getSharedPreferences("panorama_bookmarks", Context.MODE_PRIVATE)
        val entries = JSONArray(storage.getString("entries", "[]"))
        (0 until minOf(entries.length(), 100)).mapNotNull { index ->
            runCatching {
                val item = entries.getJSONObject(index)
                // Keep the original ID and registration timestamp; ignore the old SDK pose.
                Bookmark(item.getString("id"), item.getLong("createdAt"))
            }.getOrNull()?.takeIf { PanoramaLinks.validPanoId(it.panoId) && it.createdAt > 0 }
        }.distinctBy { it.panoId }
    }.getOrDefault(emptyList())

    private fun readLastSelection(): String? = runCatching {
        prefs.getString("selected_pano", null)?.takeIf(PanoramaLinks::validPanoId)
    }.getOrNull()
}
