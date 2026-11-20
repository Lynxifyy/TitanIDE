package dev.titanide.app.ui.workbench

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.titanide.core.model.Language

/**
 * Applies syntax colouring to an editable field.
 *
 * A [VisualTransformation] is used rather than rendering a styled read-only overlay so the
 * caret, selection, IME composition and hardware-keyboard navigation all keep working. The
 * offset mapping is the identity because the transformation never changes the text length.
 */
class SyntaxHighlightTransformation(private val language: Language) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = SyntaxHighlighting.highlight(text.text, language)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is SyntaxHighlightTransformation && other.language == language

    override fun hashCode(): Int = language.hashCode()
}
