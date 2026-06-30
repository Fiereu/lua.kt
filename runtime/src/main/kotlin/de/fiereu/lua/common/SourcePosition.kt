package de.fiereu.lua.common

/** A one-based line and column into a source chunk. */
internal data class SourcePosition(
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$line:$column"

    companion object {
        val NONE = SourcePosition(0, 0)
    }
}
