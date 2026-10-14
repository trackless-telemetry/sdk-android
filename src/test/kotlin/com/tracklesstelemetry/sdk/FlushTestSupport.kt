package com.tracklesstelemetry.sdk

/** [Trackless.flush], then wait for the flush thread to send what it queued. */
internal fun flushAndWait() {
    Trackless.flush()
    Trackless.awaitFlushesForTesting()
}

/** [Trackless.destroy], then wait for its final flush to be sent. */
internal fun destroyAndWait() {
    Trackless.destroy()
    Trackless.awaitFlushesForTesting()
}
