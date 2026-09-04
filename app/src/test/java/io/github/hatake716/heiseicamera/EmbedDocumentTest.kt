package io.github.hatake716.heiseicamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class EmbedDocumentTest {
    @Test fun targetsOnlyTheSelectedPanoramaWithoutLocationFallback() {
        val uri = URI(EmbedDocument.url(EmbedRequest("F:old/pano_123-XYZ", "test-key")))
        assertEquals("https", uri.scheme)
        assertEquals("www.google.com", uri.host)
        assertEquals("/maps/embed/v1/streetview", uri.path)
        assertEquals(mapOf("key" to "test-key", "pano" to "F:old/pano_123-XYZ"), parameters(uri))
    }

    @Test fun queryValuesCannotInjectParametersOrMarkup() {
        val unusualKey = "test&location=0,0\"'><script>alert(1)</script>"
        val request = EmbedRequest("old-pano", unusualKey)
        assertEquals(mapOf("key" to unusualKey, "pano" to "old-pano"), parameters(URI(EmbedDocument.url(request))))
        val html = EmbedDocument.html(request)
        assertFalse(html.contains(unusualKey))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&amp;pano=old-pano"))
        assertEquals(1, Regex("src=").findAll(html).count())
        assertTrue(html.contains("src=\"https://www.google.com/maps/embed/v1/streetview?key="))
    }

    @Test fun preservesOnlySuppliedInitialViewParameters() {
        assertEquals(
            mapOf("key" to "test-key", "pano" to "old-pano", "heading" to "210.0", "pitch" to "-25.0", "fov" to "35.0"),
            parameters(URI(EmbedDocument.url(EmbedRequest("old-pano", "test-key", 210f, -25f, 35f)))),
        )
    }

    @Test fun refusesMissingKeysInvalidIdentifiersAndImpossibleViewParameters() {
        listOf(
            EmbedRequest("old-pano", " "),
            EmbedRequest("", "test-key"),
            EmbedRequest("old&pano=latest", "test-key"),
            EmbedRequest("old-pano", "test-key", heading = Float.NaN),
            EmbedRequest("old-pano", "test-key", heading = 361f),
            EmbedRequest("old-pano", "test-key", pitch = -91f),
            EmbedRequest("old-pano", "test-key", fov = 0f),
            EmbedRequest("old-pano", "test-key", fov = Float.POSITIVE_INFINITY),
        ).forEach { request -> assertTrue(runCatching { EmbedDocument.url(request) }.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test fun wrapperUsesAppOriginAndDoesNotClaimAccessToGoogleFrameState() {
        val html = EmbedDocument.html(EmbedRequest("old-pano", "test-key"))
        assertEquals("https://appassets.androidplatform.net/heisei-camera/viewer.html", EmbedDocument.BASE_URL)
        assertTrue(html.contains("referrerpolicy=\"strict-origin-when-cross-origin\""))
        assertTrue(html.contains("min-width:200px; min-height:200px"))
        assertFalse(html.contains("allowfullscreen"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("postMessage"))
        assertFalse(html.contains("contentWindow"))
        assertFalse(html.contains("<canvas"))
    }

    private fun parameters(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { field ->
        field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), "UTF-8")
    }
}
