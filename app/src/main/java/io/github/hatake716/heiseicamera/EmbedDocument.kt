package io.github.hatake716.heiseicamera

import androidx.compose.runtime.Immutable
import java.net.URLEncoder

/** The view direction is an initial value; Maps Embed does not expose the user's later pose. */
@Immutable
data class EmbedRequest(
    val target: StreetViewTarget,
    val apiKey: String,
    val heading: Float? = null,
    val pitch: Float? = null,
    val fov: Float? = null,
)

internal object EmbedDocument {
    // An Android in-app origin, not a hosted website or a claimed developer-owned domain.
    const val BASE_URL = "https://appassets.androidplatform.net/heisei-camera/viewer.html"
    private const val ENDPOINT = "https://www.google.com/maps/embed/v1/streetview"

    fun url(request: EmbedRequest): String {
        require(request.apiKey.isNotBlank()) { "Maps Embed API key is required" }
        require(request.heading == null || request.heading.isFinite() && request.heading in -180f..360f)
        require(request.pitch == null || request.pitch.isFinite() && request.pitch in -90f..90f)
        require(request.fov == null || request.fov.isFinite() && request.fov in 10f..100f)
        val parameters = linkedMapOf("key" to request.apiKey)
        when (val target = request.target) {
            is StreetViewTarget.Panorama -> parameters["pano"] = target.panoId
            is StreetViewTarget.Nearby -> parameters["location"] =
                "${target.location.latitude},${target.location.longitude}"
        }
        request.heading?.let { parameters["heading"] = it.toString() }
        request.pitch?.let { parameters["pitch"] = it.toString() }
        request.fov?.let { parameters["fov"] = it.toString() }
        // Targets are exclusive: an unavailable historical ID never falls back to a nearby scene.
        return ENDPOINT + parameters.entries.joinToString("&", prefix = "?") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    fun html(request: EmbedRequest): String = """
        <!doctype html>
        <html lang="ja">
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src https://www.google.com; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>Google ストリートビュー</title>
            <style>
              html, body { width:100%; height:100%; margin:0; background:#101815; overflow:hidden; }
              iframe { display:block; width:100%; height:100%; min-width:200px; min-height:200px; border:0; }
            </style>
          </head>
          <body>
            <iframe title="Google ストリートビュー" src="${escapeAttribute(url(request))}"
              referrerpolicy="strict-origin-when-cross-origin"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups"></iframe>
          </body>
        </html>
    """.trimIndent()

    // There is deliberately no fullscreen permission: native shutter controls remain available.
    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
