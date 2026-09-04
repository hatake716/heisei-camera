package io.github.hatake716.heiseicamera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationProviderTest {
    @Test fun acceptsNewPreciseAndCoarseFixesIncludingRealNegativeCoordinates() {
        assertTrue(valid(latitude = 35.681, longitude = 139.767, accuracy = 8f))
        assertTrue(valid(latitude = -33.869, longitude = -70.669, accuracy = 2400f))
        assertTrue(valid(latitude = 0.0, longitude = 0.0, accuracy = 0f))
    }

    @Test fun rejectsCachedFutureAndInvalidElapsedTimesWithoutUsingWallClock() {
        assertFalse(valid(fix = STARTED - 1))
        assertFalse(valid(fix = NOW + 1))
        assertFalse(valid(fix = -1))
        assertFalse(valid(fix = 0))
        assertFalse(valid(fix = Long.MAX_VALUE))
        assertFalse(isFreshLocationFix(35.0, 139.0, 8f, 1, -1, NOW))
        assertFalse(isFreshLocationFix(35.0, 139.0, 8f, STARTED, STARTED, STARTED - 1))
        assertTrue(valid(fix = STARTED))
        assertTrue(valid(fix = NOW))
    }

    @Test fun rejectsMalformedCoordinatesAndUnknownAccuracy() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -90.01, 90.01)
            .forEach { assertFalse(valid(latitude = it)) }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -180.01, 180.01)
            .forEach { assertFalse(valid(longitude = it)) }
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)
            .forEach { assertFalse(valid(accuracy = it)) }
    }

    private fun valid(
        latitude: Double = 35.0,
        longitude: Double = 139.0,
        accuracy: Float? = 8f,
        fix: Long = STARTED + 1,
    ) = isFreshLocationFix(latitude, longitude, accuracy, fix, STARTED, NOW)

    private companion object {
        const val STARTED = 1_000_000_000L
        const val NOW = 2_000_000_000L
    }
}
