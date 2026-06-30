package de.fiereu.lua.runtime

import de.fiereu.lua.LuaCoroutine
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaValue
import java.util.concurrent.SynchronousQueue

/**
 * A coroutine backed by a JVM virtual thread. Resume and yield hand control over
 * a pair of synchronous channels, so only one side runs at a time. This keeps
 * yield working from anywhere in the tree-walking interpreter with no rewrite.
 */
internal class LuaCoroutineImpl(
    private val body: LuaFunction,
) : LuaCoroutine {
    private sealed interface Outcome

    private class Yielded(
        val values: List<LuaValue>,
    ) : Outcome

    private class Returned(
        val values: List<LuaValue>,
    ) : Outcome

    private class Failed(
        val error: LuaError,
    ) : Outcome

    private val resumeChannel = SynchronousQueue<List<LuaValue>>()
    private val yieldChannel = SynchronousQueue<Outcome>()
    private var started = false

    @Volatile
    override var status: LuaCoroutine.Status = LuaCoroutine.Status.SUSPENDED
        private set

    override fun resume(arguments: List<LuaValue>): List<LuaValue> {
        if (status != LuaCoroutine.Status.SUSPENDED) {
            throw LuaError.of("cannot resume non-suspended coroutine")
        }
        val previous = CoroutineSupport.currentOrNull()
        previous?.markNormal()
        status = LuaCoroutine.Status.RUNNING
        if (!started) {
            started = true
            startThread()
        }
        resumeChannel.put(arguments)
        val outcome = yieldChannel.take()
        previous?.markRunning()
        return resolve(outcome)
    }

    private fun resolve(outcome: Outcome): List<LuaValue> =
        when (outcome) {
            is Yielded -> {
                status = LuaCoroutine.Status.SUSPENDED
                outcome.values
            }

            is Returned -> {
                status = LuaCoroutine.Status.DEAD
                outcome.values
            }

            is Failed -> {
                status = LuaCoroutine.Status.DEAD
                throw outcome.error
            }
        }

    fun doYield(values: List<LuaValue>): List<LuaValue> {
        yieldChannel.put(Yielded(values))
        return resumeChannel.take()
    }

    fun close(): List<LuaValue> =
        when (status) {
            LuaCoroutine.Status.DEAD, LuaCoroutine.Status.SUSPENDED -> {
                status = LuaCoroutine.Status.DEAD
                listOf(de.fiereu.lua.LuaBoolean.TRUE)
            }

            else -> {
                throw LuaError.of("cannot close a non-suspended coroutine")
            }
        }

    private fun markNormal() {
        status = LuaCoroutine.Status.NORMAL
    }

    private fun markRunning() {
        status = LuaCoroutine.Status.RUNNING
    }

    private fun startThread() {
        Thread.ofVirtual().name("lua-coroutine").start {
            CoroutineSupport.enter(this)
            val initial = resumeChannel.take()
            val outcome =
                try {
                    Returned(body.call(initial))
                } catch (error: LuaError) {
                    Failed(error)
                } catch (error: Exception) {
                    Failed(LuaError.of(error.message ?: "error in coroutine"))
                }
            yieldChannel.put(outcome)
        }
    }
}

/** Tracks the coroutine running on the current thread so `yield` can find it. */
internal object CoroutineSupport {
    private val current = ThreadLocal<LuaCoroutineImpl?>()

    fun currentOrNull(): LuaCoroutineImpl? = current.get()

    fun enter(coroutine: LuaCoroutineImpl) {
        current.set(coroutine)
    }

    fun yield(values: List<LuaValue>): List<LuaValue> {
        val coroutine = current.get() ?: throw LuaError.of("attempt to yield from outside a coroutine")
        return coroutine.doYield(values)
    }
}
