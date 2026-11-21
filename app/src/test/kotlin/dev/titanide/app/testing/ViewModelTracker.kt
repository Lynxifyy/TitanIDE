package dev.titanide.app.testing

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain

/**
 * Cancels the ViewModels a test created.
 *
 * A ViewModel's `viewModelScope` lives until `onCleared`, which only happens when a real
 * owner is destroyed. Unit tests build ViewModels by hand, so nothing ever cleared them and
 * their collectors kept running for the rest of the JVM. The next test class then called
 * `Dispatchers.resetMain()` while those coroutines were still dispatching, which throws
 * "Dispatchers.Main is used concurrently with setting it" — a failure in whichever class
 * happened to run next, not in the class that leaked.
 */
object ViewModelTracker {

    private val live = java.util.Collections.synchronizedList(mutableListOf<ViewModel>())

    /**
     * `ViewModel.clear()` is Kotlin-`internal`, so its JVM name is name-mangled. Looking the
     * mangled name up directly is deliberate: a failed lookup must fail the test, not leave
     * the leak in place while every test still passes.
     */
    private val clearMethod: java.lang.reflect.Method =
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel_release").apply { isAccessible = true }

    /** Registers a ViewModel so [clearAll] can cancel it at the end of the test. */
    fun <T : ViewModel> track(viewModel: T): T {
        live += viewModel
        return viewModel
    }

    fun clearAll() {
        val pending = synchronized(live) {
            val copy = live.toList()
            live.clear()
            copy
        }
        pending.forEach { viewModel -> clearMethod.invoke(viewModel) }
    }
}

/**
 * Restores the real main dispatcher after [ViewModelTracker.clearAll] stopped the leaked
 * collectors. Cancellation is asynchronous, so a dispatch that was already in flight can
 * still overlap the reset; that overlap is retried instead of failing the test.
 */
fun resetMainDispatcher() {
    repeat(50) {
        try {
            Dispatchers.resetMain()
            return
        } catch (e: IllegalStateException) {
            Thread.sleep(20)
        }
    }
    Dispatchers.resetMain()
}
