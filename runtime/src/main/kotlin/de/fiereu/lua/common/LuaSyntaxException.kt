package de.fiereu.lua.common

/**
 * A lexing or parsing error. The [message] follows Lua's `chunkname:line: detail`
 * convention so it reads the same as a reference interpreter's syntax errors.
 */
internal class LuaSyntaxException(
    val detail: String,
    val position: SourcePosition,
    val chunkName: String? = null,
) : RuntimeException(format(chunkName, position, detail)) {
    companion object {
        private fun format(
            chunkName: String?,
            position: SourcePosition,
            detail: String,
        ): String {
            val location = chunkName?.let { "$it:" } ?: ""
            return "$location${position.line}: $detail"
        }
    }
}
