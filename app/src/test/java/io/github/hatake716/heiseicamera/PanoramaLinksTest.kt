package io.github.hatake716.heiseicamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class PanoramaLinksTest {
    private val pano = "tu510ie_z4ptBZYo2BGEJg"

    @Test fun importsOfficialPanoramaUrlFromSharedText() {
        val text = "昔の風景\nhttps://www.google.com/maps/@?api=1&map_action=pano&pano=$pano\nここです"
        assertEquals(PanoramaReference(pano), PanoramaLinks.parse(text))
    }

    @Test fun decodesOpaqueOfficialPanoIdWithoutAssumingTwentyTwoCharacters() {
        assertEquals(
            "F:-sample/abc_123-XYZ",
            PanoramaLinks.parse("https://google.com/maps/@?api=1&pano=F%3A-sample%2Fabc_123-XYZ")?.panoId,
        )
    }

    @Test fun importsConsumerDataUrlWithExplicitPanoramaMarker() {
        assertEquals(
            pano,
            PanoramaLinks.parse("https://www.google.com/maps/@35.0,139.0,3a,75y/data=!3m7!1e1!3m5!1s$pano!2e0!7i16384!8i8192")?.panoId,
        )
    }

    @Test fun importsPercentEncodedQueryData() {
        assertEquals(
            pano,
            PanoramaLinks.parse("https://maps.google.com/?data=%213m4%211s$pano%212e0%217i16384")?.panoId,
        )
    }

    @Test fun importsConsumerPanoramaMarkerVariants() {
        for (marker in listOf("!1e1", "!1e3")) {
            assertEquals(
                pano,
                PanoramaLinks.parse("https://www.google.com/maps/@/data=!3m3$marker!1s$pano")?.panoId,
            )
        }
    }

    @Test fun ignoresUnrelatedTimestampAndDateQuery() {
        val link = "https://www.google.com/maps/@/data=!4v1234567890000!3m3!1s$pano!2e0?date=2008-09"
        assertEquals(PanoramaReference(pano), PanoramaLinks.parse(link))
        assertNull(PanoramaLinks.parse("https://www.google.com/maps/@/data=!4v1234567890000"))
    }

    @Test fun placeAndLocationLinksDoNotBecomePanoramaIds() {
        listOf(
            "https://www.google.com/maps/place/Tokyo/data=!4m5!3m4!1sChIJ-place-id!8m2!3d35.0!4d139.0",
            "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=35,139",
            "https://www.google.com/maps/search/?api=1&query_place_id=ChIJ-place-id",
            pano,
        ).forEach { assertNull(it, PanoramaLinks.parse(it)) }
    }

    @Test fun shortLinkTokensAreNeverAssumedToBePanoIds() {
        listOf(
            "https://maps.app.goo.gl/$pano",
            "https://goo.gl/maps/$pano",
            "https://maps.app.goo.gl/token?pano=$pano",
        ).forEach { assertNull(it, PanoramaLinks.parse(it)) }
    }

    @Test fun rejectsUntrustedSchemesHostsPathsPortsAndUserInfo() {
        listOf(
            "http://www.google.com/maps/?pano=$pano",
            "https://www.google.com.evil.example/maps/?pano=$pano",
            "https://evil.example/maps/?pano=$pano",
            "https://www.google.com@evil.example/maps/?pano=$pano",
            "https://evil@www.google.com/maps/?pano=$pano",
            "https://www.google.com:8443/maps/?pano=$pano",
            "https://www.google.com/maps-evil/?pano=$pano",
            "https://www.google.com/search?pano=$pano",
            "https://goo.gl/not-maps?pano=$pano",
        ).forEach { assertNull(it, PanoramaLinks.parse(it)) }
    }

    @Test fun doesNotImportNestedUrlsFromAnUntrustedLink() {
        assertNull(PanoramaLinks.parse("https://evil.example/?next=https://www.google.com/maps/?pano=$pano"))
        assertNull(PanoramaLinks.parse("https://www.google.com/maps/?next=https://evil.example/?pano=$pano"))
    }

    @Test fun rejectsMalformedAndOversizedIds() {
        listOf("", "abc%20def", "abc%0Adef", "abc%26pano%3Dother", "%ZZ", "a".repeat(513))
            .forEach { assertNull(it, PanoramaLinks.parse("https://www.google.com/maps/?pano=$it")) }
    }

    @Test fun conflictingPanoramaIdsAreRejected() {
        assertNull(PanoramaLinks.parse("https://www.google.com/maps/?pano=$pano&pano=different"))
        assertNull(PanoramaLinks.parse("https://www.google.com/maps/@/data=!3m7!1s$pano!2e0!1sdifferent!2e0"))
        assertNull(PanoramaLinks.parse("https://www.google.com/maps/@/data=!3m7!1e1!1s$pano!1sdifferent"))
        assertEquals(pano, PanoramaLinks.parse("https://www.google.com/maps/?pano=$pano&pano=$pano")?.panoId)
    }

    @Test fun acceptsOnlyGoogleMapsRedirectDestinations() {
        assertTrue(PanoramaLinks.isTrustedMapsUri(URI("https://maps.app.goo.gl/abc")))
        assertTrue(PanoramaLinks.isTrustedMapsUri(URI("https://goo.gl/maps/abc")))
        assertTrue(PanoramaLinks.isTrustedMapsUri(URI("https://www.google.com:443/maps/@?pano=$pano")))
        assertFalse(PanoramaLinks.isTrustedMapsUri(URI("https://accounts.google.com/")))
        assertFalse(PanoramaLinks.isTrustedMapsUri(URI("https://maps.app.goo.gl.evil.example/abc")))
        assertFalse(PanoramaLinks.isTrustedMapsUri(URI("http://maps.google.com/")))
        assertFalse(PanoramaLinks.isTrustedMapsUri(URI("https://127.0.0.1/maps/")))
    }
}
