package de.fiereu.lua

/** The lifecycle state of a script started with [LuaRuntime.start]. */
public enum class ExecutionStatus {
    /** Running, or waiting for the runtime lock. */
    RUNNING,

    /** Parked at a checkpoint after [ExecutionHandle.pause]. */
    PAUSED,

    /** Finished normally; [ExecutionHandle.result] holds the return values. */
    COMPLETED,

    /** Finished with an error; [ExecutionHandle.error] holds it. */
    FAILED,

    /** Aborted by [ExecutionHandle.stop]. */
    STOPPED,
}

/**
 * A handle to a script started on a managed thread with [LuaRuntime.start]. All
 * methods are safe to call from any thread. Pause and stop take effect at the next
 * statement or loop checkpoint, so a script blocked in native code keeps running
 * until control returns to Lua.
 */
public interface ExecutionHandle {
    /** The current lifecycle state. */
    public val status: ExecutionStatus

    /** The return values once [status] is [ExecutionStatus.COMPLETED], else null. */
    public val result: List<LuaValue>?

    /** The error once [status] is [ExecutionStatus.FAILED], else null. */
    public val error: LuaError?

    /** Requests a pause. The script parks at its next checkpoint and releases the runtime lock. */
    public fun pause()

    /** Wakes a paused execution. */
    public fun resume()

    /** Requests an abort. The script unwinds at its next checkpoint with an error Lua code cannot catch. */
    public fun stop()

    /**
     * Blocks until the execution finishes. Returns the results when it completed,
     * rethrows the [LuaError] when it failed, and throws [IllegalStateException]
     * when it was stopped.
     */
    public fun await(): List<LuaValue>

    /** Blocks up to [timeoutMillis] for the execution to finish. Returns true if it did, false on timeout. */
    public fun await(timeoutMillis: Long): Boolean
}
