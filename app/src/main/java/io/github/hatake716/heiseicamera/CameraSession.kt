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
    private var viewer by mutableStateOf(CameraViewerState(selectedPano = readLastSelection()))
    val selectedPano: String? get() = viewer.selectedPano
    val viewEpoch: Int get() = viewer.epoch
    val pageState: EmbedPageState get() = viewer.pageState

    var message by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set
    /** Saved only with Activity state so interrupted short-link resolution can be resumed. */
    var pendingImportLink by mutableStateOf<String?>(null)
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
        viewer = viewer.select(panoId)
        prefs.edit().putString("selected_pano", panoId).apply()
        message = "過去の風景を選びました。"
    }

    fun retry() {
        selectedPano?.let(::open)
    }

    fun markPageState(panoId: String, epoch: Int, state: EmbedPageState) {
        if (!viewer.matches(panoId, epoch)) return
        viewer = viewer.pageChanged(panoId, epoch, state)
    }

    /** Opens a read-only live WebView result; no bitmap or image file is created. */
    fun shutter(): Boolean {
        if (importing) return false
        val id = selectedPano ?: return false
        // Each camera-to-past transition creates a fresh iframe, including the same selection.
        viewer = viewer.select(id)
        message = null
        return true
    }

    fun resume() {
        viewer = selectedPano?.let(viewer::select) ?: viewer
        message = null
    }

    fun saveBookmark(): Boolean {
        val id = selectedPano ?: return false
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
