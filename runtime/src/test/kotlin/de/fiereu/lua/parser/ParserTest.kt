package de.fiereu.lua.parser

import de.fiereu.lua.common.LuaSyntaxException
import de.fiereu.lua.parser.ast.AssignStat
import de.fiereu.lua.parser.ast.Attribute
import de.fiereu.lua.parser.ast.BinaryExpr
import de.fiereu.lua.parser.ast.BinaryOp
import de.fiereu.lua.parser.ast.BoolLiteral
import de.fiereu.lua.parser.ast.CallExpr
import de.fiereu.lua.parser.ast.Expr
import de.fiereu.lua.parser.ast.FloatLiteral
import de.fiereu.lua.parser.ast.FunctionExpr
import de.fiereu.lua.parser.ast.GenericFor
import de.fiereu.lua.parser.ast.GlobalDeclaration
import de.fiereu.lua.parser.ast.GlobalWildcard
import de.fiereu.lua.parser.ast.IndexExpr
import de.fiereu.lua.parser.ast.IntLiteral
import de.fiereu.lua.parser.ast.KeyedField
import de.fiereu.lua.parser.ast.LocalDeclaration
import de.fiereu.lua.parser.ast.MethodCallExpr
import de.fiereu.lua.parser.ast.NameRef
import de.fiereu.lua.parser.ast.NamedField
import de.fiereu.lua.parser.ast.NilLiteral
import de.fiereu.lua.parser.ast.NumericFor
import de.fiereu.lua.parser.ast.ParenExpr
import de.fiereu.lua.parser.ast.PositionalField
import de.fiereu.lua.parser.ast.ReturnStat
import de.fiereu.lua.parser.ast.Stat
import de.fiereu.lua.parser.ast.StringLiteral
import de.fiereu.lua.parser.ast.TableExpr
import de.fiereu.lua.parser.ast.UnaryExpr
import de.fiereu.lua.parser.ast.UnaryOp
import de.fiereu.lua.parser.ast.VarargExpr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ParserTest :
    StringSpec({
        fun firstStatement(source: String): Stat =
            LuaParser
                .parse(source)
                .body.statements
                .first()

        fun expressionOf(source: String): Expr {
            val ret = LuaParser.parse("return $source").body.returnStatement!!
            return ret.values.first()
        }

        fun render(expr: Expr): String =
            when (expr) {
                is IntLiteral -> expr.value.toString()
                is FloatLiteral -> expr.value.toString()
                is BoolLiteral -> expr.value.toString()
                is NilLiteral -> "nil"
                is StringLiteral -> "\"${expr.value.latin1()}\""
                is NameRef -> expr.name
                is VarargExpr -> "..."
                is ParenExpr -> "(paren ${render(expr.inner)})"
                is UnaryExpr -> "(${expr.operator.symbol()} ${render(expr.operand)})"
                is BinaryExpr -> "(${expr.operator.symbol()} ${render(expr.left)} ${render(expr.right)})"
                is IndexExpr -> "(index ${render(expr.receiver)} ${render(expr.key)})"
                is CallExpr -> "(call ${render(expr.callee)}${expr.arguments.joinToString("") { " " + render(it) }})"
                is MethodCallExpr -> "(mcall ${render(expr.receiver)} ${expr.method})"
                else -> expr.toString()
            }

        "respects arithmetic precedence" {
            render(expressionOf("1 + 2 * 3")) shouldBe "(+ 1 (* 2 3))"
        }

        "left-associates additive operators" {
            render(expressionOf("1 + 2 - 3")) shouldBe "(- (+ 1 2) 3)"
        }

        "right-associates exponentiation" {
            render(expressionOf("2 ^ 2 ^ 3")) shouldBe "(^ 2 (^ 2 3))"
        }

        "right-associates concatenation" {
            render(expressionOf("1 .. 2 .. 3")) shouldBe "(.. 1 (.. 2 3))"
        }

        "binds exponentiation tighter than unary minus" {
            render(expressionOf("-2 ^ 2")) shouldBe "(neg (^ 2 2))"
        }

        "binds unary not tighter than comparison" {
            render(expressionOf("not a == b")) shouldBe "(== (not a) b)"
        }

        "orders logical operators" {
            render(expressionOf("a and b or c")) shouldBe "(or (and a b) c)"
        }

        "parses suffix chains" {
            render(expressionOf("a.b[c].d")) shouldBe "(index (index (index a \"b\") c) \"d\")"
        }

        "parses calls and method calls" {
            render(expressionOf("f(1)(2)")) shouldBe "(call (call f 1) 2)"
            render(expressionOf("o:m(1)")) shouldBe "(mcall o m)"
        }

        "parses local declarations with attributes" {
            val statement = firstStatement("local x <const>, y = 1, 2")
            statement.shouldBeInstanceOf<LocalDeclaration>()
            statement.names.map { it.name to it.attribute } shouldBe
                listOf("x" to Attribute.CONST, "y" to null)
            statement.values.size shouldBe 2
        }

        "rejects close on global declarations" {
            shouldThrow<LuaSyntaxException> { firstStatement("global x <close>") }
        }

        "parses the collective global declaration" {
            (firstStatement("global *") as GlobalWildcard).attribute shouldBe null
            (firstStatement("global <const> *") as GlobalWildcard).attribute shouldBe Attribute.CONST
        }

        "parses an explicit global declaration" {
            val statement = firstStatement("global x, y = 1, 2")
            statement.shouldBeInstanceOf<GlobalDeclaration>()
            statement.names.map { it.name } shouldBe listOf("x", "y")
        }

        "desugars a method function statement with implicit self" {
            val statement = firstStatement("function a.b:c(x) end")
            statement.shouldBeInstanceOf<AssignStat>()
            val target = statement.targets.first()
            target.shouldBeInstanceOf<IndexExpr>()
            val function = statement.values.first()
            function.shouldBeInstanceOf<FunctionExpr>()
            function.parameters shouldBe listOf("self", "x")
        }

        "parses a named vararg parameter" {
            val statement = firstStatement("local f = function(a, ...rest) return rest end")
            val function = (statement as LocalDeclaration).values.first() as FunctionExpr
            function.parameters shouldBe listOf("a")
            function.isVararg shouldBe true
            function.varargName shouldBe "rest"
        }

        "parses numeric and generic for loops" {
            (firstStatement("for i = 1, 10, 2 do end") as NumericFor).name shouldBe "i"
            (firstStatement("for k, v in pairs(t) do end") as GenericFor).names shouldBe listOf("k", "v")
        }

        "parses table constructors" {
            val table = expressionOf("{1, x = 2, [k] = 3}") as TableExpr
            table.fields[0].shouldBeInstanceOf<PositionalField>()
            table.fields[1].shouldBeInstanceOf<NamedField>()
            table.fields[2].shouldBeInstanceOf<KeyedField>()
        }

        "keeps parentheses to mark single-value truncation" {
            val ret = LuaParser.parse("return (f())").body.returnStatement as ReturnStat
            ret.values.first().shouldBeInstanceOf<ParenExpr>()
        }

        "rejects assignment to a non-assignable expression" {
            shouldThrow<LuaSyntaxException> { firstStatement("(a) = 1") }
        }

        "rejects a bare non-call expression statement" {
            shouldThrow<LuaSyntaxException> { firstStatement("1 + 2") }
        }
    })

private fun BinaryOp.symbol(): String =
    when (this) {
        BinaryOp.ADD -> "+"
        BinaryOp.SUB -> "-"
        BinaryOp.MUL -> "*"
        BinaryOp.DIV -> "/"
        BinaryOp.IDIV -> "//"
        BinaryOp.MOD -> "%"
        BinaryOp.POW -> "^"
        BinaryOp.CONCAT -> ".."
        BinaryOp.EQ -> "=="
        BinaryOp.NE -> "~="
        BinaryOp.LT -> "<"
        BinaryOp.LE -> "<="
        BinaryOp.GT -> ">"
        BinaryOp.GE -> ">="
        BinaryOp.AND -> "and"
        BinaryOp.OR -> "or"
        BinaryOp.BAND -> "&"
        BinaryOp.BOR -> "|"
        BinaryOp.BXOR -> "~"
        BinaryOp.SHL -> "<<"
        BinaryOp.SHR -> ">>"
    }

private fun UnaryOp.symbol(): String =
    when (this) {
        UnaryOp.NEG -> "neg"
        UnaryOp.NOT -> "not"
        UnaryOp.LEN -> "len"
        UnaryOp.BNOT -> "bnot"
    }
