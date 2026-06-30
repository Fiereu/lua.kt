package de.fiereu.lua.runtime

import de.fiereu.lua.LuaString

/** Metamethod key names, interned as [LuaString]s to avoid re-wrapping on lookup. */
internal object Metamethods {
    val INDEX = LuaString.of("__index")
    val NEWINDEX = LuaString.of("__newindex")
    val CALL = LuaString.of("__call")
    val CONCAT = LuaString.of("__concat")
    val LEN = LuaString.of("__len")
    val EQ = LuaString.of("__eq")
    val LT = LuaString.of("__lt")
    val LE = LuaString.of("__le")
    val UNM = LuaString.of("__unm")
    val BNOT = LuaString.of("__bnot")
    val TOSTRING = LuaString.of("__tostring")
    val NAME = LuaString.of("__name")
    val CLOSE = LuaString.of("__close")

    val ADD = LuaString.of("__add")
    val SUB = LuaString.of("__sub")
    val MUL = LuaString.of("__mul")
    val DIV = LuaString.of("__div")
    val MOD = LuaString.of("__mod")
    val POW = LuaString.of("__pow")
    val IDIV = LuaString.of("__idiv")
    val BAND = LuaString.of("__band")
    val BOR = LuaString.of("__bor")
    val BXOR = LuaString.of("__bxor")
    val SHL = LuaString.of("__shl")
    val SHR = LuaString.of("__shr")
}
