package koma.core

/**
 * Annotation to mark experimental APIs in the Koma framework.
 * APIs marked with this annotation may change in the future without notice.
 *
 * This annotation is used to notify users that an API is experimental
 * and requires explicit opt-in when used.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is an experimental API for Koma Framework. It may be changed in the future without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
)
annotation class ExperimentalKomaApi

/**
 * Annotation to mark internal APIs in the Koma framework.
 * APIs marked with this annotation are not intended for public use
 * and may be changed or removed without notice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal API for Koma Framework. It is not intended for public use and may be changed or removed without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
)
annotation class InternalKomaApi

@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class KomaStoreDsl
