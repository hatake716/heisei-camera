package io.github.hatake716.heiseicamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraViewerStateTest {
    @Test fun lateCallbacksFromAnotherTargetCannotChangeCurrentPage() {
        val firstTarget = StreetViewTarget.Panorama("first")
        val secondTarget = StreetViewTarget.Nearby(SceneLocation(35.6, 139.7))
        val first = CameraViewerState().select(firstTarget)
        val second = first.select(secondTarget)
        assertEquals(second, second.pageChanged(firstTarget, first.epoch, EmbedPageState.PAGE_LOADED))
        val loaded = second.pageChanged(secondTarget, second.epoch, EmbedPageState.PAGE_LOADED)
        assertEquals(loaded, loaded.pageChanged(firstTarget, first.epoch, EmbedPageState.FAILED))
    }

    @Test fun reloadingSameTargetInvalidatesItsOlderCallbacks() {
        val target = StreetViewTarget.Panorama("same")
        val first = CameraViewerState().select(target)
        val loaded = first.pageChanged(target, first.epoch, EmbedPageState.PAGE_LOADED)
        val retry = loaded.select(target)
        assertEquals(loaded.epoch + 1, retry.epoch)
        assertEquals(EmbedPageState.LOADING, retry.pageState)
        assertEquals(retry, retry.pageChanged(target, first.epoch, EmbedPageState.PAGE_LOADED))
    }

    @Test fun returningToLiveRejectsThePreviousResultPage() {
        val target = StreetViewTarget.Nearby(SceneLocation(35.6, 139.7))
        val past = CameraViewerState().select(target)
        val live = past.select(null)
        assertNull(live.target)
        assertEquals(live, live.pageChanged(target, past.epoch, EmbedPageState.PAGE_LOADED))
    }

    @Test fun automaticShutterRequiresEachPressLocationWithoutManualFallback() {
        val first = SceneLocation(35.6, 139.7)
        val next = SceneLocation(35.7, 139.8)
        assertEquals(StreetViewTarget.Nearby(first), shutterTarget(true, "old-pano", first))
        assertEquals(StreetViewTarget.Nearby(next), shutterTarget(true, "old-pano", next))
        assertNull(shutterTarget(true, "old-pano", null))
    }

    @Test fun manualShutterUsesOnlyItsExactSelectedPanorama() {
        val location = SceneLocation(35.6, 139.7)
        assertEquals(StreetViewTarget.Panorama("old-pano"), shutterTarget(false, "old-pano", location))
        assertEquals(StreetViewTarget.Panorama("old-pano"), shutterTarget(false, "old-pano", null))
        assertNull(shutterTarget(false, null, location))
    }
}
