package de.fiereu.lua.parser

import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.LuaNumbers
import de.fiereu.lua.common.LuaNumeral
import de.fiereu.lua.common.LuaSyntaxException
import de.fiereu.lua.common.SourcePosition
import java.io.ByteArrayOutputStream

/**
 * Turns Lua source bytes into a stream of [Token]s following manual §3.1. The
 * lexer works on raw bytes so string literals stay byte-accurate, including
 * embedded zeros and arbitrary `\xNN` escapes.
 */
internal class Lexer(
    private val data: ByteArray,
    private val chunkName: String? = null,
) {
    private var pos = 0
    private var line = 1
    private var column = 1

    constructor(source: String, chunkName: String? = null) : this(source.encodeToByteArray(), chunkName)

    fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>()
        while (true) {
            val token = nextToken()
            tokens.add(token)
            if (token.type == TokenType.EOF) break
        }
        return tokens
    }

    fun nextToken(): Token {
        skipTrivia()
        val start = currentPosition()
        val c = peek()
        return when {
            c == -1 -> Token(TokenType.EOF, start)
            isNameStart(c) -> scanName(start)
            c in DIGIT_0..DIGIT_9 -> scanNumber(start)
            c == DOT_CODE && peek(1) in DIGIT_0..DIGIT_9 -> scanNumber(start)
            c == QUOTE_DOUBLE || c == QUOTE_SINGLE -> scanShortString(start, c)
            c == BRACKET_OPEN && (peek(1) == BRACKET_OPEN || peek(1) == EQUALS_CODE) -> scanLongStringToken(start)
            else -> scanOperator(start)
        }
    }

    private fun scanName(start: SourcePosition): Token {
        val begin = pos
        while (isNameContinue(peek())) advance()
        val text = String(data, begin, pos - begin, Charsets.US_ASCII)
        val keyword = Token.KEYWORDS[text]
        return if (keyword != null) Token(keyword, start) else Token(TokenType.NAME, start, name = text)
    }

    private fun scanNumber(start: SourcePosition): Token {
        val begin = pos
        val isHex = peek() == DIGIT_0 && (peek(1) == LOWER_X || peek(1) == UPPER_X)
        if (isHex) {
            advance()
            advance()
        }
        consumeNumberBody(isHex)
        if (isNameStart(peek())) advance()
        val lexeme = String(data, begin, pos - begin, Charsets.US_ASCII)
        val numeral =
            LuaNumbers.parseNumeral(lexeme)
                ?: throw error("malformed number near '$lexeme'", start)
        return when (numeral) {
            is LuaNumeral.Int -> Token(TokenType.INT, start, intValue = numeral.value)
            is LuaNumeral.Float -> Token(TokenType.FLOAT, start, floatValue = numeral.value)
        }
    }

    private fun consumeNumberBody(isHex: Boolean) {
        val firstExponent = if (isHex) LOWER_P else LOWER_E
        val secondExponent = if (isHex) UPPER_P else UPPER_E
        while (true) {
            val c = peek()
            when {
                c == DOT_CODE -> {
                    advance()
                }

                isHex && isHexDigit(c) -> {
                    advance()
                }

                !isHex && c in DIGIT_0..DIGIT_9 -> {
                    advance()
                }

                c == firstExponent || c == secondExponent -> {
                    advance()
                    if (peek() == PLUS_CODE || peek() == MINUS_CODE) advance()
                }

                else -> {
                    return
                }
            }
        }
    }

    private fun scanShortString(
        start: SourcePosition,
        quote: Int,
    ): Token {
        advance()
        val out = ByteArrayOutputStream()
        while (true) {
            val c = peek()
            when {
                c == -1 || c == NEWLINE_CODE || c == RETURN_CODE -> {
                    throw error("unfinished string", start)
                }

                c == quote -> {
                    advance()
                    return Token(TokenType.STRING, start, stringValue = LuaBytes.wrap(out.toByteArray()))
                }

                c == BACKSLASH_CODE -> {
                    advance()
                    readEscape(out)
                }

                else -> {
                    out.write(c)
                    advance()
                }
            }
        }
    }

    private fun readEscape(out: ByteArrayOutputStream) {
        when (val e = peek()) {
            LOWER_A -> {
                writeSimpleEscape(out, 7)
            }

            LOWER_B -> {
                writeSimpleEscape(out, 8)
            }

            LOWER_F -> {
                writeSimpleEscape(out, 12)
            }

            LOWER_N -> {
                writeSimpleEscape(out, 10)
            }

            LOWER_R -> {
                writeSimpleEscape(out, 13)
            }

            LOWER_T -> {
                writeSimpleEscape(out, 9)
            }

            LOWER_V -> {
                writeSimpleEscape(out, 11)
            }

            BACKSLASH_CODE -> {
                writeSimpleEscape(out, BACKSLASH_CODE)
            }

            QUOTE_DOUBLE -> {
                writeSimpleEscape(out, QUOTE_DOUBLE)
            }

            QUOTE_SINGLE -> {
                writeSimpleEscape(out, QUOTE_SINGLE)
            }

            NEWLINE_CODE, RETURN_CODE -> {
                newLine()
                out.write(NEWLINE_CODE)
            }

            LOWER_X -> {
                advance()
                out.write(readHexByte())
            }

            LOWER_Z -> {
                advance()
                skipWhitespaceRun()
            }

            LOWER_U -> {
                advance()
                readUnicodeEscape(out)
            }

            in DIGIT_0..DIGIT_9 -> {
                out.write(readDecimalByte())
            }

            else -> {
                throw error("invalid escape sequence near '\\${if (e == -1) "" else e.toChar()}'")
            }
        }
    }

    private fun writeSimpleEscape(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        out.write(value)
        advance()
    }

    private fun readHexByte(): Int {
        var value = 0
        repeat(2) {
            val c = peek()
            if (!isHexDigit(c)) throw error("hexadecimal digit expected")
            value = value * 16 + Character.digit(c, 16)
            advance()
        }
        return value
    }

    private fun readDecimalByte(): Int {
        var value = 0
        var count = 0
        while (count < 3 && peek() in DIGIT_0..DIGIT_9) {
            value = value * 10 + (peek() - DIGIT_0)
            advance()
            count++
        }
        if (value > 0xFF) throw error("decimal escape too large")
        return value
    }

    private fun readUnicodeEscape(out: ByteArrayOutputStream) {
        if (peek() != BRACE_OPEN) throw error("missing '{' in \\u{xxxx}")
        advance()
        if (!isHexDigit(peek())) throw error("hexadecimal digit expected")
        var codePoint = 0L
        while (isHexDigit(peek())) {
            codePoint = codePoint * 16 + Character.digit(peek(), 16)
            if (codePoint > MAX_UNICODE) throw error("UTF-8 value too large")
            advance()
        }
        if (peek() != BRACE_CLOSE) throw error("missing '}' in \\u{xxxx}")
        advance()
        encodeUtf8(codePoint, out)
    }

    private fun skipWhitespaceRun() {
        while (true) {
            when (peek()) {
                SPACE_CODE, TAB_CODE, VTAB_CODE, FORMFEED_CODE -> advance()
                NEWLINE_CODE, RETURN_CODE -> newLine()
                else -> return
            }
        }
    }

    private fun scanLongStringToken(start: SourcePosition): Token {
        val level = tryOpenLongBracket()
        if (level < 0) throw error("invalid long string delimiter", start)
        val content = readLongString(level, start)
        return Token(TokenType.STRING, start, stringValue = LuaBytes.wrap(content))
    }

    private fun readLongString(
        level: Int,
        start: SourcePosition,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (peek() == NEWLINE_CODE || peek() == RETURN_CODE) newLine()
        while (true) {
            val c = peek()
            when {
                c == -1 -> {
                    throw error("unfinished long string", start)
                }

                c == BRACKET_CLOSE && tryCloseLongBracket(level) -> {
                    return out.toByteArray()
                }

                c == NEWLINE_CODE || c == RETURN_CODE -> {
                    newLine()
                    out.write(NEWLINE_CODE)
                }

                else -> {
                    out.write(c)
                    advance()
                }
            }
        }
    }

    private fun scanOperator(start: SourcePosition): Token {
        val c = advance()
        return when (c) {
            PLUS_CODE -> Token(TokenType.PLUS, start)
            STAR_CODE -> Token(TokenType.STAR, start)
            PERCENT_CODE -> Token(TokenType.PERCENT, start)
            CARET_CODE -> Token(TokenType.CARET, start)
            HASH_CODE -> Token(TokenType.HASH, start)
            AMP_CODE -> Token(TokenType.AMPERSAND, start)
            PIPE_CODE -> Token(TokenType.PIPE, start)
            LPAREN_CODE -> Token(TokenType.LEFT_PAREN, start)
            RPAREN_CODE -> Token(TokenType.RIGHT_PAREN, start)
            BRACE_OPEN -> Token(TokenType.LEFT_BRACE, start)
            BRACE_CLOSE -> Token(TokenType.RIGHT_BRACE, start)
            BRACKET_OPEN -> Token(TokenType.LEFT_BRACKET, start)
            BRACKET_CLOSE -> Token(TokenType.RIGHT_BRACKET, start)
            SEMICOLON_CODE -> Token(TokenType.SEMICOLON, start)
            COMMA_CODE -> Token(TokenType.COMMA, start)
            MINUS_CODE -> Token(TokenType.MINUS, start)
            SLASH_CODE -> if (match(SLASH_CODE)) Token(TokenType.DOUBLE_SLASH, start) else Token(TokenType.SLASH, start)
            TILDE_CODE -> if (match(ASSIGN_CODE)) Token(TokenType.NOT_EQUAL, start) else Token(TokenType.TILDE, start)
            ASSIGN_CODE -> if (match(ASSIGN_CODE)) Token(TokenType.EQUAL, start) else Token(TokenType.ASSIGN, start)
            COLON_CODE -> if (match(COLON_CODE)) Token(TokenType.DOUBLE_COLON, start) else Token(TokenType.COLON, start)
            LESS_CODE -> scanLess(start)
            GREATER_CODE -> scanGreater(start)
            DOT_CODE -> scanDot(start)
            else -> throw error("unexpected symbol near '${describe(c)}'", start)
        }
    }

    private fun scanLess(start: SourcePosition): Token =
        when {
            match(ASSIGN_CODE) -> Token(TokenType.LESS_EQUAL, start)
            match(LESS_CODE) -> Token(TokenType.SHIFT_LEFT, start)
            else -> Token(TokenType.LESS, start)
        }

    private fun scanGreater(start: SourcePosition): Token =
        when {
            match(ASSIGN_CODE) -> Token(TokenType.GREATER_EQUAL, start)
            match(GREATER_CODE) -> Token(TokenType.SHIFT_RIGHT, start)
            else -> Token(TokenType.GREATER, start)
        }

    private fun scanDot(start: SourcePosition): Token {
        if (!match(DOT_CODE)) return Token(TokenType.DOT, start)
        return if (match(DOT_CODE)) Token(TokenType.ELLIPSIS, start) else Token(TokenType.CONCAT, start)
    }

    private fun skipTrivia() {
        while (true) {
            when (peek()) {
                SPACE_CODE, TAB_CODE, VTAB_CODE, FORMFEED_CODE -> advance()
                NEWLINE_CODE, RETURN_CODE -> newLine()
                MINUS_CODE -> if (peek(1) == MINUS_CODE) skipComment() else return
                else -> return
            }
        }
    }

    private fun skipComment() {
        advance()
        advance()
        if (peek() == BRACKET_OPEN) {
            val level = tryOpenLongBracket()
            if (level >= 0) {
                readLongString(level, currentPosition())
                return
            }
        }
        while (peek() != -1 && peek() != NEWLINE_CODE && peek() != RETURN_CODE) advance()
    }

    private fun tryOpenLongBracket(): Int {
        var offset = 1
        while (peek(offset) == EQUALS_CODE) offset++
        if (peek(offset) != BRACKET_OPEN) return -1
        val level = offset - 1
        repeat(offset + 1) { advance() }
        return level
    }

    private fun tryCloseLongBracket(level: Int): Boolean {
        var offset = 1
        while (peek(offset) == EQUALS_CODE) offset++
        if (offset - 1 != level || peek(offset) != BRACKET_CLOSE) return false
        repeat(offset + 1) { advance() }
        return true
    }

    private fun match(expected: Int): Boolean {
        if (peek() != expected) return false
        advance()
        return true
    }

    private fun peek(offset: Int = 0): Int {
        val index = pos + offset
        return if (index < data.size) data[index].toInt() and 0xFF else -1
    }

    private fun advance(): Int {
        val c = data[pos].toInt() and 0xFF
        pos++
        column++
        return c
    }

    private fun newLine() {
        val first = data[pos].toInt() and 0xFF
        pos++
        val next = peek()
        if ((next == NEWLINE_CODE || next == RETURN_CODE) && next != first) pos++
        line++
        column = 1
    }

    private fun currentPosition(): SourcePosition = SourcePosition(line, column)

    private fun error(
        message: String,
        position: SourcePosition = currentPosition(),
    ): LuaSyntaxException = LuaSyntaxException(message, position, chunkName)

    private fun describe(c: Int): String = if (c in 0x20..0x7E) c.toChar().toString() else "<\\$c>"

    private companion object {
        const val MAX_UNICODE = 0x7FFFFFFFL

        const val SPACE_CODE = ' '.code
        const val TAB_CODE = '\t'.code
        const val VTAB_CODE = 0x0B
        const val FORMFEED_CODE = 0x0C
        const val NEWLINE_CODE = '\n'.code
        const val RETURN_CODE = '\r'.code

        const val DIGIT_0 = '0'.code
        const val DIGIT_9 = '9'.code
        const val DOT_CODE = '.'.code
        const val QUOTE_DOUBLE = '"'.code
        const val QUOTE_SINGLE = '\''.code
        const val BACKSLASH_CODE = '\\'.code
        const val BRACKET_OPEN = '['.code
        const val BRACKET_CLOSE = ']'.code
        const val BRACE_OPEN = '{'.code
        const val BRACE_CLOSE = '}'.code
        const val EQUALS_CODE = '='.code

        const val PLUS_CODE = '+'.code
        const val MINUS_CODE = '-'.code
        const val STAR_CODE = '*'.code
        const val SLASH_CODE = '/'.code
        const val PERCENT_CODE = '%'.code
        const val CARET_CODE = '^'.code
        const val HASH_CODE = '#'.code
        const val AMP_CODE = '&'.code
        const val PIPE_CODE = '|'.code
        const val TILDE_CODE = '~'.code
        const val ASSIGN_CODE = '='.code
        const val LPAREN_CODE = '('.code
        const val RPAREN_CODE = ')'.code
        const val SEMICOLON_CODE = ';'.code
        const val COLON_CODE = ':'.code
        const val COMMA_CODE = ','.code
        const val LESS_CODE = '<'.code
        const val GREATER_CODE = '>'.code

        const val LOWER_A = 'a'.code
        const val LOWER_B = 'b'.code
        const val LOWER_E = 'e'.code
        const val LOWER_F = 'f'.code
        const val LOWER_N = 'n'.code
        const val LOWER_P = 'p'.code
        const val LOWER_R = 'r'.code
        const val LOWER_T = 't'.code
        const val LOWER_U = 'u'.code
        const val LOWER_V = 'v'.code
        const val LOWER_X = 'x'.code
        const val LOWER_Z = 'z'.code
        const val UPPER_E = 'E'.code
        const val UPPER_P = 'P'.code
        const val UPPER_X = 'X'.code

        fun isNameStart(c: Int): Boolean = c == '_'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

        fun isNameContinue(c: Int): Boolean = isNameStart(c) || c in DIGIT_0..DIGIT_9

        fun isHexDigit(c: Int): Boolean = c in DIGIT_0..DIGIT_9 || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

        fun encodeUtf8(
            codePoint: Long,
            out: ByteArrayOutputStream,
        ) {
            when {
                codePoint < 0x80 -> {
                    out.write(codePoint.toInt())
                }

                codePoint < 0x800 -> {
                    out.write((0xC0 or (codePoint shr 6).toInt()))
                    out.write(continuation(codePoint, 0))
                }

                codePoint < 0x10000 -> {
                    out.write((0xE0 or (codePoint shr 12).toInt()))
                    out.write(continuation(codePoint, 6))
                    out.write(continuation(codePoint, 0))
                }

                codePoint < 0x200000 -> {
                    out.write((0xF0 or (codePoint shr 18).toInt()))
                    out.write(continuation(codePoint, 12))
                    out.write(continuation(codePoint, 6))
                    out.write(continuation(codePoint, 0))
                }

                codePoint < 0x4000000 -> {
                    out.write((0xF8 or (codePoint shr 24).toInt()))
                    out.write(continuation(codePoint, 18))
                    out.write(continuation(codePoint, 12))
                    out.write(continuation(codePoint, 6))
                    out.write(continuation(codePoint, 0))
                }

                else -> {
                    out.write((0xFC or (codePoint shr 30).toInt()))
                    out.write(continuation(codePoint, 24))
                    out.write(continuation(codePoint, 18))
                    out.write(continuation(codePoint, 12))
                    out.write(continuation(codePoint, 6))
                    out.write(continuation(codePoint, 0))
                }
            }
        }

        private fun continuation(
            codePoint: Long,
            shift: Int,
        ): Int = 0x80 or ((codePoint shr shift).toInt() and 0x3F)
    }
}
