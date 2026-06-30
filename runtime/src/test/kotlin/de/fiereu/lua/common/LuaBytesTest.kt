package de.fiereu.lua.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LuaBytesTest :
    StringSpec({
        "equality is by content" {
            LuaBytes.of("hello") shouldBe LuaBytes.of("hello")
            (LuaBytes.of("hello") == LuaBytes.of("world")) shouldBe false
        }

        "hashCode matches for equal content" {
            LuaBytes.of("abc").hashCode() shouldBe LuaBytes.of("abc").hashCode()
        }

        "latin1 round-trips every byte" {
            val bytes = ByteArray(256) { it.toByte() }
            LuaBytes.of(bytes).latin1().length shouldBe 256
            LuaBytes.of(
                LuaBytes
                    .of(bytes)
                    .latin1()
                    .map { it.code.toByte() }
                    .toByteArray(),
            ) shouldBe
                LuaBytes.of(bytes)
        }

        "preserves embedded zeros" {
            val bytes = byteArrayOf(0x61, 0x00, 0x62)
            LuaBytes.of(bytes).size shouldBe 3
        }

        "concatenation joins content" {
            (LuaBytes.of("foo") + LuaBytes.of("bar")) shouldBe LuaBytes.of("foobar")
        }

        "of copies the source array" {
            val source = byteArrayOf(1, 2, 3)
            val bytes = LuaBytes.of(source)
            source[0] = 9
            bytes[0] shouldBe 1.toByte()
        }
    })
