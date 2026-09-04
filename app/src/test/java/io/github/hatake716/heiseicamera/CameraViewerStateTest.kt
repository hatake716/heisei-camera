package io.github.hatake716.heiseicamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewerStateTest {
    @Test fun selectedDateIsNotShownWhilePageIsLoadingOrFailed() {
        val selected = CameraViewerState().select("chosen")
        assertEquals(EmbedPageState.LOADING, selected.pageState)
        assertFalse(selected.canLabelSelection("chosen", selected.epoch))
        val loaded = selected.pageChanged("chosen", selected.epoch, EmbedPageState.PAGE_LOADED)
        assertTrue(loaded.canLabelSelection("chosen", loaded.epoch))
        val failed = loaded.pageChanged("chosen", loaded.epoch, EmbedPageState.FAILED)
        assertFalse(failed.canLabelSelection("chosen", failed.epoch))
    }

    @Test fun lateCallbacksFromAnotherSelectionCannotChangeCurrentPage() {
        val first = CameraViewerState().select("first")
        val second = first.select("second")
        assertEquals(second, second.pageChanged("first", first.epoch, EmbedPageState.PAGE_LOADED))
        assertFalse(second.canLabelSelection("first", first.epoch))
        val loaded = second.pageChanged("second", second.epoch, EmbedPageState.PAGE_LOADED)
        assertEquals(loaded, loaded.pageChanged("first", first.epoch, EmbedPageState.FAILED))
    }

    @Test fun reloadingSamePanoramaInvalidatesItsOlderCallbacks() {
        val first = CameraViewerState().select("same")
        val loaded = first.pageChanged("same", first.epoch, EmbedPageState.PAGE_LOADED)
        val retry = loaded.select("same")
        assertEquals(loaded.epoch + 1, retry.epoch)
        assertEquals(EmbedPageState.LOADING, retry.pageState)
        assertEquals(retry, retry.pageChanged("same", first.epoch, EmbedPageState.PAGE_LOADED))
        assertFalse(retry.canLabelSelection("same", first.epoch))
    }
}
