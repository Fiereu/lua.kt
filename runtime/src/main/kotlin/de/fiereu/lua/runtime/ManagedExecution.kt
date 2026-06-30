package de.fiereu.lua.runtime

import de.fiereu.lua.ExecutionHandle
import de.fiereu.lua.ExecutionStatus
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thrown at a checkpoint to abort a stopped execution. An [Error] so Lua `pcall` cannot catch it. */
internal class ExecutionStoppedError : Error()

/**
 * Runs a function on a managed virtual thread and exposes cooperative pause/stop.
 * The interpreter calls [checkpoint] at every statement and loop back-edge of the
 * controlled execution.
 */
internal class ManagedExecution(
    private val interpreter: Interpreter,
    private val runLock: ReentrantLock,
    private val function: LuaFunction,
    private val arguments: List<LuaValue>,
) : ExecutionHandle {
    private val pauseLock = ReentrantLock()
    private val unpaused = pauseLock.newCondition()
    private val done = CountDownLatch(1)

    @Volatile private var pauseRequested = false

    @Volatile private var stopRequested = false

    @Volatile private var worker: Thread? = null

    @Volatile override var status: ExecutionStatus = ExecutionStatus.RUNNING
        private set

    @Volatile override var result: List<LuaValue>? = null
        private set

    @Volatile override var error: LuaError? = null
        private set

    fun start() {
        worker =
            Thread.ofVirtual().name("lua-execution").start {
                interpreter.installControl(this)
                runLock.lock()
                try {
                    result = function.call(arguments)
                    status = ExecutionStatus.COMPLETED
                } catch (stopped: ExecutionStoppedError) {
                    status = ExecutionStatus.STOPPED
                } catch (interrupted: InterruptedException) {
                    status = ExecutionStatus.STOPPED
                } catch (failure: LuaError) {
                    error = failure
                    status = ExecutionStatus.FAILED
                } catch (failure: Exception) {
                    error = LuaError.of(failure.message ?: "error")
                    status = ExecutionStatus.FAILED
                } finally {
                    runLock.unlock()
                    interpreter.removeControl()
                    done.countDown()
                }
            }
    }

    fun checkpoint() {
        if (stopRequested) throw ExecutionStoppedError()
        if (pauseRequested) park()
    }

    private fun park() {
        val depth = runLock.holdCount
        repeat(depth) { runLock.unlock() }
        try {
            pauseLock.withLock {
                status = ExecutionStatus.PAUSED
                while (pauseRequested && !stopRequested) {
                    awaitQuietly()
                }
                status = ExecutionStatus.RUNNING
            }
        } finally {
            repeat(depth) { runLock.lock() }
        }
        if (stopRequested) throw ExecutionStoppedError()
    }

    private fun awaitQuietly() {
        try {
            unpaused.await()
        } catch (interrupted: InterruptedException) {
            // stop() interrupts to break the wait; the loop re-checks the flags.
        }
    }

    override fun pause() {
        pauseLock.withLock {
            if (!isTerminal()) pauseRequested = true
        }
    }

    override fun resume() {
        pauseLock.withLock {
            pauseRequested = false
            unpaused.signalAll()
        }
    }

    override fun stop() {
        pauseLock.withLock {
            if (isTerminal()) return
            stopRequested = true
            unpaused.signalAll()
        }
        worker?.interrupt()
    }

    override fun await(): List<LuaValue> {
        done.await()
        return when (status) {
            ExecutionStatus.COMPLETED -> result ?: emptyList()
            ExecutionStatus.FAILED -> throw error ?: LuaError.of("execution failed")
            else -> error("execution was stopped")
        }
    }

    override fun await(timeoutMillis: Long): Boolean = done.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun isTerminal(): Boolean =
        status == ExecutionStatus.COMPLETED ||
            status == ExecutionStatus.FAILED ||
            status == ExecutionStatus.STOPPED
}
