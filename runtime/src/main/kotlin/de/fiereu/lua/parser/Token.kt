package de.fiereu.lua.parser

import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.SourcePosition

internal enum class TokenType {
    AND,
    BREAK,
    DO,
    ELSE,
    ELSEIF,
    END,
    FALSE,
    FOR,
    FUNCTION,
    GLOBAL,
    GOTO,
    IF,
    IN,
    LOCAL,
    NIL,
    NOT,
    OR,
    REPEAT,
    RETURN,
    THEN,
    TRUE,
    UNTIL,
    WHILE,

    PLUS,
    MINUS,
    STAR,
    SLASH,
    DOUBLE_SLASH,
    PERCENT,
    CARET,
    HASH,
    AMPERSAND,
    TILDE,
    PIPE,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    EQUAL,
    NOT_EQUAL,
    LESS_EQUAL,
    GREATER_EQUAL,
    LESS,
    GREATER,
    ASSIGN,
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    DOUBLE_COLON,
    SEMICOLON,
    COLON,
    COMMA,
    DOT,
    CONCAT,
    ELLIPSIS,

    NAME,
    INT,
    FLOAT,
    STRING,

    EOF,
}

/**
 * A lexical token. Only the payload field matching [type] is populated: [name]
 * for [TokenType.NAME], [intValue] for [TokenType.INT], [floatValue] for
 * [TokenType.FLOAT], and [stringValue] for [TokenType.STRING].
 */
internal class Token(
    val type: TokenType,
    val position: SourcePosition,
    val name: String? = null,
    val intValue: Long = 0,
    val floatValue: Double = 0.0,
    val stringValue: LuaBytes? = null,
) {
    override fun toString(): String =
        when (type) {
            TokenType.NAME -> "NAME($name)"
            TokenType.INT -> "INT($intValue)"
            TokenType.FLOAT -> "FLOAT($floatValue)"
            TokenType.STRING -> "STRING"
            TokenType.EOF -> "<eof>"
            else -> type.name
        }

    companion object {
        val KEYWORDS: Map<String, TokenType> =
            mapOf(
                "and" to TokenType.AND,
                "break" to TokenType.BREAK,
                "do" to TokenType.DO,
                "else" to TokenType.ELSE,
                "elseif" to TokenType.ELSEIF,
                "end" to TokenType.END,
                "false" to TokenType.FALSE,
                "for" to TokenType.FOR,
                "function" to TokenType.FUNCTION,
                "global" to TokenType.GLOBAL,
                "goto" to TokenType.GOTO,
                "if" to TokenType.IF,
                "in" to TokenType.IN,
                "local" to TokenType.LOCAL,
                "nil" to TokenType.NIL,
                "not" to TokenType.NOT,
                "or" to TokenType.OR,
                "repeat" to TokenType.REPEAT,
                "return" to TokenType.RETURN,
                "then" to TokenType.THEN,
                "true" to TokenType.TRUE,
                "until" to TokenType.UNTIL,
                "while" to TokenType.WHILE,
            )
    }
}
