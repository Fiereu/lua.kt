package de.fiereu.lua

import java.io.File

/** A category of security-sensitive operation a script may attempt. */
public enum class SecurityAction {
    /** Opening a file for reading. */
    READ_FILE,

    /** Opening a file for writing or appending (this also covers creating a file). */
    WRITE_FILE,

    /** Deleting a file. */
    DELETE_FILE,

    /** Renaming or moving a file. */
    RENAME_FILE,

    /** Creating a temporary file. */
    TEMP_FILE,

    /** Running an external command. */
    EXECUTE,

    /** Terminating the host process. */
    EXIT,

    /** Reading a host environment variable. */
    READ_ENV,

    /** Compiling Lua source into a function (`load`, `loadfile`, `dofile`). */
    LOAD_CODE,

    /** Loading a module by name (`require`). */
    LOAD_MODULE,
}

/**
 * A description of a single security-sensitive operation, passed to a
 * [SecurityPolicy] for an allow or deny decision.
 */
public class SecurityRequest(
    /** What the script is trying to do. */
    public val action: SecurityAction,
    /** The primary subject as the script gave it: a path, command, env name, module, or chunk name. */
    public val target: String,
    /**
     * For file actions, [target] resolved to an absolute, lexically-normalized path
     * (with `..` segments collapsed). Use this for safe directory-prefix checks. Null for non-file actions.
     */
    public val resolvedPath: String? = null,
    /** The secondary subject, currently the destination of a rename. */
    public val secondaryTarget: String? = null,
)

/**
 * A user-defined gate consulted before the standard library performs a sensitive
 * operation. Set it on [LuaRuntimeConfig]. Returning false makes the operation
 * raise a [LuaError]. With no policy set, every operation is allowed.
 */
public fun interface SecurityPolicy {
    /** Returns true to allow [request], false to deny it. */
    public fun isAllowed(request: SecurityRequest): Boolean
}

/** Ready-made [SecurityPolicy] building blocks for common sandboxing needs. */
public object SecurityPolicies {
    /**
     * Confines every file action to within [directory] (resolved absolutely). Path
     * traversal with `..` is collapsed before the check, so it cannot escape. Non-file
     * actions (execute, exit, env, code/module loading) are allowed; compose with the
     * others via [all] to restrict those too.
     */
    public fun directoryJail(directory: String): SecurityPolicy {
        val root = File(directory).absoluteFile.normalize().path
        return SecurityPolicy { request ->
            if (!isFileAction(request.action)) {
                true
            } else {
                inside(request.resolvedPath, root) &&
                    request.secondaryTarget?.let { inside(File(it).absoluteFile.normalize().path, root) } != false
            }
        }
    }

    /** Allows reads but denies any operation that creates, changes, or removes a file. */
    public fun readOnly(): SecurityPolicy =
        SecurityPolicy { request ->
            when (request.action) {
                SecurityAction.WRITE_FILE,
                SecurityAction.DELETE_FILE,
                SecurityAction.RENAME_FILE,
                SecurityAction.TEMP_FILE,
                -> false

                else -> true
            }
        }

    /** Denies running external commands and terminating the host process. */
    public fun denyProcess(): SecurityPolicy =
        SecurityPolicy { request ->
            request.action != SecurityAction.EXECUTE && request.action != SecurityAction.EXIT
        }

    /** Combines [policies] so an operation is allowed only when every policy allows it. */
    public fun all(vararg policies: SecurityPolicy): SecurityPolicy = SecurityPolicy { request -> policies.all { it.isAllowed(request) } }

    private fun isFileAction(action: SecurityAction): Boolean =
        action == SecurityAction.READ_FILE ||
            action == SecurityAction.WRITE_FILE ||
            action == SecurityAction.DELETE_FILE ||
            action == SecurityAction.RENAME_FILE ||
            action == SecurityAction.TEMP_FILE

    private fun inside(
        path: String?,
        root: String,
    ): Boolean {
        if (path == null) return false
        return path == root || path.startsWith(root + File.separator)
    }
}
