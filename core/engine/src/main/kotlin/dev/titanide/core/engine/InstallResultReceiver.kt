package dev.titanide.core.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivers the result of a [PackageInstaller] session to the coroutine that started it.
 *
 * A session commits with a `PendingIntent` and Android answers by broadcasting a status.
 * The receiver must be registered before the commit and must be unique per session, so the
 * action name carries the session id: a single shared action would let a stale result from
 * an earlier install complete the current one.
 */
internal object InstallResultReceiver {

    private const val TAG = "InstallResult"

    /** How long to wait for the system dialog before abandoning the session. */
    private const val RESULT_TIMEOUT_MS = 5L * 60L * 1000L

    private data class Session(
        val context: Context,
        val receiver: BroadcastReceiver,
        val result: CompletableDeferred<Result>,
    )

    data class Result(val status: Int, val message: String?)

    private val sessions = ConcurrentHashMap<Int, Session>()

    private fun actionFor(sessionId: Int) = "dev.titanide.engine.INSTALL_RESULT.$sessionId"

    /** Registers the receiver and returns the PendingIntent the session commits with. */
    fun prepare(context: Context, sessionId: Int): PendingIntent {
        val deferred = CompletableDeferred<Result>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                    ?: Int.MIN_VALUE
                deferred.complete(
                    Result(status, intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                )
            }
        }
        sessions[sessionId] = Session(context, receiver, deferred)

        // The action name is ours, so NOT_EXPORTED is correct: no other app can deliver a
        // forged install result to this receiver.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(actionFor(sessionId)),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(actionFor(sessionId)).setPackage(context.packageName),
            flags,
        )
    }

    /** Waits for the verdict, or null when nothing arrived within the timeout. */
    suspend fun await(sessionId: Int): Result? {
        val session = sessions[sessionId] ?: return null
        val result = withTimeoutOrNull(RESULT_TIMEOUT_MS) { session.result.await() }
        if (result == null) Log.w(TAG, "No install result for session $sessionId within the timeout")
        release(sessionId)
        return result
    }

    /** Unregisters the receiver for a session. Safe to call more than once. */
    fun release(sessionId: Int) {
        val session = sessions.remove(sessionId) ?: return
        runCatching { session.context.unregisterReceiver(session.receiver) }
            .onFailure { Log.d(TAG, "Receiver for session $sessionId was already unregistered") }
    }
}
