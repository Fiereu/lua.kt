package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaError

/** A single capture from a pattern match: either a substring or a position. */
internal sealed interface Capture

internal class StringCapture(
    val bytes: ByteArray,
) : Capture

internal class PositionCapture(
    val index: Int,
) : Capture

/** A successful match: the matched span plus its explicit captures. */
internal class PatternMatch(
    val start: Int,
    val end: Int,
    val captures: List<Capture>,
)

/**
 * Lua's pattern matcher (manual §6.5.1), ported from the reference `lstrlib.c`
 * algorithm. Works on raw bytes so it is byte-accurate.
 */
internal class LuaPattern(
    private val src: ByteArray,
    private val pat: ByteArray,
) {
    private val patEnd = pat.size
    private val srcEnd = src.size
    private var level = 0
    private val capStart = IntArray(MAX_CAPTURES)
    private val capLen = IntArray(MAX_CAPTURES)

    fun find(initial: Int): PatternMatch? {
        val anchored = patEnd > 0 && pat[0].toInt() == CARET
        val patternStart = if (anchored) 1 else 0
        var start = initial
        while (start <= srcEnd) {
            level = 0
            val end = match(start, patternStart)
            if (end != FAIL) return PatternMatch(start, end, captures())
            if (anchored) break
            start++
        }
        return null
    }

    private fun captures(): List<Capture> {
        val result = ArrayList<Capture>(level)
        for (i in 0 until level) {
            result.add(
                if (capLen[i] == CAP_POSITION) {
                    PositionCapture(capStart[i] + 1)
                } else {
                    StringCapture(src.copyOfRange(capStart[i], capStart[i] + capLen[i]))
                },
            )
        }
        return result
    }

    private fun match(
        sIn: Int,
        pIn: Int,
    ): Int {
        var s = sIn
        var p = pIn
        while (p < patEnd) {
            when (val outcome = step(s, p)) {
                is Step.Done -> {
                    return outcome.value
                }

                is Step.Advance -> {
                    s = outcome.s
                    p = outcome.p
                }
            }
        }
        return s
    }

    private fun step(
        s: Int,
        p: Int,
    ): Step {
        when (pat[p].toInt()) {
            OPEN_PAREN -> {
                return Step.Done(startCapture(s, p))
            }

            CLOSE_PAREN -> {
                return Step.Done(endCapture(s, p + 1))
            }

            DOLLAR -> {
                if (p + 1 == patEnd) return Step.Done(if (s == srcEnd) s else FAIL)
            }

            PERCENT -> {
                val special = percentStep(s, p)
                if (special != null) return special
            }
        }
        return itemStep(s, p)
    }

    private fun percentStep(
        s: Int,
        p: Int,
    ): Step? =
        when (pat[p + 1].toInt()) {
            LOWER_B -> {
                val end = matchBalance(s, p + 2)
                if (end == FAIL) Step.Done(FAIL) else Step.Advance(end, p + 4)
            }

            LOWER_F -> {
                frontierStep(s, p)
            }

            in DIGIT_0..DIGIT_9 -> {
                val end = matchCapture(s, pat[p + 1].toInt() - DIGIT_0)
                if (end == FAIL) Step.Done(FAIL) else Step.Advance(end, p + 2)
            }

            else -> {
                null
            }
        }

    private fun frontierStep(
        s: Int,
        p: Int,
    ): Step {
        val classStart = p + 2
        val classEnd = classEnd(classStart)
        val previous = if (s == 0) 0 else src[s - 1].toInt() and 0xFF
        val current = if (s < srcEnd) src[s].toInt() and 0xFF else 0
        val matches = !matchSingle(previous, classStart, classEnd) && matchSingle(current, classStart, classEnd)
        return if (matches) Step.Advance(s, classEnd) else Step.Done(FAIL)
    }

    private fun itemStep(
        s: Int,
        p: Int,
    ): Step {
        val ep = classEnd(p)
        val matches = s < srcEnd && matchSingle(src[s].toInt() and 0xFF, p, ep)
        val quantifier = if (ep < patEnd) pat[ep].toInt() else 0
        return when (quantifier) {
            QUESTION -> optionalStep(s, ep, matches)
            PLUS -> Step.Done(if (matches) maxExpand(s + 1, p, ep) else FAIL)
            STAR -> Step.Done(maxExpand(s, p, ep))
            MINUS -> Step.Done(minExpand(s, p, ep))
            else -> if (matches) Step.Advance(s + 1, ep) else Step.Done(FAIL)
        }
    }

    private fun optionalStep(
        s: Int,
        ep: Int,
        matches: Boolean,
    ): Step {
        if (matches) {
            val result = match(s + 1, ep + 1)
            if (result != FAIL) return Step.Done(result)
        }
        return Step.Advance(s, ep + 1)
    }

    private fun startCapture(
        s: Int,
        p: Int,
    ): Int {
        val position = pat[p + 1].toInt() == CLOSE_PAREN
        val nextPattern = if (position) p + 2 else p + 1
        capStart[level] = s
        capLen[level] = if (position) CAP_POSITION else CAP_UNFINISHED
        level++
        val result = match(s, nextPattern)
        if (result == FAIL) level--
        return result
    }

    private fun endCapture(
        s: Int,
        p: Int,
    ): Int {
        val captureIndex = lastUnfinished()
        capLen[captureIndex] = s - capStart[captureIndex]
        val result = match(s, p)
        if (result == FAIL) capLen[captureIndex] = CAP_UNFINISHED
        return result
    }

    private fun lastUnfinished(): Int {
        for (i in level - 1 downTo 0) {
            if (capLen[i] == CAP_UNFINISHED) return i
        }
        throw LuaError.of("invalid pattern capture")
    }

    private fun matchCapture(
        s: Int,
        index: Int,
    ): Int {
        val captureIndex = index - 1
        if (captureIndex < 0 || captureIndex >= level || capLen[captureIndex] == CAP_UNFINISHED) {
            throw LuaError.of("invalid capture index %$index")
        }
        val length = capLen[captureIndex]
        if (srcEnd - s < length) return FAIL
        for (i in 0 until length) {
            if (src[capStart[captureIndex] + i] != src[s + i]) return FAIL
        }
        return s + length
    }

    private fun matchBalance(
        s: Int,
        p: Int,
    ): Int {
        if (s >= srcEnd || src[s].toInt() != pat[p].toInt()) return FAIL
        val open = pat[p].toInt()
        val close = pat[p + 1].toInt()
        var depth = 1
        var i = s + 1
        while (i < srcEnd) {
            when (src[i].toInt()) {
                close -> {
                    depth--
                    if (depth == 0) return i + 1
                }

                open -> {
                    depth++
                }
            }
            i++
        }
        return FAIL
    }

    private fun maxExpand(
        s: Int,
        p: Int,
        ep: Int,
    ): Int {
        var count = 0
        while (s + count < srcEnd && matchSingle(src[s + count].toInt() and 0xFF, p, ep)) count++
        while (count >= 0) {
            val result = match(s + count, ep + 1)
            if (result != FAIL) return result
            count--
        }
        return FAIL
    }

    private fun minExpand(
        sIn: Int,
        p: Int,
        ep: Int,
    ): Int {
        var s = sIn
        while (true) {
            val result = match(s, ep + 1)
            if (result != FAIL) return result
            if (s < srcEnd && matchSingle(src[s].toInt() and 0xFF, p, ep)) s++ else return FAIL
        }
    }

    private fun classEnd(pIn: Int): Int {
        var p = pIn
        val c = pat[p].toInt()
        p++
        if (c == PERCENT) return p + 1
        if (c == OPEN_BRACKET) {
            if (p < patEnd && pat[p].toInt() == CARET) p++
            do {
                if (p >= patEnd) throw LuaError.of("malformed pattern (missing ']')")
                val current = pat[p].toInt()
                p++
                if (current == PERCENT && p < patEnd) p++
            } while (p >= patEnd || pat[p].toInt() != CLOSE_BRACKET)
            return p + 1
        }
        return p
    }

    private fun matchSingle(
        c: Int,
        p: Int,
        ep: Int,
    ): Boolean =
        when (pat[p].toInt()) {
            DOT -> true
            PERCENT -> matchClass(c, pat[p + 1].toInt())
            OPEN_BRACKET -> matchBracketClass(c, p, ep - 1)
            else -> (pat[p].toInt() and 0xFF) == c
        }

    private fun matchBracketClass(
        c: Int,
        pIn: Int,
        ec: Int,
    ): Boolean {
        var p = pIn + 1
        val negate = pat[p].toInt() == CARET
        if (negate) p++
        var found = false
        while (p < ec) {
            val item = bracketItem(c, p, ec)
            if (item.matched) found = true
            p = item.next
        }
        return found != negate
    }

    private fun bracketItem(
        c: Int,
        p: Int,
        ec: Int,
    ): BracketItem {
        if (pat[p].toInt() == PERCENT) return BracketItem(matchClass(c, pat[p + 1].toInt()), p + 2)
        if (p + 2 < ec && pat[p + 1].toInt() == MINUS) {
            val inRange = (pat[p].toInt() and 0xFF) <= c && c <= (pat[p + 2].toInt() and 0xFF)
            return BracketItem(inRange, p + 3)
        }
        return BracketItem((pat[p].toInt() and 0xFF) == c, p + 1)
    }

    private class BracketItem(
        val matched: Boolean,
        val next: Int,
    )

    private sealed interface Step {
        class Done(
            val value: Int,
        ) : Step

        class Advance(
            val s: Int,
            val p: Int,
        ) : Step
    }

    companion object {
        const val FAIL = -1
        private const val CAP_UNFINISHED = -1
        private const val CAP_POSITION = -2
        private const val MAX_CAPTURES = 32

        private const val CARET = '^'.code
        private const val DOLLAR = '$'.code
        private const val PERCENT = '%'.code
        private const val DOT = '.'.code
        private const val OPEN_PAREN = '('.code
        private const val CLOSE_PAREN = ')'.code
        private const val OPEN_BRACKET = '['.code
        private const val CLOSE_BRACKET = ']'.code
        private const val MINUS = '-'.code
        private const val PLUS = '+'.code
        private const val STAR = '*'.code
        private const val QUESTION = '?'.code
        private const val LOWER_B = 'b'.code
        private const val LOWER_F = 'f'.code
        private const val DIGIT_0 = '0'.code
        private const val DIGIT_9 = '9'.code

        fun matchClass(
            c: Int,
            classByte: Int,
        ): Boolean {
            val lower = classByte or 0x20
            val result =
                when (lower.toChar()) {
                    'a' -> isAlpha(c)
                    'd' -> isDigit(c)
                    'l' -> isLower(c)
                    'u' -> isUpper(c)
                    's' -> isSpace(c)
                    'w' -> isAlpha(c) || isDigit(c)
                    'c' -> isControl(c)
                    'p' -> isPunct(c)
                    'x' -> isHex(c)
                    'g' -> c in 0x21..0x7E
                    else -> return classByte == c
                }
            return if (classByte in 'A'.code..'Z'.code) !result else result
        }

        private fun isDigit(c: Int) = c in '0'.code..'9'.code

        private fun isLower(c: Int) = c in 'a'.code..'z'.code

        private fun isUpper(c: Int) = c in 'A'.code..'Z'.code

        private fun isAlpha(c: Int) = isLower(c) || isUpper(c)

        private fun isSpace(c: Int) = c == 0x20 || c in 0x09..0x0D

        private fun isControl(c: Int) = c < 0x20 || c == 0x7F

        private fun isHex(c: Int) = isDigit(c) || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

        private fun isPunct(c: Int) = c in 0x21..0x7E && !isAlpha(c) && !isDigit(c)
    }
}
