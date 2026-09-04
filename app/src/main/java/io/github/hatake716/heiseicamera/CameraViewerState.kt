package io.github.hatake716.heiseicamera

/** Only the requested panorama is known: Google's embedded page owns its current viewpoint. */
internal data class CameraViewerState(
    val selectedPano: String? = null,
    val epoch: Int = 0,
    val pageState: EmbedPageState = EmbedPageState.LOADING,
) {
    fun select(panoId: String) = CameraViewerState(selectedPano = panoId, epoch = epoch + 1)

    fun matches(panoId: String, expectedEpoch: Int): Boolean =
        selectedPano == panoId && epoch == expectedEpoch

    fun pageChanged(panoId: String, expectedEpoch: Int, value: EmbedPageState): CameraViewerState =
        if (matches(panoId, expectedEpoch)) copy(pageState = value) else this
}
