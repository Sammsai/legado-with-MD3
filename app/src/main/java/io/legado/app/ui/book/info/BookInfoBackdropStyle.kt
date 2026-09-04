package io.legado.app.ui.book.info

internal data class BookInfoBackdropStyle(
    val showCover: Boolean,
    val blurCover: Boolean,
    val applySeedOverlay: Boolean,
) {
    companion object {
        const val MODE_OFF = "off"
        const val MODE_ON = "on"
        const val MODE_OFF_FOR_DEFAULT = "off_for_default"
    }
}

internal fun resolveBookInfoBackdropStyle(backgroundMode: String): BookInfoBackdropStyle {
    // 取值与主题设置持久化的 bookInfoBackground 一致：off/ off_for_default / on
    return when (backgroundMode) {
        BookInfoBackdropStyle.MODE_OFF -> BookInfoBackdropStyle(
            showCover = true,
            blurCover = false,
            applySeedOverlay = true,
        )

        BookInfoBackdropStyle.MODE_OFF_FOR_DEFAULT -> BookInfoBackdropStyle(
            showCover = false,
            blurCover = false,
            applySeedOverlay = false,
        )

        else -> BookInfoBackdropStyle(
            showCover = true,
            blurCover = true,
            applySeedOverlay = true,
        )
    }
}
