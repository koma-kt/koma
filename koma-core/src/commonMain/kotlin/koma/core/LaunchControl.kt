package koma.core

/**
 * Stable key for coordinating tracked coroutines launched from `action {}` handlers.
 *
 * Reuse the same instance when multiple launches should share cancellation or drop behavior, or
 * when the lane should be cancellable later via `cancelLaunch(...)`.
 */
class LaunchLane

/**
 * Controls how `action {}` launches coordinate with other tracked launches in the same lane.
 *
 * [Untracked] launches are not tracked by lane.
 * [CancelPrevious] and [DropIfRunning] create tracked lanes. When [LaunchLane] is omitted, the
 * launch uses a default lane derived from the current action type, so matching launches
 * coordinate across dispatches of that action type.
 */
sealed interface LaunchControl {

    /**
     * Launch every invocation independently without lane tracking.
     */
    data object Untracked : LaunchControl

    /**
     * Cancel the previous tracked launch in the same lane before starting a new one.
     */
    data class CancelPrevious(val lane: LaunchLane? = null) : LaunchControl

    /**
     * Ignore a new launch while tracked work in the same lane is still active.
     */
    data class DropIfRunning(val lane: LaunchLane? = null) : LaunchControl
}
