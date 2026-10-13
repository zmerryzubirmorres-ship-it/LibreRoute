package io.github.libreroute.admin

/** Retry only the initial read while this exact authorized connection is current. */
internal fun <T> readReadyAdminCarrier(isCurrent: () -> Boolean, read: () -> Result<T>,
                                     pause: (Long) -> Unit = { Thread.sleep(it) }): Result<T>? {
    var latest: Result<T>? = null
    for (delay in listOf(0L, 250L, 1000L)) {
        if (!isCurrent()) return null
        if (delay > 0) pause(delay)
        if (!isCurrent()) return null
        val result = read()
        if (!isCurrent()) return null
        latest = result
        if (result.isSuccess || result.exceptionOrNull() is ConfirmedAdminRejection) return result
    }
    return latest
}
