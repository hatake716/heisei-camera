package io.github.hatake716.heiseicamera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class PanoramaReference(val panoId: String)

/** Accepts user-shared Maps links, without fetching imagery or looking up its history. */
object PanoramaLinks {
    private const val MAX_TEXT_LENGTH = 32_768
    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MILLIS = 5_000
    private val urlPattern = Regex("https://[^\\s<>\"'「」『』]+", RegexOption.IGNORE_CASE)
    private val panoPattern = Regex("[A-Za-z0-9_:/-]{1,512}")
    private val panoramaField = Regex("!1s([^!?#&]+)!2e[0-9]+(?=!|$)")
    private val alternatePanoramaField = Regex("!1s([^!?#&]+)(?=!|$)")
    private val streetViewMarker = Regex("!1e[13](?=!|$)")

    fun parse(text: String): PanoramaReference? = candidates(text)
        .firstNotNullOfOrNull { parseUri(it) }

    /**
     * Only Maps short links initiate network access. Each redirect is checked before opening it.
     * HTML pages, scripts and undocumented history services are deliberately not inspected.
     */
    suspend fun resolve(text: String): PanoramaReference? = withContext(Dispatchers.IO) {
        parse(text)?.let { return@withContext it }
        for (candidate in candidates(text).filter(::isShortLink)) {
            coroutineContext.ensureActive()
            val result = expandShortLink(candidate)
            if (result != null) return@withContext result
        }
        null
    }

    internal fun validPanoId(value: String): Boolean = panoPattern.matches(value)

    internal fun isTrustedMapsUri(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            uri.port !in listOf(-1, 443)
        ) return false
        val path = uri.path ?: return false
        return when (uri.host?.lowercase(Locale.ROOT)) {
            "google.com", "www.google.com" -> path == "/maps" || path.startsWith("/maps/")
            "maps.google.com" -> path.isEmpty() || path.startsWith("/")
            "maps.app.goo.gl" -> path.length > 1 && path.startsWith("/")
            "goo.gl" -> path == "/maps" || path.startsWith("/maps/")
            else -> false
        }
    }

    private fun candidates(text: String): Sequence<URI> {
        if (text.length > MAX_TEXT_LENGTH) return emptySequence()
        return urlPattern.findAll(text).take(8).mapNotNull { match ->
            val value = match.value.trimEnd('.', ',', ';', ')', ']', '}', '。', '、', '）')
            if (value.length > MAX_URL_LENGTH) return@mapNotNull null
            runCatching { URI(value) }.getOrNull()?.takeIf(::isTrustedMapsUri)
        }
    }

    private fun parseUri(uri: URI): PanoramaReference? {
        if (!isTrustedMapsUri(uri) || isShortLink(uri)) return null
        val parameters = queryParameters(uri) ?: return null
        if (parameters.containsKey("pano")) {
            // Conflicting duplicate parameters are ambiguous, so never choose one silently.
            val values = parameters.getValue("pano").distinct()
            return values.singleOrNull()?.takeIf(::validPanoId)?.let(::PanoramaReference)
        }

        // Consumer Maps /data= fields are a best-effort import format, not an API contract.
        // Require a panorama marker so a place ID is not mistaken for a panorama ID.
        val encodedPathData = uri.rawPath.orEmpty().substringAfter("/data=", "")
        val pathData = decode(encodedPathData) ?: return null
        val dataFields = listOf(pathData) + parameters["data"].orEmpty()
        for (data in dataFields) {
            val explicit = panoramaField.findAll(data)
                .map { it.groupValues[1] }.filter(::validPanoId).distinct().toList()
            if (explicit.size == 1) return PanoramaReference(explicit.single())
            if (explicit.size > 1) return null
            if (streetViewMarker.containsMatchIn(data) && data.contains("!3m")) {
                val alternate = alternatePanoramaField.findAll(data)
                    .map { it.groupValues[1] }.filter(::validPanoId).distinct().toList()
                if (alternate.size == 1) return PanoramaReference(alternate.single())
            }
        }
        // !4v is a Maps URL timestamp, not the Street View capture date.
        return null
    }

    private fun queryParameters(uri: URI): Map<String, List<String>>? {
        val result = mutableMapOf<String, MutableList<String>>()
        for (part in uri.rawQuery.orEmpty().split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')) ?: return null
            val value = decode(part.substringAfter('=', "")) ?: return null
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    private fun isShortLink(uri: URI): Boolean =
        uri.host.equals("maps.app.goo.gl", ignoreCase = true) ||
            uri.host.equals("goo.gl", ignoreCase = true)

    private suspend fun expandShortLink(initial: URI): PanoramaReference? {
        var current = initial
        try {
            for (redirectCount in 0..MAX_REDIRECTS) {
                coroutineContext.ensureActive()
                parseUri(current)?.let { return it }
                if (redirectCount == MAX_REDIRECTS) return null
                val connection = current.toURL().openConnection() as HttpURLConnection
                val next = try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = TIMEOUT_MILLIS
                    connection.readTimeout = TIMEOUT_MILLIS
                    connection.requestMethod = "GET"
                    connection.useCaches = false
                    if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return null
                    val location = connection.getHeaderField("Location") ?: return null
                    if (location.length > MAX_URL_LENGTH) return null
                    current.resolve(location).takeIf(::isTrustedMapsUri) ?: return null
                } finally {
                    connection.disconnect()
                }
                current = next
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log shared URLs: they can contain a user's precise location.
            coroutineContext.ensureActive()
        }
        return null
    }
}
