package de.fiereu.lua.parser.ast

import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.SourcePosition

/** A variable attribute from `<...>`. Lua 5.5 defines `const` and `close`. */
internal enum class Attribute { CONST, CLOSE }

/** A declared name with its optional attribute. */
internal data class AttribName(
    val name: String,
    val attribute: Attribute?,
)

/** A parsed chunk: the body of an implicit vararg function over `_ENV`. */
internal data class Chunk(
    val body: Block,
)

/** A sequence of statements with an optional trailing return. */
internal data class Block(
    val statements: List<Stat>,
    val returnStatement: ReturnStat?,
)

internal sealed interface Stat {
    val position: SourcePosition
}

internal sealed interface Expr {
    val position: SourcePosition
}

internal data class LocalDeclaration(
    val names: List<AttribName>,
    val values: List<Expr>,
    override val position: SourcePosition,
) : Stat

internal data class LocalFunctionDeclaration(
    val name: String,
    val body: FunctionExpr,
    override val position: SourcePosition,
) : Stat

internal data class GlobalDeclaration(
    val names: List<AttribName>,
    val values: List<Expr>,
    override val position: SourcePosition,
) : Stat

internal data class GlobalWildcard(
    val attribute: Attribute?,
    override val position: SourcePosition,
) : Stat

internal data class AssignStat(
    val targets: List<Expr>,
    val values: List<Expr>,
    override val position: SourcePosition,
) : Stat

internal data class CallStat(
    val call: Expr,
    override val position: SourcePosition,
) : Stat

internal data class DoBlock(
    val body: Block,
    override val position: SourcePosition,
) : Stat

internal data class WhileLoop(
    val condition: Expr,
    val body: Block,
    override val position: SourcePosition,
) : Stat

internal data class RepeatLoop(
    val body: Block,
    val condition: Expr,
    override val position: SourcePosition,
) : Stat

internal data class IfClause(
    val condition: Expr,
    val body: Block,
)

internal data class IfStat(
    val clauses: List<IfClause>,
    val elseBody: Block?,
    override val position: SourcePosition,
) : Stat

internal data class NumericFor(
    val name: String,
    val start: Expr,
    val limit: Expr,
    val step: Expr?,
    val body: Block,
    override val position: SourcePosition,
) : Stat

internal data class GenericFor(
    val names: List<String>,
    val expressions: List<Expr>,
    val body: Block,
    override val position: SourcePosition,
) : Stat

internal data class ReturnStat(
    val values: List<Expr>,
    override val position: SourcePosition,
) : Stat

internal data class BreakStat(
    override val position: SourcePosition,
) : Stat

internal data class GotoStat(
    val label: String,
    override val position: SourcePosition,
) : Stat

internal data class LabelStat(
    val name: String,
    override val position: SourcePosition,
) : Stat

internal data class NilLiteral(
    override val position: SourcePosition,
) : Expr

internal data class BoolLiteral(
    val value: Boolean,
    override val position: SourcePosition,
) : Expr

internal data class IntLiteral(
    val value: Long,
    override val position: SourcePosition,
) : Expr

internal data class FloatLiteral(
    val value: Double,
    override val position: SourcePosition,
) : Expr

internal data class StringLiteral(
    val value: LuaBytes,
    override val position: SourcePosition,
) : Expr

internal data class VarargExpr(
    override val position: SourcePosition,
) : Expr

internal data class NameRef(
    val name: String,
    override val position: SourcePosition,
) : Expr

internal data class IndexExpr(
    val receiver: Expr,
    val key: Expr,
    override val position: SourcePosition,
) : Expr

internal data class CallExpr(
    val callee: Expr,
    val arguments: List<Expr>,
    override val position: SourcePosition,
) : Expr

internal data class MethodCallExpr(
    val receiver: Expr,
    val method: String,
    val arguments: List<Expr>,
    override val position: SourcePosition,
) : Expr

internal data class FunctionExpr(
    val parameters: List<String>,
    val isVararg: Boolean,
    val varargName: String?,
    val body: Block,
    override val position: SourcePosition,
) : Expr

internal data class TableExpr(
    val fields: List<TableField>,
    override val position: SourcePosition,
) : Expr

internal data class BinaryExpr(
    val operator: BinaryOp,
    val left: Expr,
    val right: Expr,
    override val position: SourcePosition,
) : Expr

internal data class UnaryExpr(
    val operator: UnaryOp,
    val operand: Expr,
    override val position: SourcePosition,
) : Expr

/** Parentheses are semantically relevant: they truncate a multi-value result to one. */
internal data class ParenExpr(
    val inner: Expr,
    override val position: SourcePosition,
) : Expr

internal sealed interface TableField

internal data class PositionalField(
    val value: Expr,
) : TableField

internal data class NamedField(
    val key: String,
    val value: Expr,
) : TableField

internal data class KeyedField(
    val key: Expr,
    val value: Expr,
) : TableField

internal enum class BinaryOp {
    ADD,
    SUB,
    MUL,
    DIV,
    IDIV,
    MOD,
    POW,
    CONCAT,
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    AND,
    OR,
    BAND,
    BOR,
    BXOR,
    SHL,
    SHR,
}

internal enum class UnaryOp {
    NEG,
    NOT,
    LEN,
    BNOT,
}
