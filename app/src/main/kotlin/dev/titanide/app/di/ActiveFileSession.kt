package dev.titanide.app.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The file the user is currently looking at, shared between the editor and every feature
 * that acts on "the current file" (AI modes, build diagnostics, CodeGuard fixes).
 *
 * A single owner avoids the editor and the AI studio drifting apart, which would otherwise
 * mean AI edits applied to a buffer the user is not looking at.
 */
class ActiveFileSession {

    data class Snapshot(
        val projectId: String? = null,
        val path: String? = null,
        val content: String = "",
        val language: String? = null,
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1,
    ) {
        val hasFile: Boolean get() = path != null

        val selection: String?
            get() = if (selectionStart >= 0 && selectionEnd > selectionStart && selectionEnd <= content.length) {
                content.substring(selectionStart, selectionEnd)
            } else {
                null
            }
    }

    private val _current = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = _current.asStateFlow()

    fun update(projectId: String?, path: String?, content: String, language: String?) {
        _current.value = _current.value.copy(
            projectId = projectId,
            path = path,
            content = content,
            language = language,
            selectionStart = -1,
            selectionEnd = -1,
        )
    }

    fun setSelection(start: Int, end: Int) {
        _current.value = _current.value.copy(selectionStart = start, selectionEnd = end)
    }

    fun clear() {
        _current.value = Snapshot()
    }
}
