package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Test

class BookInfoBackdropStyleTest {

    @Test
    fun backgroundModesKeepTheirExpectedCoverTreatment() {
        assertEquals(
            BookInfoBackdropStyle(
                showCover = true,
                blurCover = false,
                applySeedOverlay = true,
            ),
            resolveBookInfoBackdropStyle(BookInfoBackdropStyle.MODE_OFF)
        )
        assertEquals(
            BookInfoBackdropStyle(
                showCover = true,
                blurCover = true,
                applySeedOverlay = true,
            ),
            resolveBookInfoBackdropStyle(BookInfoBackdropStyle.MODE_ON)
        )
        assertEquals(
            BookInfoBackdropStyle(
                showCover = false,
                blurCover = false,
                applySeedOverlay = false,
            ),
            resolveBookInfoBackdropStyle(BookInfoBackdropStyle.MODE_OFF_FOR_DEFAULT)
        )
    }
}
