package de.fiereu.lua.parser

import de.fiereu.lua.common.LuaSyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LexerTest :
    StringSpec({
        fun types(source: String): List<TokenType> = Lexer(source).tokenize().map { it.type }

        fun tokens(source: String): List<Token> = Lexer(source).tokenize()

        "tokenizes a simple statement" {
            types("local x = 1") shouldContainExactly
                listOf(TokenType.LOCAL, TokenType.NAME, TokenType.ASSIGN, TokenType.INT, TokenType.EOF)
        }

        "distinguishes names from keywords" {
            val result = tokens("and And AND")
            result[0].type shouldBe TokenType.AND
            result[1].type shouldBe TokenType.NAME
            result[1].name shouldBe "And"
            result[2].type shouldBe TokenType.NAME
            result[2].name shouldBe "AND"
        }

        "recognizes the global keyword" {
            types("global x") shouldContainExactly
                listOf(TokenType.GLOBAL, TokenType.NAME, TokenType.EOF)
        }

        "lexes integer constants" {
            tokens("3 345 0xff 0xBEBADA").filter { it.type == TokenType.INT }.map { it.intValue } shouldBe
                listOf(3L, 345L, 255L, 0xBEBADA)
        }

        "lexes float constants" {
            val values =
                tokens("3.0 3.1416 314.16e-2 0.31416E1 34e1 0x0.1E 0xA23p-4 0X1.921FB54442D18P+1")
                    .filter { it.type == TokenType.FLOAT }
                    .map { it.floatValue }
            values shouldBe listOf(3.0, 3.1416, 3.1416, 3.1416, 340.0, 0.1171875, 162.1875, Math.PI)
        }

        "treats a leading dot as a float" {
            val token = tokens(".5").first()
            token.type shouldBe TokenType.FLOAT
            token.floatValue shouldBe 0.5
        }

        "distinguishes dot, concat, and ellipsis" {
            types("a.b a..b ...") shouldContainExactly
                listOf(
                    TokenType.NAME,
                    TokenType.DOT,
                    TokenType.NAME,
                    TokenType.NAME,
                    TokenType.CONCAT,
                    TokenType.NAME,
                    TokenType.ELLIPSIS,
                    TokenType.EOF,
                )
        }

        "lexes all operator tokens" {
            types("+ - * / // % ^ # & ~ | << >> == ~= <= >= < > = ( ) { } [ ] :: ; : , ..") shouldContainExactly
                listOf(
                    TokenType.PLUS,
                    TokenType.MINUS,
                    TokenType.STAR,
                    TokenType.SLASH,
                    TokenType.DOUBLE_SLASH,
                    TokenType.PERCENT,
                    TokenType.CARET,
                    TokenType.HASH,
                    TokenType.AMPERSAND,
                    TokenType.TILDE,
                    TokenType.PIPE,
                    TokenType.SHIFT_LEFT,
                    TokenType.SHIFT_RIGHT,
                    TokenType.EQUAL,
                    TokenType.NOT_EQUAL,
                    TokenType.LESS_EQUAL,
                    TokenType.GREATER_EQUAL,
                    TokenType.LESS,
                    TokenType.GREATER,
                    TokenType.ASSIGN,
                    TokenType.LEFT_PAREN,
                    TokenType.RIGHT_PAREN,
                    TokenType.LEFT_BRACE,
                    TokenType.RIGHT_BRACE,
                    TokenType.LEFT_BRACKET,
                    TokenType.RIGHT_BRACKET,
                    TokenType.DOUBLE_COLON,
                    TokenType.SEMICOLON,
                    TokenType.COLON,
                    TokenType.COMMA,
                    TokenType.CONCAT,
                    TokenType.EOF,
                )
        }

        "the five literal forms denote the same string" {
            val forms =
                listOf(
                    "a = 'alo\\n123\"'",
                    "a = \"alo\\n123\\\"\"",
                    "a = '\\97lo\\10\\04923\"'",
                    "a = [[alo\n123\"]]",
                    "a = [==[\nalo\n123\"]==]",
                )
            forms.forEach { source ->
                val string = tokens(source).first { it.type == TokenType.STRING }
                string.stringValue!!.latin1() shouldBe "alo\n123\""
            }
        }

        "processes hex, decimal, and z escapes" {
            val hex = tokens("\"\\x41\\x42\"").first { it.type == TokenType.STRING }
            hex.stringValue!!.latin1() shouldBe "AB"
            val z = tokens("\"a\\z   \n   b\"").first { it.type == TokenType.STRING }
            z.stringValue!!.latin1() shouldBe "ab"
        }

        "encodes unicode escapes as utf-8" {
            val token = tokens("\"\\u{48}\\u{20AC}\"").first { it.type == TokenType.STRING }
            token.stringValue!!.toByteArray().toList() shouldBe
                listOf(0x48, 0xE2, 0x82, 0xAC).map { it.toByte() }
        }

        "skips short and long comments" {
            types("a -- comment\nb --[[ long\ncomment ]] c") shouldContainExactly
                listOf(TokenType.NAME, TokenType.NAME, TokenType.NAME, TokenType.EOF)
        }

        "tracks line numbers across newlines" {
            val result = tokens("a\nb\r\nc")
            result[0].position.line shouldBe 1
            result[1].position.line shouldBe 2
            result[2].position.line shouldBe 3
        }

        "rejects an unfinished string" {
            shouldThrow<LuaSyntaxException> { tokens("\"abc") }
        }

        "rejects a malformed number" {
            shouldThrow<LuaSyntaxException> { tokens("3x") }
        }
    })
