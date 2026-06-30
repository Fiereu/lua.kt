package de.fiereu.lua.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LuaNumbersTest :
    StringSpec({
        "parses decimal integers" {
            LuaNumbers.parseNumeral("3") shouldBe LuaNumeral.Int(3)
            LuaNumbers.parseNumeral("345") shouldBe LuaNumeral.Int(345)
        }

        "parses hexadecimal integers" {
            LuaNumbers.parseNumeral("0xff") shouldBe LuaNumeral.Int(255)
            LuaNumbers.parseNumeral("0xBEBADA") shouldBe LuaNumeral.Int(0xBEBADA)
        }

        "wraps overflowing hexadecimal integers" {
            LuaNumbers.parseNumeral("0xffffffffffffffff") shouldBe LuaNumeral.Int(-1)
        }

        "promotes overflowing decimal integers to float" {
            val parsed = LuaNumbers.parseNumeral("99999999999999999999999999")
            parsed.shouldBeInstanceOf<LuaNumeral.Float>()
        }

        "parses decimal floats" {
            LuaNumbers.parseNumeral("3.0") shouldBe LuaNumeral.Float(3.0)
            LuaNumbers.parseNumeral("3.1416") shouldBe LuaNumeral.Float(3.1416)
            LuaNumbers.parseNumeral("314.16e-2") shouldBe LuaNumeral.Float(3.1416)
            LuaNumbers.parseNumeral("0.31416E1") shouldBe LuaNumeral.Float(3.1416)
            LuaNumbers.parseNumeral("34e1") shouldBe LuaNumeral.Float(340.0)
            LuaNumbers.parseNumeral("3.") shouldBe LuaNumeral.Float(3.0)
            LuaNumbers.parseNumeral(".5") shouldBe LuaNumeral.Float(0.5)
        }

        "parses hexadecimal floats" {
            LuaNumbers.parseNumeral("0x0.1E") shouldBe LuaNumeral.Float(0.1171875)
            LuaNumbers.parseNumeral("0xA23p-4") shouldBe LuaNumeral.Float(162.1875)
            LuaNumbers.parseNumeral("0x1.fp10") shouldBe LuaNumeral.Float(1984.0)
            LuaNumbers.parseNumeral("0X1.921FB54442D18P+1") shouldBe LuaNumeral.Float(Math.PI)
        }

        "rejects malformed numerals" {
            LuaNumbers.parseNumeral("") shouldBe null
            LuaNumbers.parseNumeral("3e") shouldBe null
            LuaNumbers.parseNumeral("0x") shouldBe null
            LuaNumbers.parseNumeral("0xg") shouldBe null
            LuaNumbers.parseNumeral("abc") shouldBe null
            LuaNumbers.parseNumeral("1.2.3") shouldBe null
        }

        "tonumber allows sign and surrounding spaces" {
            LuaNumbers.parse("  -10 ") shouldBe LuaNumeral.Int(-10)
            LuaNumbers.parse("0x10") shouldBe LuaNumeral.Int(16)
            LuaNumbers.parse("+2.5") shouldBe LuaNumeral.Float(2.5)
            LuaNumbers.parse("   ") shouldBe null
            LuaNumbers.parse("") shouldBe null
        }

        "formats integers" {
            LuaNumbers.integerToString(0) shouldBe "0"
            LuaNumbers.integerToString(-42) shouldBe "-42"
        }

        "formats floats like %.14g with the trailing .0 rule" {
            LuaNumbers.floatToString(3.0) shouldBe "3.0"
            LuaNumbers.floatToString(0.1) shouldBe "0.1"
            LuaNumbers.floatToString(100.0) shouldBe "100.0"
            LuaNumbers.floatToString(123.456) shouldBe "123.456"
            LuaNumbers.floatToString(1.0 / 3.0) shouldBe "0.33333333333333"
            LuaNumbers.floatToString(Math.PI) shouldBe "3.1415926535898"
            LuaNumbers.floatToString(1e15) shouldBe "1e+15"
            LuaNumbers.floatToString(1e100) shouldBe "1e+100"
            LuaNumbers.floatToString(1e-5) shouldBe "1e-05"
            LuaNumbers.floatToString(0.0001) shouldBe "0.0001"
            LuaNumbers.floatToString(-0.0) shouldBe "-0.0"
        }

        "formats non-finite floats" {
            LuaNumbers.floatToString(Double.POSITIVE_INFINITY) shouldBe "inf"
            LuaNumbers.floatToString(Double.NEGATIVE_INFINITY) shouldBe "-inf"
            LuaNumbers.floatToString(Double.NaN) shouldBe "nan"
        }
    })
