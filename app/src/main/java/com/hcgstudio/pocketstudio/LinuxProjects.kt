package com.hcgstudio.pocketstudio

import android.content.Context
import android.net.Uri
import com.hcgstudio.pocketstudio.plugin.LinuxEnvironment
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
import dalvik.system.PathClassLoader
import org.json.JSONArray
import java.io.FilterInputStream
import java.io.InputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

internal object LinuxProjects {
    const val SCHEME = "studio-linux"
    val packageNames = setOf("com.hcgstudio.pocketstudio.avfplugin", "com.hcgstudio.pocketstudio.prootplugin")
    val projectName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    fun registrations(context: Context) = discoverPlugins(context).filter {
        it.trusted && it.category == PluginDiscovery.DEVELOPMENT_ENVIRONMENT_CATEGORY &&
            it.service.metaData.getInt(LinuxEnvironment.API) == LinuxEnvironment.VERSION &&
            !it.service.metaData.getString(LinuxEnvironment.ENTRY).isNullOrBlank()
    }
    fun uri(pkg: String, name: String): Uri = Uri.Builder().scheme(SCHEME).authority(pkg).appendPath(name).build()

    fun provider(context: Context, plugin: DiscoveredPlugin): Pair<LinuxEnvironment, Context> {
        val installed = context.createPackageContext(plugin.packageName, 0)
        check(installed.applicationInfo.sourceDir == plugin.sourceDir)
        val loader = PathClassLoader(plugin.sourceDir, installed.applicationInfo.nativeLibraryDir,
            LinuxEnvironment::class.java.classLoader)
        val type = loader.loadClass(plugin.service.metaData.getString(LinuxEnvironment.ENTRY))
            .asSubclass(LinuxEnvironment::class.java)
        return type.getConstructor(Context::class.java, Context::class.java)
            .newInstance(context.applicationContext, installed) to installed
    }

    @JvmStatic fun open(context: Context, location: Uri): LinuxProject {
        val name = location.pathSegments.singleOrNull()?.takeIf(projectName::matches)
            ?: throw IOException(context.getString(R.string.linux_invalid_name))
        val pkg = location.authority.orEmpty()
        val registration = registrations(context).singleOrNull { it.packageName == pkg }
            ?: throw IOException(context.getString(R.string.linux_plugin_missing))
        val (runtime, installed) = provider(context, registration)
        return LinuxProject(runtime, name, uri(pkg, name), context.assets.open("linux-files.py")
            .bufferedReader().use { it.readText() }, EditorLanguage.load(installed))
    }
}

internal class LinuxProject(val environment: LinuxEnvironment, val name: String, val location: Uri,
                           private val fileScript: String, val languages: List<EditorLanguage>) {
    fun node(path: String, directory: Boolean) = Project.Node(
        if (path.isEmpty()) location else location.buildUpon().apply { path.split('/').forEach { appendPath(it) } }.build(),
        path.substringAfterLast('/').ifEmpty { name }, path, directory)

    fun start(argv: Array<String>, terminal: Boolean = false): Process = environment.start(name, argv, terminal)

    fun list(path: String): List<Project.Node> {
        val items = JSONArray(file("list", path).toString(Charsets.UTF_8))
        return (0 until items.length()).map {
            val item = items.getJSONArray(it)
            val child = item.getString(0)
            node(if (path.isEmpty()) child else "$path/$child", item.getBoolean(1))
        }
    }

    fun stat(path: String): Project.Node = node(path, file("stat", path).toString(Charsets.UTF_8).trim() == "directory")

    private fun stderr(process: Process): FutureTask<String> {
        val tail = FutureTask {
            process.errorStream.reader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                val text = StringBuilder()
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    text.append(buf, 0, n)
                    if (text.length > 8192) text.delete(0, text.length - 8192)
                }
                text.toString()
            }
        }
        thread(isDaemon = true, name = "linux-files-stderr") { tail.run() }
        return tail
    }

    private fun checkExit(process: Process, error: FutureTask<String>, path: String) {
        if (!process.waitFor(60, TimeUnit.SECONDS)) throw IOException("Linux filesystem request timed out")
        val detail = error.get(5, TimeUnit.SECONDS)
        when (process.exitValue()) {
            0 -> return
            2 -> throw FileNotFoundException(path)
            else -> throw IOException(detail.ifBlank { "Linux filesystem request failed" })
        }
    }

    // Stream raw bytes so exports and other callers can read an entire file. Project.read()
    // applies the text editor's separate size limit and closes this stream on early rejection.
    fun input(path: String): InputStream {
        val process = start(arrayOf("python3", "-I", "-c", fileScript, "read", path))
        val error = stderr(process)
        try { process.outputStream.close() } catch (e: IOException) { process.destroy(); throw e }
        return object : FilterInputStream(process.inputStream) {
            private var verified = false
            private fun checked(count: Int): Int {
                if (count == -1 && !verified) {
                    try { checkExit(process, error, path) }
                    catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.InterruptedIOException()
                    } catch (e: TimeoutException) { throw IOException(e) }
                    catch (e: ExecutionException) { throw IOException(e.cause) }
                    verified = true
                }
                return count
            }
            override fun read(): Int = checked(super.read())
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                checked(super.read(buffer, offset, length))
            override fun close() {
                try { super.close() } finally { process.destroy() }
            }
        }
    }

    @JvmOverloads fun file(op: String, path: String, destination: String? = null, data: ByteArray? = null): ByteArray {
        val argv = listOfNotNull("python3", "-I", "-c", fileScript, op, path, destination)
        val process = start(argv.toTypedArray())
        val error = stderr(process)
        val result = FutureTask {
            process.outputStream.use { if (data != null) it.write(data) }
            process.inputStream.use { it.readBytes() }
        }
        thread(isDaemon = true, name = "linux-files-stdio") { result.run() }
        try {
            checkExit(process, error, path)
            return result.get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IOException(e.cause)
        } catch (e: TimeoutException) {
            throw IOException("Linux filesystem stream timed out", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException()
        } finally {
            process.destroy()
        }
    }
}
