package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaFunction
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaUserdata
import de.fiereu.lua.LuaValue
import de.fiereu.lua.SecurityAction
import de.fiereu.lua.arg
import de.fiereu.lua.common.LuaBytes
import de.fiereu.lua.common.LuaNumbers
import de.fiereu.lua.common.LuaNumeral
import de.fiereu.lua.runtime.LuaRuntimeImpl
import de.fiereu.lua.set
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** A file handle backing the io library, over either a random-access file or a stream. */
internal class FileHandle(
    val name: String,
    private val file: RandomAccessFile?,
    private val input: InputStream?,
    private val output: OutputStream?,
) {
    var closed = false
        private set

    private var pushed = NO_PUSHBACK

    fun write(bytes: ByteArray) {
        if (file != null) file.write(bytes) else output?.write(bytes)
    }

    fun flush() {
        output?.flush()
    }

    private fun readByte(): Int {
        if (pushed != NO_PUSHBACK) {
            val value = pushed
            pushed = NO_PUSHBACK
            return value
        }
        return file?.read() ?: input?.read() ?: -1
    }

    private fun unread(value: Int) {
        pushed = value
    }

    fun readLine(keepEol: Boolean): LuaValue {
        val out = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = readByte()
            if (b < 0) break
            sawAny = true
            if (b == '\n'.code) {
                if (keepEol) out.write(b)
                return LuaString(LuaBytes.wrap(out.toByteArray()))
            }
            out.write(b)
        }
        return if (sawAny) LuaString(LuaBytes.wrap(out.toByteArray())) else LuaNil
    }

    fun readAll(): LuaValue {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = readByte()
            if (b < 0) break
            out.write(b)
        }
        return LuaString(LuaBytes.wrap(out.toByteArray()))
    }

    fun readCount(count: Int): LuaValue {
        if (count == 0) {
            val probe = readByte()
            if (probe < 0) return LuaNil
            unread(probe)
            return LuaString.of("")
        }
        val out = ByteArrayOutputStream()
        var remaining = count
        while (remaining > 0) {
            val b = readByte()
            if (b < 0) break
            out.write(b)
            remaining--
        }
        return if (out.size() == 0) LuaNil else LuaString(LuaBytes.wrap(out.toByteArray()))
    }

    fun readNumber(): LuaValue {
        val token = StringBuilder()
        var b = readByte()
        while (b >= 0 && b.toChar().isWhitespace()) b = readByte()
        while (b >= 0 && b.toChar() in NUMERAL_CHARS) {
            token.append(b.toChar())
            b = readByte()
        }
        if (b >= 0) unread(b)
        return when (val parsed = LuaNumbers.parse(token.toString())) {
            is LuaNumeral.Int -> LuaInteger(parsed.value)
            is LuaNumeral.Float -> LuaFloat(parsed.value)
            null -> LuaNil
        }
    }

    fun seek(
        whence: String,
        offset: Long,
    ): Long {
        val raf = file ?: throw LuaError.of("cannot seek this file")
        val base =
            when (whence) {
                "set" -> 0
                "cur" -> raf.filePointer
                "end" -> raf.length()
                else -> throw LuaError.of("invalid option '$whence'")
            }
        raf.seek(base + offset)
        return raf.filePointer
    }

    fun close() {
        if (closed) return
        closed = true
        file?.close()
        if (output !== System.out && output !== System.err) output?.close()
        if (input !== System.`in`) input?.close()
    }

    companion object {
        private const val NO_PUSHBACK = -2
        private val NUMERAL_CHARS = "0123456789.+-xXeEpPaAbBcCdDfF"
    }
}

/** The input/output library (manual §6.9). */
internal object IoLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) {
        val methods = runtime.newTable()
        val metatable = runtime.newTable()
        metatable["__index"] = methods
        metatable["__name"] = LuaString.of("FILE*")
        runtime.populate(methods) {
            function("write") { write(it.arg(0), handle(it.arg(0)), it, 1) }
            function("read") { read(handle(it.arg(0)), it, 1) }
            result("lines") { lineIterator(handle(it.arg(0)), it, 1, closeAtEnd = false) }
            function("close") { args -> handle(args.arg(0)).close().let { listOf(LuaBoolean.TRUE) } }
            function("flush") { args -> handle(args.arg(0)).flush().let { listOf(args.arg(0)) } }
            function("seek") { seek(it) }
            function("setvbuf") { listOf(it.arg(0)) }
        }

        val sink = (runtime as LuaRuntimeImpl).config.standardOutput
        val sinkStream =
            object : OutputStream() {
                override fun write(b: Int) = sink(LuaBytes.ofByte(b))

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = sink(LuaBytes.of(b.copyOfRange(off, off + len)))
            }
        val stdin = makeFile(runtime, metatable, FileHandle("<stdin>", null, System.`in`, null))
        val stdout = makeFile(runtime, metatable, FileHandle("<stdout>", null, null, sinkStream))
        val stderr = makeFile(runtime, metatable, FileHandle("<stderr>", null, null, System.err))
        val defaults = Defaults(stdin, stdout)

        runtime.module(target, "io") {
            constant("stdin", stdin)
            constant("stdout", stdout)
            constant("stderr", stderr)
            function("open") { open(runtime, metatable, it) }
            function("close") { args ->
                handle(if (args.arg(0) == LuaNil) defaults.output else args.arg(0)).close().let { listOf(LuaBoolean.TRUE) }
            }
            function("read") { read(handle(defaults.input), it, 0) }
            function("write") { write(defaults.output, handle(defaults.output), it, 0) }
            function("lines") { lines(runtime, metatable, defaults, it) }
            result("type") { type(it.arg(0)) }
            function("flush") { handle(defaults.output).flush().let { listOf(defaults.output) } }
            function("input") { selectDefault(runtime, metatable, defaults, it, input = true) }
            function("output") { selectDefault(runtime, metatable, defaults, it, input = false) }
            function("tmpfile") { tmpFile(runtime, metatable) }
            function("popen") { listOf(LuaNil, LuaString.of("'popen' not supported")) }
        }
    }

    private class Defaults(
        var input: LuaValue,
        var output: LuaValue,
    )

    private fun makeFile(
        runtime: LuaRuntime,
        metatable: LuaTable,
        file: FileHandle,
    ): LuaUserdata = runtime.newUserdata(file, metatable)

    private fun handle(value: LuaValue): FileHandle =
        (value as? LuaUserdata)?.instance as? FileHandle ?: throw LuaError.of("bad file handle")

    private fun open(
        runtime: LuaRuntime,
        metatable: LuaTable,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val name = args.checkBytes(0, "open").utf8()
        val mode = (args.arg(1) as? LuaString)?.bytes?.utf8()?.replace("b", "") ?: "r"
        runtime.guard(if (mode == "r") SecurityAction.READ_FILE else SecurityAction.WRITE_FILE, name)
        return try {
            listOf(makeFile(runtime, metatable, openFile(name, mode)))
        } catch (error: java.io.IOException) {
            listOf(LuaNil, LuaString.of("$name: ${error.message}"), LuaInteger(2))
        }
    }

    private fun openFile(
        name: String,
        mode: String,
    ): FileHandle {
        val raf =
            when (mode) {
                "r" -> RandomAccessFile(name, "r")
                "w", "w+" -> RandomAccessFile(name, "rw").also { it.setLength(0) }
                "r+" -> RandomAccessFile(name, "rw")
                "a", "a+" -> RandomAccessFile(name, "rw").also { it.seek(it.length()) }
                else -> throw LuaError.of("invalid mode '$mode'")
            }
        return FileHandle(name, raf, null, null)
    }

    private fun write(
        self: LuaValue,
        file: FileHandle,
        args: List<LuaValue>,
        from: Int,
    ): List<LuaValue> {
        for (i in from until args.size) {
            file.write(args.checkBytes(i, "write").toByteArray())
        }
        return listOf(self)
    }

    private fun read(
        file: FileHandle,
        args: List<LuaValue>,
        from: Int,
    ): List<LuaValue> {
        if (args.size <= from) return listOf(file.readLine(false))
        val result = ArrayList<LuaValue>()
        for (i in from until args.size) result.add(readOne(file, args[i]))
        return result
    }

    private fun readOne(
        file: FileHandle,
        spec: LuaValue,
    ): LuaValue {
        if (spec is LuaInteger) return file.readCount(spec.value.toInt())
        val format = (spec as? LuaString)?.bytes?.utf8()?.removePrefix("*") ?: "l"
        return when (format) {
            "l" -> file.readLine(false)
            "L" -> file.readLine(true)
            "a" -> file.readAll()
            "n" -> file.readNumber()
            else -> throw LuaError.of("invalid format '$format'")
        }
    }

    private fun seek(args: List<LuaValue>): List<LuaValue> {
        val file = handle(args.arg(0))
        val whence = (args.arg(1) as? LuaString)?.bytes?.utf8() ?: "cur"
        val offset = args.optInteger(2, 0)
        return listOf(LuaInteger(file.seek(whence, offset)))
    }

    private fun lines(
        runtime: LuaRuntime,
        metatable: LuaTable,
        defaults: Defaults,
        args: List<LuaValue>,
    ): List<LuaValue> {
        if (args.arg(0) == LuaNil) return listOf(lineIterator(handle(defaults.input), args, 1, closeAtEnd = false))
        val name = args.checkBytes(0, "lines").utf8()
        runtime.guard(SecurityAction.READ_FILE, name)
        val file = openFile(name, "r")
        makeFile(runtime, metatable, file)
        return listOf(lineIterator(file, args, 1, closeAtEnd = true))
    }

    private fun lineIterator(
        file: FileHandle,
        args: List<LuaValue>,
        from: Int,
        closeAtEnd: Boolean,
    ): LuaFunction {
        val formats = if (args.size > from) args.subList(from, args.size) else listOf(LuaString.of("l"))
        return LuaFunction {
            val values = formats.map { readOne(file, it) }
            if (values.first() == LuaNil) {
                if (closeAtEnd) file.close()
                listOf(LuaNil)
            } else {
                values
            }
        }
    }

    private fun type(value: LuaValue): LuaValue {
        val file = (value as? LuaUserdata)?.instance as? FileHandle ?: return LuaNil
        return LuaString.of(if (file.closed) "closed file" else "file")
    }

    private fun selectDefault(
        runtime: LuaRuntime,
        metatable: LuaTable,
        defaults: Defaults,
        args: List<LuaValue>,
        input: Boolean,
    ): List<LuaValue> {
        val current = if (input) defaults.input else defaults.output
        when (val value = args.arg(0)) {
            LuaNil -> {
                return listOf(current)
            }

            is LuaString -> {
                runtime.guard(if (input) SecurityAction.READ_FILE else SecurityAction.WRITE_FILE, value.bytes.utf8())
                val file = makeFile(runtime, metatable, openFile(value.bytes.utf8(), if (input) "r" else "w"))
                if (input) defaults.input = file else defaults.output = file
            }

            else -> {
                if (input) defaults.input = value else defaults.output = value
            }
        }
        return listOf(if (input) defaults.input else defaults.output)
    }

    private fun tmpFile(
        runtime: LuaRuntime,
        metatable: LuaTable,
    ): List<LuaValue> {
        runtime.guard(SecurityAction.TEMP_FILE, "(tmpfile)")
        val temp = File.createTempFile("lua", null).also { it.deleteOnExit() }
        return listOf(makeFile(runtime, metatable, openFile(temp.absolutePath, "w+")))
    }
}
