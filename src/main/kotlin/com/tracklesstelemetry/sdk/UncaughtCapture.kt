package com.tracklesstelemetry.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the process-wide default uncaught-exception handler lives. The real one
 * is [Thread.setDefaultUncaughtExceptionHandler]; tests pass a fake so they
 * never replace the JVM's own.
 */
internal interface DefaultHandlerSlot {
    var handler: Thread.UncaughtExceptionHandler?
}

/** The JVM's default uncaught-exception handler. */
internal object SystemDefaultHandlerSlot : DefaultHandlerSlot {
    override var handler: Thread.UncaughtExceptionHandler?
        get() = Thread.getDefaultUncaughtExceptionHandler()
        set(value) = Thread.setDefaultUncaughtExceptionHandler(value)
}

/**
 * How the crashing thread waits for the flush thread's send: at most
 * `timeoutMs`, returning whether the send finished. Replaced in tests.
 */
internal fun interface UncaughtSendWait {
    fun await(done: CountDownLatch, timeoutMs: Long): Boolean
}

/**
 * The [TracklessConfig.captureUncaught] hook: a default uncaught-exception
 * handler chained in front of the one that was there before (the platform's,
 * or a crash reporter's).
 *
 * Installed at most once per [slot], and never removed: another library may
 * have chained itself in front since, and unhooking would cut it off. While
 * capture is off or recording is disabled, [record] returns at once and the
 * wrapper only forwards.
 *
 * Whatever [record] does — returns, throws, or gives up waiting on its send —
 * the previous handler is called afterwards, in a `finally`, with the same
 * thread and throwable. Nothing is written to disk.
 */
internal class UncaughtCapture(
    private val slot: DefaultHandlerSlot = SystemDefaultHandlerSlot,
    val sendWait: UncaughtSendWait = UncaughtSendWait { done, timeoutMs ->
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    },
) {
    private val installed = AtomicBoolean(false)

    /** Whether the handler is installed in [slot]. */
    val isInstalled: Boolean
        get() = installed.get()

    /**
     * Install the chained handler, once. [record] runs on the crashing thread
     * and receives only the throwable; it must never throw, but is guarded
     * anyway.
     */
    fun installOnce(record: (Throwable) -> Unit) {
        if (!installed.compareAndSet(false, true)) return
        try {
            val previous = slot.handler
            slot.handler = ChainedHandler(previous, record)
        } catch (_: Throwable) {
            // A security manager refusing the swap — capture stays off
        }
    }

    private class ChainedHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
        private val record: (Throwable) -> Unit,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                record(throwable)
            } catch (_: Throwable) {
                // Never let the SDK change how the app crashes
            } finally {
                forward(thread, throwable)
            }
        }

        private fun forward(thread: Thread, throwable: Throwable) {
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
                return
            }
            // No handler before ours: do what ThreadGroup.uncaughtException does
            // when there is no default handler. Calling the thread's group
            // instead would find this handler again as the default.
            @Suppress("DEPRECATION")
            if (throwable is ThreadDeath) return
            System.err.print("Exception in thread \"" + thread.name + "\" ")
            throwable.printStackTrace(System.err)
        }
    }
}
