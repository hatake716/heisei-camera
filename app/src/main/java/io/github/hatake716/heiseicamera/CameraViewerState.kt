package io.github.hatake716.heiseicamera

/** Only the request is known: Google's embedded page owns the actual panorama and viewpoint. */
internal data class CameraViewerState(
    val target: StreetViewTarget? = null,
    val epoch: Int = 0,
    val pageState: EmbedPageState = EmbedPageState.LOADING,
) {
    fun select(value: StreetViewTarget?) = CameraViewerState(target = value, epoch = epoch + 1)

    fun matches(value: StreetViewTarget, expectedEpoch: Int): Boolean =
        target == value && epoch == expectedEpoch

    fun pageChanged(expectedTarget: StreetViewTarget, expectedEpoch: Int, value: EmbedPageState): CameraViewerState =
        if (matches(expectedTarget, expectedEpoch)) copy(pageState = value) else this
}
