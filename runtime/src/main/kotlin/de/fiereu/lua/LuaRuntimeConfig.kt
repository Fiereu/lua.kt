package de.fiereu.lua

import de.fiereu.lua.common.LuaBytes

/** The Lua language version a runtime targets. */
public enum class LuaVersion { LUA_5_1, LUA_5_2, LUA_5_3, LUA_5_4, LUA_5_5 }

/** An installable library. Custom libraries simply implement this. */
public fun interface LuaLibrary {
    public fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    )
}

/**
 * Runtime configuration. Limits matter when running untrusted scripts.
 */
public data class LuaRuntimeConfig(
    val version: LuaVersion = LuaVersion.LUA_5_5,
    val libraries: Set<LuaLibrary> = StdLibs.SAFE_DEFAULT,
    /** Maximum call depth, guarding against runaway recursion. */
    val maxCallDepth: Int = 200,
    /** Where `print` and friends write. Receives raw bytes. */
    val standardOutput: (LuaBytes) -> Unit = { bytes -> defaultStandardOutput(bytes) },
    /** A host debugger attached from the start, or null. Also settable via [LuaRuntime.attachDebugger]. */
    val debugger: Debugger? = null,
    /** A gate consulted before sensitive stdlib operations (file, process, env, code loading). Null allows everything. */
    val securityPolicy: SecurityPolicy? = null,
) {
    private companion object {
        fun defaultStandardOutput(bytes: LuaBytes) {
            System.out.write(bytes.toByteArray())
            System.out.flush()
        }
    }
}
