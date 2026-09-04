package io.legado.app.ui.widget.components.modalBottomSheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.legado.app.ui.about.MarkdownSheet

@Stable
data class TextSheetData(
    val title: String,
    val content: String,
    val onDismiss: (() -> Unit)? = null,
)

val LocalTextSheetHost = staticCompositionLocalOf<(title: String, content: String) -> Unit> {
    { _, _ -> }
}

@Composable
fun TextSheetHost(
    data: TextSheetData?,
    onDismissRequest: () -> Unit,
) {
    var lastData by remember { mutableStateOf<TextSheetData?>(null) }
    LaunchedEffect(data) {
        if (data != null) {
            lastData = data
        }
    }
    val current = data ?: lastData
    MarkdownSheet(
        show = data != null,
        title = current?.title.orEmpty(),
        content = current?.content.orEmpty(),
        onDismissRequest = onDismissRequest,
    )
}
