package dev.titanide.app.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker exists because a leaked ViewModel keeps collecting on `Dispatchers.Main`, so
 * the next test class's `resetMain()` throws. That only helps if clearing really cancels the
 * scope, which is what these tests pin down.
 */
class ViewModelTrackerTest {

    private class Sample : ViewModel() {
        // A job that finishes immediately is not "cancelled", so it must suspend.
        fun start(): Job = viewModelScope.launch { awaitCancellation() }
    }

    @After
    fun tearDown() = ViewModelTracker.clearAll()

    @Test
    fun `clearing cancels the view model scope`() {
        val sample = ViewModelTracker.track(Sample())
        val job = sample.start()

        ViewModelTracker.clearAll()

        assertTrue("a tracked view model must have its scope cancelled", job.isCancelled)
    }

    @Test
    fun `clearing twice is harmless`() {
        val sample = ViewModelTracker.track(Sample())
        ViewModelTracker.clearAll()

        ViewModelTracker.clearAll()

        assertFalse(sample.start().isActive)
    }
}
