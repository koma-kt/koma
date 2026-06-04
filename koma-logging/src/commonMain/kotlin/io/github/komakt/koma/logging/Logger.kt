package io.github.komakt.koma.logging

/**
 * Logging abstraction used by Koma plugins and diagnostics.
 */
fun interface Logger {
    /**
     * Emits a log entry at the given severity.
     *
     * @param severity The severity of the log
     * @param tag The tag for the log
     * @param throwable Associated exception (if any)
     * @param message Provider for the log message. It may not be evaluated if logging is skipped.
     */
    fun log(
        severity: Severity,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    )

    /**
     * Supported log severity levels.
     */
    enum class Severity {
        /** Very detailed debug information */
        Verbose,

        /** Debug information */
        Debug,

        /** General information */
        Info,

        /** Warning information */
        Warn,

        /** Error information */
        Error,
    }
}
