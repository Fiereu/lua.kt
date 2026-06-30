package de.fiereu.lua.parser

import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.LuaSyntaxException
import de.fiereu.lua.common.SourcePosition
import de.fiereu.lua.parser.ast.AssignStat
import de.fiereu.lua.parser.ast.AttribName
import de.fiereu.lua.parser.ast.Attribute
import de.fiereu.lua.parser.ast.BinaryExpr
import de.fiereu.lua.parser.ast.BinaryOp
import de.fiereu.lua.parser.ast.Block
import de.fiereu.lua.parser.ast.BoolLiteral
import de.fiereu.lua.parser.ast.BreakStat
import de.fiereu.lua.parser.ast.CallExpr
import de.fiereu.lua.parser.ast.CallStat
import de.fiereu.lua.parser.ast.Chunk
import de.fiereu.lua.parser.ast.DoBlock
import de.fiereu.lua.parser.ast.Expr
import de.fiereu.lua.parser.ast.FloatLiteral
import de.fiereu.lua.parser.ast.FunctionExpr
import de.fiereu.lua.parser.ast.GenericFor
import de.fiereu.lua.parser.ast.GlobalDeclaration
import de.fiereu.lua.parser.ast.GlobalWildcard
import de.fiereu.lua.parser.ast.GotoStat
import de.fiereu.lua.parser.ast.IfClause
import de.fiereu.lua.parser.ast.IfStat
import de.fiereu.lua.parser.ast.IndexExpr
import de.fiereu.lua.parser.ast.IntLiteral
import de.fiereu.lua.parser.ast.KeyedField
import de.fiereu.lua.parser.ast.LabelStat
import de.fiereu.lua.parser.ast.LocalDeclaration
import de.fiereu.lua.parser.ast.LocalFunctionDeclaration
import de.fiereu.lua.parser.ast.MethodCallExpr
import de.fiereu.lua.parser.ast.NameRef
import de.fiereu.lua.parser.ast.NamedField
import de.fiereu.lua.parser.ast.NilLiteral
import de.fiereu.lua.parser.ast.NumericFor
import de.fiereu.lua.parser.ast.ParenExpr
import de.fiereu.lua.parser.ast.PositionalField
import de.fiereu.lua.parser.ast.RepeatLoop
import de.fiereu.lua.parser.ast.ReturnStat
import de.fiereu.lua.parser.ast.Stat
import de.fiereu.lua.parser.ast.StringLiteral
import de.fiereu.lua.parser.ast.TableExpr
import de.fiereu.lua.parser.ast.TableField
import de.fiereu.lua.parser.ast.UnaryExpr
import de.fiereu.lua.parser.ast.UnaryOp
import de.fiereu.lua.parser.ast.VarargExpr
import de.fiereu.lua.parser.ast.WhileLoop

/** Recursive-descent statement parser with a Pratt expression parser (manual §3.4.8). */
internal class Parser(
    private val tokens: List<Token>,
    private val chunkName: String? = null,
) {
    private var current = 0

    fun parse(): Chunk {
        val block = parseBlock()
        expect(TokenType.EOF, "'<eof>'")
        return Chunk(block)
    }

    private fun parseBlock(): Block {
        val statements = ArrayList<Stat>()
        var returnStatement: ReturnStat? = null
        while (!isBlockEnd()) {
            if (check(TokenType.RETURN)) {
                returnStatement = parseReturn()
                break
            }
            val statement = parseStatement()
            if (statement != null) statements.add(statement)
        }
        return Block(statements, returnStatement)
    }

    private fun isBlockEnd(): Boolean =
        peek().type in
            setOf(
                TokenType.EOF,
                TokenType.END,
                TokenType.ELSE,
                TokenType.ELSEIF,
                TokenType.UNTIL,
            )

    private fun parseStatement(): Stat? {
        val token = peek()
        return when (token.type) {
            TokenType.SEMICOLON -> {
                advance()
                null
            }

            TokenType.IF -> {
                parseIf()
            }

            TokenType.WHILE -> {
                parseWhile()
            }

            TokenType.DO -> {
                parseDo()
            }

            TokenType.FOR -> {
                parseFor()
            }

            TokenType.REPEAT -> {
                parseRepeat()
            }

            TokenType.FUNCTION -> {
                parseFunctionStatement()
            }

            TokenType.LOCAL -> {
                parseLocal()
            }

            TokenType.GLOBAL -> {
                parseGlobal()
            }

            TokenType.DOUBLE_COLON -> {
                parseLabel()
            }

            TokenType.BREAK -> {
                BreakStat(advance().position)
            }

            TokenType.GOTO -> {
                parseGoto()
            }

            else -> {
                parseExpressionStatement()
            }
        }
    }

    private fun parseIf(): Stat {
        val position = advance().position
        val clauses = ArrayList<IfClause>()
        clauses.add(parseIfClause())
        while (match(TokenType.ELSEIF)) {
            clauses.add(parseIfClause())
        }
        val elseBody = if (match(TokenType.ELSE)) parseBlock() else null
        expect(TokenType.END, "'end'")
        return IfStat(clauses, elseBody, position)
    }

    private fun parseIfClause(): IfClause {
        val condition = parseExpression()
        expect(TokenType.THEN, "'then'")
        return IfClause(condition, parseBlock())
    }

    private fun parseWhile(): Stat {
        val position = advance().position
        val condition = parseExpression()
        expect(TokenType.DO, "'do'")
        val body = parseBlock()
        expect(TokenType.END, "'end'")
        return WhileLoop(condition, body, position)
    }

    private fun parseDo(): Stat {
        val position = advance().position
        val body = parseBlock()
        expect(TokenType.END, "'end'")
        return DoBlock(body, position)
    }

    private fun parseRepeat(): Stat {
        val position = advance().position
        val body = parseBlock()
        expect(TokenType.UNTIL, "'until'")
        val condition = parseExpression()
        return RepeatLoop(body, condition, position)
    }

    private fun parseFor(): Stat {
        val position = advance().position
        val firstName = expectName()
        if (match(TokenType.ASSIGN)) {
            return parseNumericFor(firstName, position)
        }
        val names = arrayListOf(firstName)
        while (match(TokenType.COMMA)) names.add(expectName())
        expect(TokenType.IN, "'=' or 'in'")
        val expressions = parseExpressionList()
        expect(TokenType.DO, "'do'")
        val body = parseBlock()
        expect(TokenType.END, "'end'")
        return GenericFor(names, expressions, body, position)
    }

    private fun parseNumericFor(
        name: String,
        position: SourcePosition,
    ): Stat {
        val start = parseExpression()
        expect(TokenType.COMMA, "','")
        val limit = parseExpression()
        val step = if (match(TokenType.COMMA)) parseExpression() else null
        expect(TokenType.DO, "'do'")
        val body = parseBlock()
        expect(TokenType.END, "'end'")
        return NumericFor(name, start, limit, step, body, position)
    }

    private fun parseFunctionStatement(): Stat {
        val position = advance().position
        var target: Expr = NameRef(expectName(), position)
        while (match(TokenType.DOT)) {
            target = IndexExpr(target, StringLiteral(nameAsBytes(expectName()), position), position)
        }
        val isMethod = match(TokenType.COLON)
        if (isMethod) {
            target = IndexExpr(target, StringLiteral(nameAsBytes(expectName()), position), position)
        }
        val body = parseFunctionBody(position, implicitSelf = isMethod)
        return AssignStat(listOf(target), listOf(body), position)
    }

    private fun parseLocal(): Stat {
        val position = advance().position
        if (match(TokenType.FUNCTION)) {
            val name = expectName()
            val body = parseFunctionBody(position, implicitSelf = false)
            return LocalFunctionDeclaration(name, body, position)
        }
        val names = parseAttribNameList(allowClose = true)
        val values = if (match(TokenType.ASSIGN)) parseExpressionList() else emptyList()
        return LocalDeclaration(names, values, position)
    }

    private fun parseGlobal(): Stat {
        val position = advance().position
        if (match(TokenType.FUNCTION)) {
            val name = expectName()
            val body = parseFunctionBody(position, implicitSelf = false)
            return AssignStat(listOf(NameRef(name, position)), listOf(body), position)
        }
        val prefix = parseOptionalAttribute()
        if (match(TokenType.STAR)) {
            return GlobalWildcard(prefix, position)
        }
        val names = parseAttribNameList(allowClose = false, prefix = prefix)
        val values = if (match(TokenType.ASSIGN)) parseExpressionList() else emptyList()
        return GlobalDeclaration(names, values, position)
    }

    private fun parseAttribNameList(
        allowClose: Boolean,
        prefix: Attribute? = null,
    ): List<AttribName> {
        val leading = prefix ?: parseOptionalAttribute()
        val names = ArrayList<AttribName>()
        var closeCount = 0
        do {
            val name = expectName()
            val postfix = parseOptionalAttribute()
            val attribute = postfix ?: leading
            if (attribute == Attribute.CLOSE) {
                if (!allowClose) throw error("only local variables can have the close attribute")
                if (++closeCount > 1) throw error("multiple to-be-closed variables in a declaration")
            }
            names.add(AttribName(name, attribute))
        } while (match(TokenType.COMMA))
        return names
    }

    private fun parseOptionalAttribute(): Attribute? {
        if (!match(TokenType.LESS)) return null
        val name = expectName()
        expect(TokenType.GREATER, "'>'")
        return when (name) {
            "const" -> Attribute.CONST
            "close" -> Attribute.CLOSE
            else -> throw error("unknown attribute '$name'")
        }
    }

    private fun parseLabel(): Stat {
        val position = advance().position
        val name = expectName()
        expect(TokenType.DOUBLE_COLON, "'::'")
        return LabelStat(name, position)
    }

    private fun parseGoto(): Stat {
        val position = advance().position
        return GotoStat(expectName(), position)
    }

    private fun parseReturn(): ReturnStat {
        val position = advance().position
        val values =
            if (isBlockEnd() || check(TokenType.SEMICOLON)) {
                emptyList()
            } else {
                parseExpressionList()
            }
        match(TokenType.SEMICOLON)
        return ReturnStat(values, position)
    }

    private fun parseExpressionStatement(): Stat {
        val position = peek().position
        val first = parseSuffixedExpression()
        if (check(TokenType.ASSIGN) || check(TokenType.COMMA)) {
            return parseAssignment(first, position)
        }
        if (first !is CallExpr && first !is MethodCallExpr) {
            throw error("syntax error near '${peek()}'")
        }
        return CallStat(first, position)
    }

    private fun parseAssignment(
        first: Expr,
        position: SourcePosition,
    ): Stat {
        val targets = arrayListOf(first)
        while (match(TokenType.COMMA)) targets.add(parseSuffixedExpression())
        expect(TokenType.ASSIGN, "'='")
        val values = parseExpressionList()
        targets.forEach { target ->
            if (target !is NameRef && target !is IndexExpr) {
                throw error("cannot assign to this expression")
            }
        }
        return AssignStat(targets, values, position)
    }

    private fun parseExpressionList(): List<Expr> {
        val expressions = arrayListOf(parseExpression())
        while (match(TokenType.COMMA)) expressions.add(parseExpression())
        return expressions
    }

    private fun parseExpression(limit: Int = 0): Expr {
        var left = parseUnaryExpression()
        while (true) {
            val operator = binaryOperatorFor(peek().type) ?: break
            val priority = priorityOf(operator)
            if (priority.left <= limit) break
            val position = advance().position
            val right = parseExpression(priority.right)
            left = BinaryExpr(operator, left, right, position)
        }
        return left
    }

    private fun parseUnaryExpression(): Expr {
        val operator = unaryOperatorFor(peek().type)
        if (operator != null) {
            val position = advance().position
            val operand = parseExpression(UNARY_PRIORITY)
            return UnaryExpr(operator, operand, position)
        }
        return parseSimpleExpression()
    }

    private fun parseSimpleExpression(): Expr {
        val token = peek()
        return when (token.type) {
            TokenType.NIL -> NilLiteral(advance().position)
            TokenType.TRUE -> BoolLiteral(true, advance().position)
            TokenType.FALSE -> BoolLiteral(false, advance().position)
            TokenType.INT -> IntLiteral(advance().intValue, token.position)
            TokenType.FLOAT -> FloatLiteral(advance().floatValue, token.position)
            TokenType.STRING -> StringLiteral(advance().stringValue!!, token.position)
            TokenType.ELLIPSIS -> VarargExpr(advance().position)
            TokenType.LEFT_BRACE -> parseTable()
            TokenType.FUNCTION -> parseFunctionBody(advance().position, implicitSelf = false)
            else -> parseSuffixedExpression()
        }
    }

    private fun parseSuffixedExpression(): Expr {
        var expression = parsePrimaryExpression()
        while (true) {
            val token = peek()
            expression =
                when (token.type) {
                    TokenType.DOT -> {
                        advance()
                        IndexExpr(expression, StringLiteral(nameAsBytes(expectName()), token.position), token.position)
                    }

                    TokenType.LEFT_BRACKET -> {
                        advance()
                        val key = parseExpression()
                        expect(TokenType.RIGHT_BRACKET, "']'")
                        IndexExpr(expression, key, token.position)
                    }

                    TokenType.COLON -> {
                        advance()
                        val method = expectName()
                        MethodCallExpr(expression, method, parseArguments(), token.position)
                    }

                    TokenType.LEFT_PAREN, TokenType.LEFT_BRACE, TokenType.STRING -> {
                        CallExpr(expression, parseArguments(), token.position)
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parsePrimaryExpression(): Expr {
        val token = peek()
        return when (token.type) {
            TokenType.LEFT_PAREN -> {
                advance()
                val inner = parseExpression()
                expect(TokenType.RIGHT_PAREN, "')'")
                ParenExpr(inner, token.position)
            }

            TokenType.NAME -> {
                NameRef(advance().name!!, token.position)
            }

            else -> {
                throw error("unexpected symbol near '${peek()}'")
            }
        }
    }

    private fun parseArguments(): List<Expr> {
        val token = peek()
        return when (token.type) {
            TokenType.STRING -> {
                listOf(StringLiteral(advance().stringValue!!, token.position))
            }

            TokenType.LEFT_BRACE -> {
                listOf(parseTable())
            }

            TokenType.LEFT_PAREN -> {
                advance()
                val arguments = if (check(TokenType.RIGHT_PAREN)) emptyList() else parseExpressionList()
                expect(TokenType.RIGHT_PAREN, "')'")
                arguments
            }

            else -> {
                throw error("function arguments expected")
            }
        }
    }

    private fun parseTable(): Expr {
        val position = expect(TokenType.LEFT_BRACE, "'{'").position
        val fields = ArrayList<TableField>()
        while (!check(TokenType.RIGHT_BRACE)) {
            fields.add(parseTableField())
            if (!match(TokenType.COMMA) && !match(TokenType.SEMICOLON)) break
        }
        expect(TokenType.RIGHT_BRACE, "'}'")
        return TableExpr(fields, position)
    }

    private fun parseTableField(): TableField {
        if (check(TokenType.LEFT_BRACKET)) {
            advance()
            val key = parseExpression()
            expect(TokenType.RIGHT_BRACKET, "']'")
            expect(TokenType.ASSIGN, "'='")
            return KeyedField(key, parseExpression())
        }
        if (check(TokenType.NAME) && peek(1).type == TokenType.ASSIGN) {
            val name = advance().name!!
            advance()
            return NamedField(name, parseExpression())
        }
        return PositionalField(parseExpression())
    }

    private fun parseFunctionBody(
        position: SourcePosition,
        implicitSelf: Boolean,
    ): FunctionExpr {
        expect(TokenType.LEFT_PAREN, "'('")
        val parameters = ArrayList<String>()
        if (implicitSelf) parameters.add("self")
        var isVararg = false
        var varargName: String? = null
        if (!check(TokenType.RIGHT_PAREN)) {
            while (true) {
                if (match(TokenType.ELLIPSIS)) {
                    isVararg = true
                    if (check(TokenType.NAME)) varargName = advance().name
                    break
                }
                parameters.add(expectName())
                if (!match(TokenType.COMMA)) break
            }
        }
        expect(TokenType.RIGHT_PAREN, "')'")
        val body = parseBlock()
        expect(TokenType.END, "'end'")
        return FunctionExpr(parameters, isVararg, varargName, body, position)
    }

    private fun expectName(): String {
        val token = peek()
        if (token.type != TokenType.NAME) throw error("name expected near '$token'")
        advance()
        return token.name!!
    }

    private fun nameAsBytes(name: String) = LuaBytes.of(name)

    private fun peek(offset: Int = 0): Token = tokens[minOf(current + offset, tokens.size - 1)]

    private fun advance(): Token = tokens[current].also { if (current < tokens.size - 1) current++ }

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun expect(
        type: TokenType,
        description: String,
    ): Token {
        if (!check(type)) throw error("$description expected near '${peek()}'")
        return advance()
    }

    private fun error(message: String): LuaSyntaxException = LuaSyntaxException(message, peek().position, chunkName)

    private data class Priority(
        val left: Int,
        val right: Int,
    )

    private companion object {
        const val UNARY_PRIORITY = 12

        fun unaryOperatorFor(type: TokenType): UnaryOp? =
            when (type) {
                TokenType.MINUS -> UnaryOp.NEG
                TokenType.NOT -> UnaryOp.NOT
                TokenType.HASH -> UnaryOp.LEN
                TokenType.TILDE -> UnaryOp.BNOT
                else -> null
            }

        fun binaryOperatorFor(type: TokenType): BinaryOp? =
            when (type) {
                TokenType.PLUS -> BinaryOp.ADD
                TokenType.MINUS -> BinaryOp.SUB
                TokenType.STAR -> BinaryOp.MUL
                TokenType.SLASH -> BinaryOp.DIV
                TokenType.DOUBLE_SLASH -> BinaryOp.IDIV
                TokenType.PERCENT -> BinaryOp.MOD
                TokenType.CARET -> BinaryOp.POW
                TokenType.CONCAT -> BinaryOp.CONCAT
                TokenType.EQUAL -> BinaryOp.EQ
                TokenType.NOT_EQUAL -> BinaryOp.NE
                TokenType.LESS -> BinaryOp.LT
                TokenType.LESS_EQUAL -> BinaryOp.LE
                TokenType.GREATER -> BinaryOp.GT
                TokenType.GREATER_EQUAL -> BinaryOp.GE
                TokenType.AND -> BinaryOp.AND
                TokenType.OR -> BinaryOp.OR
                TokenType.AMPERSAND -> BinaryOp.BAND
                TokenType.PIPE -> BinaryOp.BOR
                TokenType.TILDE -> BinaryOp.BXOR
                TokenType.SHIFT_LEFT -> BinaryOp.SHL
                TokenType.SHIFT_RIGHT -> BinaryOp.SHR
                else -> null
            }

        fun priorityOf(operator: BinaryOp): Priority =
            when (operator) {
                BinaryOp.OR -> Priority(1, 1)
                BinaryOp.AND -> Priority(2, 2)
                BinaryOp.LT, BinaryOp.GT, BinaryOp.LE, BinaryOp.GE, BinaryOp.NE, BinaryOp.EQ -> Priority(3, 3)
                BinaryOp.BOR -> Priority(4, 4)
                BinaryOp.BXOR -> Priority(5, 5)
                BinaryOp.BAND -> Priority(6, 6)
                BinaryOp.SHL, BinaryOp.SHR -> Priority(7, 7)
                BinaryOp.CONCAT -> Priority(9, 8)
                BinaryOp.ADD, BinaryOp.SUB -> Priority(10, 10)
                BinaryOp.MUL, BinaryOp.DIV, BinaryOp.IDIV, BinaryOp.MOD -> Priority(11, 11)
                BinaryOp.POW -> Priority(14, 13)
            }
    }
}

/** Entry point: parse Lua source into an AST [Chunk]. */
internal object LuaParser {
    fun parse(
        source: String,
        chunkName: String? = null,
    ): Chunk = Parser(Lexer(source, chunkName).tokenize(), chunkName).parse()

    fun parse(
        source: ByteArray,
        chunkName: String? = null,
    ): Chunk = Parser(Lexer(source, chunkName).tokenize(), chunkName).parse()
}
