package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaFloat
import de.fiereu.lua.LuaInteger
import de.fiereu.lua.LuaLibrary
import de.fiereu.lua.LuaNil
import de.fiereu.lua.LuaRuntime
import de.fiereu.lua.LuaString
import de.fiereu.lua.LuaTable
import de.fiereu.lua.LuaValue
import de.fiereu.lua.SecurityAction
import de.fiereu.lua.arg
import de.fiereu.lua.get
import de.fiereu.lua.isTruthy
import de.fiereu.lua.set
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.system.exitProcess

/** The operating-system library (manual §6.10). */
internal object OsLibrary : LuaLibrary {
    private val startNanos = System.nanoTime()

    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) = runtime.module(target, "os") {
        result("time") { LuaInteger(time(it)) }
        result("clock") { LuaFloat((System.nanoTime() - startNanos) / 1e9) }
        result("difftime") { LuaFloat(it.checkNumber(0, "difftime") - it.checkNumber(1, "difftime")) }
        result("date") { date(runtime, it) }
        result("getenv") { getEnv(runtime, it) }
        function("remove") { remove(runtime, it) }
        function("rename") { rename(runtime, it) }
        result("tmpname") {
            runtime.guard(SecurityAction.TEMP_FILE, "(tmpname)")
            LuaString.of(tempName())
        }
        result("setlocale") { LuaString.of("C") }
        function("execute") { execute(runtime, it) }
        function("exit") { exit(runtime, it) }
    }

    private fun time(args: List<LuaValue>): Long {
        val table = args.arg(0)
        if (table !is LuaTable) return System.currentTimeMillis() / 1000
        val calendar = GregorianCalendar()
        calendar.set(
            field(table, "year", calendar.get(Calendar.YEAR)),
            field(table, "month", 1) - 1,
            field(table, "day", 1),
            field(table, "hour", 12),
            field(table, "min", 0),
            field(table, "sec", 0),
        )
        return calendar.timeInMillis / 1000
    }

    private fun field(
        table: LuaTable,
        name: String,
        default: Int,
    ): Int = integerOf(table[name])?.toInt() ?: default

    private fun date(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): LuaValue {
        var format = (args.arg(0) as? LuaString)?.bytes?.utf8() ?: "%c"
        val seconds = if (args.arg(1) == LuaNil) System.currentTimeMillis() / 1000 else args.checkInteger(1, "date")
        val utc = format.startsWith("!")
        if (utc) format = format.substring(1)
        val calendar = GregorianCalendar(if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
        calendar.timeInMillis = seconds * 1000
        return if (format == "*t") dateTable(runtime, calendar) else LuaString.of(strftime(format, calendar))
    }

    private fun dateTable(
        runtime: LuaRuntime,
        calendar: Calendar,
    ): LuaTable {
        val table = runtime.newTable()
        table["year"] = LuaInteger(calendar.get(Calendar.YEAR).toLong())
        table["month"] = LuaInteger((calendar.get(Calendar.MONTH) + 1).toLong())
        table["day"] = LuaInteger(calendar.get(Calendar.DAY_OF_MONTH).toLong())
        table["hour"] = LuaInteger(calendar.get(Calendar.HOUR_OF_DAY).toLong())
        table["min"] = LuaInteger(calendar.get(Calendar.MINUTE).toLong())
        table["sec"] = LuaInteger(calendar.get(Calendar.SECOND).toLong())
        table["wday"] = LuaInteger(calendar.get(Calendar.DAY_OF_WEEK).toLong())
        table["yday"] = LuaInteger(calendar.get(Calendar.DAY_OF_YEAR).toLong())
        table["isdst"] = LuaBoolean.of(calendar.get(Calendar.DST_OFFSET) != 0)
        return table
    }

    private fun strftime(
        format: String,
        calendar: Calendar,
    ): String {
        val out = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c != '%' || i + 1 >= format.length) {
                out.append(c)
                i++
                continue
            }
            out.append(specifier(format[i + 1], calendar))
            i += 2
        }
        return out.toString()
    }

    private fun specifier(
        code: Char,
        calendar: Calendar,
    ): String =
        when (code) {
            'Y' -> calendar.get(Calendar.YEAR).toString()
            'y' -> "%02d".format(calendar.get(Calendar.YEAR) % 100)
            'm' -> "%02d".format(calendar.get(Calendar.MONTH) + 1)
            'd' -> "%02d".format(calendar.get(Calendar.DAY_OF_MONTH))
            'H' -> "%02d".format(calendar.get(Calendar.HOUR_OF_DAY))
            'M' -> "%02d".format(calendar.get(Calendar.MINUTE))
            'S' -> "%02d".format(calendar.get(Calendar.SECOND))
            'p' -> if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            'A' -> WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1]
            'a' -> WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1].take(3)
            'B' -> MONTHS[calendar.get(Calendar.MONTH)]
            'b', 'h' -> MONTHS[calendar.get(Calendar.MONTH)].take(3)
            'j' -> "%03d".format(calendar.get(Calendar.DAY_OF_YEAR))
            'w' -> (calendar.get(Calendar.DAY_OF_WEEK) - 1).toString()
            'x' -> strftime("%m/%d/%y", calendar)
            'X' -> strftime("%H:%M:%S", calendar)
            'c' -> strftime("%a %b %d %H:%M:%S %Y", calendar)
            '%' -> "%"
            else -> "%$code"
        }

    private fun getEnv(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): LuaValue {
        val name = args.checkBytes(0, "getenv").utf8()
        runtime.guard(SecurityAction.READ_ENV, name)
        return System.getenv(name)?.let { LuaString.of(it) } ?: LuaNil
    }

    private fun remove(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val name = args.checkBytes(0, "remove").utf8()
        runtime.guard(SecurityAction.DELETE_FILE, name)
        return if (File(name).delete()) listOf(LuaBoolean.TRUE) else listOf(LuaNil, LuaString.of("$name: cannot remove"))
    }

    private fun rename(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val from = args.checkBytes(0, "rename").utf8()
        val to = args.checkBytes(1, "rename").utf8()
        runtime.guard(SecurityAction.RENAME_FILE, from, to)
        return if (File(from).renameTo(File(to))) listOf(LuaBoolean.TRUE) else listOf(LuaNil, LuaString.of("cannot rename '$from'"))
    }

    private fun tempName(): String = File.createTempFile("lua", null).also { it.deleteOnExit() }.absolutePath

    private fun execute(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val command = (args.arg(0) as? LuaString)?.bytes?.utf8() ?: ""
        runtime.guard(SecurityAction.EXECUTE, command)
        if (args.arg(0) == LuaNil) return listOf(LuaBoolean.TRUE)
        return listOf(LuaNil, LuaString.of("exit"), LuaInteger(-1))
    }

    private fun exit(
        runtime: LuaRuntime,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val code =
            when (val value = args.arg(0)) {
                LuaNil, LuaBoolean.TRUE -> 0
                LuaBoolean.FALSE -> 1
                else -> integerOf(value)?.toInt() ?: 0
            }
        runtime.guard(SecurityAction.EXIT, code.toString())
        exitProcess(code)
    }

    private val WEEKDAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS =
        arrayOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )
}
