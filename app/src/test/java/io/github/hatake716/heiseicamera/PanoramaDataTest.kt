package io.github.hatake716.heiseicamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaDataTest {
    @Test fun dateFormattingPreservesAvailablePrecision() {
        val monthly = CaptureDate.parse("2008-09")!!
        assertEquals("2008.09", monthly.display)
        assertEquals("平成20年", monthly.eraLabel)
        assertTrue(monthly.isHeisei)
        val yearly = CaptureDate.parse("2008")!!
        assertEquals("2008", yearly.display)
        assertEquals("平成20年", yearly.eraLabel)
        assertNull(yearly.month)
    }

    @Test fun eraEndUsesMonthWhenAvailable() {
        assertEquals("平成31年", CaptureDate.parse("2019-04")!!.eraLabel)
        assertTrue(CaptureDate.parse("2019-01")!!.isHeisei)
        assertFalse(CaptureDate.parse("2019-05")!!.isHeisei)
        assertEquals("平成以降", CaptureDate.parse("2019-05")!!.eraLabel)
        assertEquals("平成か不明", CaptureDate.parse("2019")!!.eraLabel)
        assertFalse(CaptureDate.parse("2019")!!.isHeisei)
    }

    @Test fun eraStartDoesNotInventADayForJanuary1989() {
        assertEquals("平成以前", CaptureDate.parse("1988-12")!!.eraLabel)
        assertEquals("平成か不明", CaptureDate.parse("1989-01")!!.eraLabel)
        assertEquals("平成か不明", CaptureDate.parse("1989")!!.eraLabel)
        assertEquals("平成元年", CaptureDate.parse("1989-02")!!.eraLabel)
        assertTrue(CaptureDate.parse("1990")!!.isHeisei)
    }

    @Test fun malformedDatesAreUnknownInsteadOfGuessed() {
        listOf(null, "", "0000", "2008-00", "2008-13", "2008-9", "2008-09-01", "08-09",
            " 2008-09", "2008-09 ", "2008/09", "２００８-０９", "200809", "2019-04\n")
            .forEach { assertNull(it, CaptureDate.parse(it)) }
    }

    @Test fun matchingMetadataReturnsCaptureMonthAndAttribution() {
        val result = parseMetadata(
            """{"status":"OK","pano_id":"same","date":"2008-09","copyright":"© Google","location":{"lat":35.6,"lng":139.7}}""",
            "same",
        ) as MetadataResult.Success
        assertEquals(CaptureDate(2008, 9), result.date)
        assertEquals("© Google", result.copyright)
        assertEquals(35.6, result.lat!!, 0.0)
        assertEquals(139.7, result.lng!!, 0.0)
    }

    @Test fun replacedOrMissingPanoIdCannotSupplyTheDisplayedDate() {
        assertTrue(parseMetadata("""{"status":"OK","pano_id":"replacement","date":"2008-09"}""", "original") is MetadataResult.Failure)
        assertTrue(parseMetadata("""{"status":"OK","date":"2008-09"}""", "original") is MetadataResult.Failure)
    }

    @Test fun absentOrMalformedMetadataDateStaysUnknown() {
        val missing = parseMetadata("""{"status":"OK","pano_id":"same"}""", "same") as MetadataResult.Success
        assertNull(missing.date)
        assertNull(missing.lat)
        assertNull(missing.lng)
        val malformed = parseMetadata("""{"status":"OK","pano_id":"same","date":"2008-13"}""", "same") as MetadataResult.Success
        assertNull(malformed.date)
    }

    @Test fun failureNeverEchoesServerErrorDetails() {
        val result = parseMetadata(
            """{"status":"REQUEST_DENIED","error_message":"private-api-key and location details"}""",
            "same",
        ) as MetadataResult.Failure
        assertFalse(result.message.contains("private-api-key"))
        assertTrue(result.message.contains("API"))
    }
}
