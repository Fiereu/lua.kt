package de.fiereu.lua.common

/**
 * Immutable byte string. Lua strings are sequences of bytes, not Unicode text,
 * so the lexer, the AST, and the runtime all carry their string data as
 * [LuaBytes] rather than a Kotlin [String].
 */
public class LuaBytes private constructor(
    private val data: ByteArray,
) {
    public val size: Int
        get() = data.size

    public operator fun get(index: Int): Byte = data[index]

    /** A defensive copy. Callers may mutate the returned array. */
    public fun toByteArray(): ByteArray = data.copyOf()

    /** Each byte mapped to the code point of the same value (lossless round-trip). */
    public fun latin1(): String {
        val chars = CharArray(data.size) { (data[it].toInt() and 0xFF).toChar() }
        return String(chars)
    }

    /** Decodes the bytes as UTF-8, replacing malformed sequences. */
    public fun utf8(): String = data.decodeToString()

    public operator fun plus(other: LuaBytes): LuaBytes = wrap(data + other.data)

    private val cachedHash: Int by lazy { data.contentHashCode() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is LuaBytes && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = cachedHash

    override fun toString(): String = utf8()

    public companion object {
        public val EMPTY: LuaBytes = LuaBytes(ByteArray(0))

        /** Copies [bytes] so later mutation of the caller's array is not observed. */
        public fun of(bytes: ByteArray): LuaBytes = if (bytes.isEmpty()) EMPTY else LuaBytes(bytes.copyOf())

        /** Takes ownership of [bytes] without copying. Only pass a freshly built array. */
        public fun wrap(bytes: ByteArray): LuaBytes = if (bytes.isEmpty()) EMPTY else LuaBytes(bytes)

        public fun of(text: String): LuaBytes = wrap(text.encodeToByteArray())

        public fun ofByte(value: Int): LuaBytes = LuaBytes(byteArrayOf(value.toByte()))
    }
}
