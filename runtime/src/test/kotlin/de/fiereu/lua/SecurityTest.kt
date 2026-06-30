package de.fiereu.lua

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class SecurityTest :
    StringSpec({
        fun runtime(policy: SecurityPolicy?): LuaRuntime =
            LuaRuntime.create(LuaRuntimeConfig(libraries = StdLibs.ALL, securityPolicy = policy))

        val jailDir = Files.createTempDirectory("luajail").toFile().also { it.deleteOnExit() }
        val jail = jailDir.absolutePath

        "directoryJail allows writes inside the jail" {
            val result =
                runtime(SecurityPolicies.directoryJail(jail)).execute(
                    """
                    local f = io.open([[$jail/inside.txt]], 'w')
                    f:write('hi')
                    f:close()
                    return 'ok'
                    """.trimIndent(),
                )
            result.first() shouldBe LuaString.of("ok")
        }

        "directoryJail blocks writes outside the jail" {
            val error =
                shouldThrow<LuaError> {
                    runtime(SecurityPolicies.directoryJail(jail)).execute("io.open('/etc/luakt-nope.txt', 'w')")
                }
            error.message!! shouldContain "operation not permitted"
        }

        "directoryJail blocks path-traversal escapes" {
            shouldThrow<LuaError> {
                runtime(SecurityPolicies.directoryJail(jail)).execute("io.open([[$jail/../escape.txt]], 'w')")
            }
        }

        "directoryJail blocks os.remove outside the jail" {
            shouldThrow<LuaError> {
                runtime(SecurityPolicies.directoryJail(jail)).execute("os.remove('/etc/hosts')")
            }
        }

        "readOnly allows reads but blocks writes" {
            val readable = jailDir.resolve("readable.txt").also { it.writeText("data") }
            val open = runtime(SecurityPolicies.readOnly()).execute("return io.open([[${readable.absolutePath}]], 'r') ~= nil")
            open.first() shouldBe LuaBoolean.TRUE
            shouldThrow<LuaError> {
                runtime(SecurityPolicies.readOnly()).execute("io.open([[$jail/x.txt]], 'w')")
            }
        }

        "denyProcess blocks os.execute and os.exit without terminating the host" {
            shouldThrow<LuaError> {
                runtime(SecurityPolicies.denyProcess()).execute("os.execute('echo hi')")
            }
            val error = shouldThrow<LuaError> { runtime(SecurityPolicies.denyProcess()).execute("os.exit(0)") }
            error.message!! shouldContain "operation not permitted"
        }

        "a custom policy can deny environment reads" {
            val noEnv = SecurityPolicy { it.action != SecurityAction.READ_ENV }
            shouldThrow<LuaError> { runtime(noEnv).execute("return os.getenv('PATH')") }
        }

        "all combines policies and denies if any denies" {
            val policy = SecurityPolicies.all(SecurityPolicies.directoryJail(jail), SecurityPolicies.denyProcess())
            shouldThrow<LuaError> { runtime(policy).execute("os.exit(0)") }
            shouldThrow<LuaError> { runtime(policy).execute("io.open('/tmp/elsewhere.txt', 'w')") }
        }

        "with no policy the standard library is unrestricted" {
            val path = jailDir.resolve("free.txt").absolutePath
            val result =
                runtime(null).execute(
                    """
                    local f = io.open([[$path]], 'w'); f:write('x'); f:close()
                    local g = io.open([[$path]], 'r'); local c = g:read('a'); g:close()
                    return c
                    """.trimIndent(),
                )
            result.first() shouldBe LuaString.of("x")
        }
    })
