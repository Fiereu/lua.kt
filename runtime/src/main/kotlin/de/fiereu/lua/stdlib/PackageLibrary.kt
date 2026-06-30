package de.fiereu.lua.stdlib

import de.fiereu.lua.LuaBoolean
import de.fiereu.lua.LuaError
import de.fiereu.lua.LuaFunction
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
import de.fiereu.lua.runtime.LuaRuntimeImpl
import de.fiereu.lua.set
import java.io.File

/** The package/module library (manual §6.4): `require` and the `package` table. */
internal object PackageLibrary : LuaLibrary {
    override fun install(
        runtime: LuaRuntime,
        target: LuaTable,
    ) {
        val impl = runtime as LuaRuntimeImpl
        val pkg = runtime.newTable()
        val loaded = runtime.newTable()
        val preload = runtime.newTable()
        val searchers = runtime.newTable()

        target["package"] = pkg
        loaded["_G"] = target
        pkg["loaded"] = loaded
        pkg["preload"] = preload
        pkg["searchers"] = searchers

        runtime.populate(pkg) {
            constant("path", LuaString.of("./?.lua;./?/init.lua"))
            constant("cpath", LuaString.of(""))
            constant("config", LuaString.of("/\n;\n?\n!\n-\n"))
            function("loadlib") { listOf(LuaNil, LuaString.of("dynamic libraries not supported"), LuaString.of("absent")) }
            function("searchpath") { searchPath(it.checkBytes(0, "searchpath").utf8(), pathString(pkg)) }
        }

        searchers[1L] = LuaFunction { args -> preloadSearcher(preload, args) }
        searchers[2L] = LuaFunction { args -> builtinSearcher(target, args) }
        searchers[3L] = LuaFunction { args -> fileSearcher(impl, pkg, args) }

        runtime.populate(target) {
            function("require") { require(impl, loaded, searchers, it) }
        }
    }

    private fun require(
        impl: LuaRuntimeImpl,
        loaded: LuaTable,
        searchers: LuaTable,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val name = args.checkBytes(0, "require").utf8()
        impl.guard(SecurityAction.LOAD_MODULE, name)
        val key = LuaString.of(name)
        val cached = loaded[key]
        if (cached != LuaNil) return listOf(cached)
        val errors = StringBuilder()
        var index = 1L
        while (true) {
            val searcher = searchers[LuaInteger(index)]
            if (searcher == LuaNil) break
            val found = impl.interpreter.call(searcher, listOf(key))
            val loader = found.getOrElse(0) { LuaNil }
            if (loader is LuaFunction) {
                val data = found.getOrElse(1) { LuaNil }
                val produced = impl.call(loader, listOf(key, data)).firstOrNull() ?: LuaNil
                val module = if (produced == LuaNil) LuaBoolean.TRUE else produced
                loaded[key] = module
                return listOf(module)
            }
            if (loader is LuaString) errors.append(loader.bytes.utf8())
            index++
        }
        throw LuaError.of("module '$name' not found:$errors")
    }

    private fun preloadSearcher(
        preload: LuaTable,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val loader = preload[args.arg(0)]
        return if (loader is LuaFunction) listOf(loader) else listOf(LuaString.of("\n\tno field package.preload['${name(args)}']"))
    }

    private fun builtinSearcher(
        globals: LuaTable,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val module = globals[args.arg(0)]
        if (module is LuaTable) return listOf(LuaFunction { listOf(module) })
        return listOf(LuaString.of("\n\tno builtin module '${name(args)}'"))
    }

    private fun fileSearcher(
        impl: LuaRuntimeImpl,
        pkg: LuaTable,
        args: List<LuaValue>,
    ): List<LuaValue> {
        val found = searchPath(name(args), pathString(pkg))
        val path = found.firstOrNull() as? LuaString ?: return listOf(found.getOrElse(1) { LuaNil })
        val filename = path.bytes.utf8()
        val loader =
            LuaFunction {
                impl.guard(SecurityAction.READ_FILE, filename)
                impl.call(impl.load(File(filename).readBytes(), "@$filename"), listOf(LuaString.of(name(args)), path))
            }
        return listOf(loader, path)
    }

    private fun searchPath(
        name: String,
        path: String,
    ): List<LuaValue> {
        val relative = name.replace('.', '/')
        val errors = StringBuilder()
        for (template in path.split(';')) {
            if (template.isEmpty()) continue
            val candidate = template.replace("?", relative)
            if (File(candidate).isFile) return listOf(LuaString.of(candidate))
            errors.append("\n\tno file '").append(candidate).append("'")
        }
        return listOf(LuaNil, LuaString.of(errors.toString()))
    }

    private fun name(args: List<LuaValue>): String = (args.arg(0) as? LuaString)?.bytes?.utf8() ?: ""

    private fun pathString(pkg: LuaTable): String = (pkg["path"] as? LuaString)?.bytes?.utf8() ?: "./?.lua"
}
