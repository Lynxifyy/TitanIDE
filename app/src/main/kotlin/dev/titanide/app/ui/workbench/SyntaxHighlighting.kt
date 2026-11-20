package dev.titanide.app.ui.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.titanide.core.designsystem.SyntaxColors
import dev.titanide.core.editor.Token
import dev.titanide.core.editor.TokenType
import dev.titanide.core.editor.Tokenizer
import dev.titanide.core.model.Language

/**
 * Token-based syntax highlighting shared by the workbench and the standalone editor.
 *
 * Colours come from the real tokenizer, so a language without a tokenizer renders as plain
 * text instead of being coloured by a guess.
 */
object SyntaxHighlighting {

    fun highlight(text: String, language: Language): AnnotatedString {
        if (language == Language.PLAINTEXT) return AnnotatedString(text)
        val tokens = runCatching { Tokenizer.tokenize(text, language) }.getOrDefault(emptyList())
        if (tokens.isEmpty()) return AnnotatedString(text)

        return buildAnnotatedString {
            var cursor = 0
            for (token in tokens) {
                if (token.start > cursor) append(text.substring(cursor, token.start.coerceAtMost(text.length)))
                val start = token.start.coerceIn(0, text.length)
                val end = token.end.coerceIn(start, text.length)
                if (start >= end) continue
                withStyle(SpanStyle(color = colorFor(token))) {
                    append(text.substring(start, end))
                }
                cursor = end
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }

    fun colorFor(token: Token): Color = when (token.type) {
        TokenType.KEYWORD -> SyntaxColors.Keyword
        TokenType.STRING -> SyntaxColors.String
        TokenType.NUMBER -> SyntaxColors.Number
        TokenType.COMMENT -> SyntaxColors.Comment
        TokenType.FUNCTION -> SyntaxColors.Function
        TokenType.TYPE -> SyntaxColors.Type
        TokenType.ANNOTATION -> SyntaxColors.Annotation
        TokenType.OPERATOR -> SyntaxColors.Operator
        TokenType.PUNCTUATION -> SyntaxColors.Punctuation
        TokenType.IDENTIFIER -> SyntaxColors.Plain
        TokenType.PLAIN -> SyntaxColors.Plain
    }
}
