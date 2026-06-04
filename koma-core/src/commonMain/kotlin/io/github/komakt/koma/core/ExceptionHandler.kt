package io.github.komakt.koma.core

/**
 * Handles non-fatal exceptions raised while a Store is running.
 *
 * This includes exceptions from DSL handlers, plugin hooks, launched coroutines, and state
 * persistence callbacks.
 */
interface ExceptionHandler {
    /**
     * Handles the exception after Koma unwraps its internal bookkeeping errors.
     *
     * @param error The exception to handle
     */
    fun handle(error: Throwable)

    @Suppress("unused")
    companion object {
        /**
         * Ignores all handled exceptions.
         */
        val Ignore: ExceptionHandler = object : ExceptionHandler {
            override fun handle(error: Throwable) {}
        }

        /**
         * Deprecated alias for [Ignore].
         */
        @Deprecated(message = "Use Ignore", replaceWith = ReplaceWith("ExceptionHandler.Ignore"))
        val Noop: ExceptionHandler
            get() = Ignore

        /**
         * Prints the exception message and stack trace for debugging.
         */
        val Log: ExceptionHandler = object : ExceptionHandler {
            override fun handle(error: Throwable) {
                println("[Koma] An exception occurred in the Koma Framework: ${error.message ?: "Unknown error"}")
                error.printStackTrace()
            }
        }

        /**
         * Rethrows handled exceptions instead of swallowing them.
         */
        val Rethrow: ExceptionHandler = object : ExceptionHandler {
            override fun handle(error: Throwable) {
                throw error
            }
        }

        /**
         * Deprecated alias for [Rethrow].
         */
        @Deprecated(message = "Use Rethrow", replaceWith = ReplaceWith("ExceptionHandler.Rethrow"))
        val Unhandled: ExceptionHandler
            get() = Rethrow
    }
}

/**
 * Creates an [ExceptionHandler] from a single callback.
 */
fun ExceptionHandler(block: (error: Throwable) -> Unit) = object : ExceptionHandler {
    override fun handle(error: Throwable) {
        block.invoke(error)
    }
}
