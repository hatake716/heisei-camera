package io.github.hatake716.heiseicamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class EmbedDocumentTest {
    @Test fun targetsOnlyTheSelectedPanoramaWithoutLocationFallback() {
        val uri = URI(EmbedDocument.url(EmbedRequest(StreetViewTarget.Panorama("F:old/pano_123-XYZ"), "test-key")))
        assertEquals("https", uri.scheme)
        assertEquals("www.google.com", uri.host)
        assertEquals("/maps/embed/v1/streetview", uri.path)
        assertEquals(mapOf("key" to "test-key", "pano" to "F:old/pano_123-XYZ"), parameters(uri))
    }

    @Test fun nearbyTargetContainsLocationAndNeverAPanoramaIdentifier() {
        val target = StreetViewTarget.Nearby(SceneLocation(35.681236, 139.767125))
        val uri = URI(EmbedDocument.url(EmbedRequest(target, "test-key")))
        assertEquals(mapOf("key" to "test-key", "location" to "35.681236,139.767125"), parameters(uri))
    }

    @Test fun invalidCoordinatesAndPanoramaIdentifiersAreRejected() {
        val invalidLocations = listOf(
            Double.NaN to 0.0, 0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0, 0.0 to Double.NEGATIVE_INFINITY,
            -90.001 to 0.0, 90.001 to 0.0, 0.0 to -180.001, 0.0 to 180.001,
        )
        invalidLocations.forEach { (lat, lng) ->
            assertTrue(runCatching { SceneLocation(lat, lng) }.exceptionOrNull() is IllegalArgumentException)
        }
        listOf("", "old&pano=latest").forEach { value ->
            assertTrue(runCatching { StreetViewTarget.Panorama(value) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(-90.0, SceneLocation(-90.0, -180.0).latitude, 0.0)
        assertEquals(180.0, SceneLocation(90.0, 180.0).longitude, 0.0)
    }

    @Test fun queryValuesCannotInjectParametersOrMarkup() {
        val unusualKey = "test&location=0,0\"'><script>alert(1)</script>"
        val request = EmbedRequest(StreetViewTarget.Panorama("old-pano"), unusualKey)
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
            parameters(URI(EmbedDocument.url(EmbedRequest(StreetViewTarget.Panorama("old-pano"), "test-key", 210f, -25f, 35f)))),
        )
    }

    @Test fun refusesMissingKeysAndImpossibleViewParameters() {
        val target = StreetViewTarget.Panorama("old-pano")
        listOf(
            EmbedRequest(target, " "),
            EmbedRequest(target, "test-key", heading = Float.NaN),
            EmbedRequest(target, "test-key", heading = 361f),
            EmbedRequest(target, "test-key", pitch = -91f),
            EmbedRequest(target, "test-key", fov = 0f),
            EmbedRequest(target, "test-key", fov = Float.POSITIVE_INFINITY),
        ).forEach { request -> assertTrue(runCatching { EmbedDocument.url(request) }.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test fun wrapperUsesAppOriginAndDoesNotClaimAccessToGoogleFrameState() {
        val html = EmbedDocument.html(EmbedRequest(StreetViewTarget.Panorama("old-pano"), "test-key"))
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
