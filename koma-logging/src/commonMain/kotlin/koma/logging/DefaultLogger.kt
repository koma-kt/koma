package koma.logging

import kotlin.concurrent.Volatile
import co.touchlab.kermit.Logger.Companion as Kermit

/**
 * Default [Logger] implementation backed by Kermit.
 */
object DefaultLogger : Logger {
    @Volatile
    private var isDisabled = false

    /**
     * Emits a log entry unless this logger has been disabled.
     */
    override fun log(severity: Logger.Severity, tag: String, throwable: Throwable?, message: () -> String) {
        if (isDisabled) return
        when (severity) {
            Logger.Severity.Verbose -> Kermit.v(tag, throwable, message)
            Logger.Severity.Debug -> Kermit.d(tag, throwable, message)
            Logger.Severity.Info -> Kermit.i(tag, throwable, message)
            Logger.Severity.Warn -> Kermit.w(tag, throwable, message)
            Logger.Severity.Error -> Kermit.e(tag, throwable, message)
        }
    }

    /**
     * Disables this logger for the rest of the process.
     */
    @Suppress("unused")
    fun disable() {
        isDisabled = true
    }
}
