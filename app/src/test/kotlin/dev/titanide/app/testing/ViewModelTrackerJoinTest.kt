package dev.titanide.app.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ViewModelStore.clear()` only *requests* cancellation: it calls `ViewModel.clear()` and
 * returns, so the ViewModel's `viewModelScope` children can still be running. A screen rendered
 * in a test builds its ViewModel through `viewModel { ... }`, which puts it in the store rather
 * than in [ViewModelTracker]'s own list, so those jobs were never joined before the container
 * closed its database. The result was a `SQLiteConnectionPool ... has been closed` failure in
 * whichever test class ran next — roughly one run in four, on a different screen each time.
 *
 * The invariant this pins: once [ViewModelTracker.clearAll] returns, no coroutine started by a
 * tracked ViewModel is still running.
 */
class ViewModelTrackerJoinTest {

    private class SlowViewModel(private val finished: AtomicBoolean) : ViewModel() {
        fun start() {
            viewModelScope.launch {
                // Ignores the cancellation request for a moment, which is what a Room query in
                // flight does: the driver finishes the statement before it notices.
                withContext(NonCancellable) {
                    Thread.sleep(150)
                }
                finished.set(true)
            }
        }
    }

    @Test
    fun `clearAll waits for ViewModels it only reached through their store`() {
        val finished = AtomicBoolean(false)
        val owner = ViewModelTracker.trackStore(
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            },
        )
        val viewModel = SlowViewModel(finished)
        owner.viewModelStore.put("slow", viewModel)
        viewModel.start()

        ViewModelTracker.clearAll()

        assertTrue(
            "clearAll returned while a store-owned ViewModel's coroutine was still running; " +
                "the database it was reading gets closed next",
            finished.get(),
        )
    }
}
