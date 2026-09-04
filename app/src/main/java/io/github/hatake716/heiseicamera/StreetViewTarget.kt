package io.github.hatake716.heiseicamera

import androidx.compose.runtime.Immutable

@Immutable
data class SceneLocation(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }
}

/** A requested panorama or a nearby search; a nearby search does not identify its result image. */
@Immutable
sealed interface StreetViewTarget {
    data class Panorama(val panoId: String) : StreetViewTarget {
        init { require(PanoramaLinks.validPanoId(panoId)) { "Invalid panorama identifier" } }
    }

    data class Nearby(val location: SceneLocation) : StreetViewTarget
}

/** Automatic mode needs this shutter press's fix and never reuses a previous target's position. */
internal fun shutterTarget(
    isAutomatic: Boolean,
    selectedPano: String?,
    location: SceneLocation?,
): StreetViewTarget? = if (isAutomatic) {
    location?.let { StreetViewTarget.Nearby(it) }
} else {
    selectedPano?.let { StreetViewTarget.Panorama(it) }
}
