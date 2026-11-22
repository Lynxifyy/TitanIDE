package dev.titanide.app.ui.home

import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The Home dashboard reports how much disk the app uses, which means walking every project,
 * build, log and artifact directory. That walk ran on the main dispatcher inside
 * `viewModelScope.launch`, so the dashboard blocked the UI thread for as long as the walk
 * took, and the cost grows with the size of the user's projects.
 *
 * The main dispatcher here is a real single thread, not a pool: a pool would hide the bug by
 * handing the walk a worker thread of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeStorageThreadingTest {

    @get:Rule
    val containers = CloseContainersRule()

    private val container = TestContainers.create()
    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "titan-test-main")
    }

    private lateinit var mainThread: Thread

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun installMainDispatcher() {
        mainThread = mainExecutor.submit<Thread> { Thread.currentThread() }.get()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
        mainExecutor.shutdownNow()
    }

    @Test
    fun `the storage walk does not run on the main thread`() {
        val ranOnMain = CopyOnWriteArrayList<Boolean>()

        // Stands in for the disk walk: records whether the caller is the UI thread.
        container.storageProbe = {
            ranOnMain += Thread.currentThread() === mainThread
            4096L
        }

        val viewModel = ViewModelTracker.track(HomeViewModel(container))
        runBlocking { withTimeout(20_000) { viewModel.state.first { it.storageUsed > 0 } } }

        assertEquals("the probe must have run once", 1, ranOnMain.size)
        assertFalse(
            "the storage walk must not run on the main thread, but it did",
            ranOnMain.first(),
        )
    }
}
