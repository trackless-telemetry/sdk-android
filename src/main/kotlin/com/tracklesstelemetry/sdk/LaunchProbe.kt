package com.tracklesstelemetry.sdk

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * The launch-time measurement behind [TracklessConfig.measureLaunch], once per
 * process, in memory only.
 *
 * **Definition.** From `Process.getStartElapsedRealtime()` (API 24+, public,
 * no permission) to the first frame of the first Activity resumed in this
 * process. Both readings are `SystemClock.elapsedRealtime()` values.
 *
 * **Late configure.** A configure that runs after an Activity was already
 * resumed measures nothing this process: the next resume would be a return to
 * the app, not a launch. That is known two ways — the probe already saw a
 * resume while measuring was off, or the first Activity callback it sees is a
 * pause or stop, which only an Activity that was already running can send.
 *
 * **Background start.** A process the system started for a broadcast, job,
 * service or content provider, and that the user opens later, measures
 * nothing this process. [arm] reads this process's own importance once, with
 * `ActivityManager.getMyMemoryState()` (public, no permission): a launcher or
 * notification-tap cold start runs `Application.onCreate` with the process at
 * `IMPORTANCE_FOREGROUND`, and any other start does not, so the probe finishes
 * with no measurement. Only the current importance of this process is read —
 * nothing about other processes or earlier ones — and it is not kept. A read
 * that fails or returns no importance leaves the probe measuring, so the cap
 * below still guards. Not an ordering of main-queue messages: on API 35 the
 * first Activity's launch is queued only after `Application.onCreate` returns,
 * so a runnable posted from there runs before the first resume on a cold start
 * too.
 *
 * **Cap.** A launch longer than [MAX_LAUNCH_SECONDS], or one that is negative
 * or not a finite number, is dropped, not clipped, and the probe finishes.
 *
 * Thread-safe: all methods are synchronized. Lifecycle callbacks and the frame
 * callback arrive on the main thread; [arm] may come from any thread.
 *
 * @param processStartMs Process start on the `elapsedRealtime` base — injectable for testing.
 * @param clock Millisecond `elapsedRealtime` clock — injectable for testing.
 * @param importance This process's current importance, or null when it cannot be read —
 *   injectable for testing.
 */
internal class LaunchProbe(
    private val processStartMs: () -> Long = Process::getStartElapsedRealtime,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val importance: () -> Int? = ::currentProcessImportance,
) {

    private enum class Phase { IDLE, ARMED, AWAITING_FRAME, MEASURED, FINISHED }

    private var phase = Phase.IDLE
    private var resumedSeen = false
    private var alreadyRunning = false
    private var measuredSeconds: Double? = null

    /**
     * Arm the probe, unless it was ever armed. A late configure, or a process
     * that is not in the foreground now (a background start), finishes it
     * instead. True when this call armed it.
     */
    @Synchronized
    fun arm(): Boolean {
        if (phase != Phase.IDLE) return false
        if (resumedSeen || alreadyRunning || startedInBackground()) {
            phase = Phase.FINISHED
            return false
        }
        phase = Phase.ARMED
        return true
    }

    /** Whether this process is not in the foreground now. False when the importance cannot be read. */
    private fun startedInBackground(): Boolean {
        val current = try {
            importance()
        } catch (_: Throwable) {
            null
        } ?: return false
        return current != RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * An Activity resumed. True when the caller should wait for its first
     * frame: the probe is armed, or still waiting for a first frame (a
     * trampoline Activity that resumed and finished before drawing).
     */
    @Synchronized
    fun onActivityResumed(): Boolean {
        resumedSeen = true
        return when (phase) {
            Phase.ARMED -> {
                phase = Phase.AWAITING_FRAME
                true
            }
            Phase.AWAITING_FRAME -> true
            else -> false
        }
    }

    /** An Activity paused or stopped. Before any resume, that Activity was already running. */
    @Synchronized
    fun onActivityPausedOrStopped() {
        if (resumedSeen) return
        alreadyRunning = true
        if (phase == Phase.ARMED) phase = Phase.FINISHED
    }

    /**
     * A resumed Activity drew its first frame. True when this measured the
     * launch; a launch outside 0 to [MAX_LAUNCH_SECONDS] finishes the probe instead.
     */
    @Synchronized
    fun onFirstFrame(): Boolean {
        if (phase != Phase.AWAITING_FRAME) return false
        val seconds = (clock() - processStartMs()) / 1000.0
        if (!seconds.isFinite() || seconds < 0 || seconds > MAX_LAUNCH_SECONDS) {
            phase = Phase.FINISHED
            return false
        }
        measuredSeconds = seconds
        phase = Phase.MEASURED
        return true
    }

    /** The measured launch time, handed out once. */
    @Synchronized
    fun takeMeasurement(): Double? {
        if (phase != Phase.MEASURED) return null
        phase = Phase.FINISHED
        return measuredSeconds.also { measuredSeconds = null }
    }

    /** Whether the probe is armed and has not measured yet (test support). */
    val isWaiting: Boolean
        @Synchronized get() = phase == Phase.ARMED || phase == Phase.AWAITING_FRAME

    internal companion object {
        /** A launch longer than this many seconds is dropped, not clipped. */
        const val MAX_LAUNCH_SECONDS = 60.0
    }
}

/**
 * This process's current importance, from `ActivityManager.getMyMemoryState()`
 * (API 16+, public, no permission, static, so no `Context` is needed), or null
 * when the read fails or fills in no importance. Reads this process only.
 */
internal fun currentProcessImportance(): Int? = try {
    val info = RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    info.importance.takeIf { it > 0 }
} catch (_: Throwable) {
    null
}

/**
 * The first frame of an Activity, found with the decor view's
 * [ViewTreeObserver.OnDrawListener].
 *
 * `onDraw` runs inside the first traversal, before the frame is handed to the
 * render thread; the callback is posted to the **front** of the main queue
 * from there, so it runs as soon as that traversal returns — the moment the
 * first frame is submitted. The listener is added only once the decor view is
 * attached (at `onResume` it usually is not yet), which also avoids the
 * pre-API-26 loss of draw listeners added to a detached view's observer. The
 * listener removes itself after the first frame. Nothing here throws.
 */
internal object FirstFrame {

    fun await(activity: Activity, onFrame: () -> Unit) {
        val decor = activity.window?.decorView ?: return
        val main = Handler(Looper.getMainLooper())
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var fired = false

            override fun onDraw() {
                if (fired) return
                fired = true
                main.postAtFrontOfQueue {
                    try {
                        onFrame()
                    } catch (_: Throwable) {
                        // Never throws into the host
                    }
                    try {
                        // Not from inside onDraw, which throws on some API levels.
                        val observer = decor.viewTreeObserver
                        if (observer.isAlive) observer.removeOnDrawListener(this)
                    } catch (_: Throwable) {
                        // Never throws into the host
                    }
                }
            }
        }
        if (decor.isAttachedToWindow) {
            decor.viewTreeObserver.addOnDrawListener(listener)
            return
        }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                try {
                    v.viewTreeObserver.addOnDrawListener(listener)
                } catch (_: Throwable) {
                    // Never throws into the host
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }
}
