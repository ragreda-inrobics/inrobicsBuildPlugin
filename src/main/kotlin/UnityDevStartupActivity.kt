import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit

class UnityDevStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val root = File(project.basePath ?: return)
        if (!File(root, "download_unity_build.ps1").isFile || !File(root, "unity-builds.json").isFile) return
        withContext(Dispatchers.IO) {
            for (flavor in listOf("Care", "Virtual")) {
                if (project.isDisposed) return@withContext
                try {
                    val remote = UnityDevVersions.readDev(root, flavor) ?: continue
                    val installed = UnityDevVersions.readInstalled(root, flavor)
                    if (!UnityDevVersions.isNewer(remote, installed, flavor)) continue
                    val id = remote.get("buildId").asString
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            NotificationGroupManager.getInstance().getNotificationGroup("Inrobics Unity")
                                .createNotification("Nueva build dev de Unity · $flavor", "Disponible: $id", NotificationType.INFORMATION)
                                .addAction(NotificationAction.createSimple("Descargar dev de $flavor") {
                                    UnityBuildDownloads.download(project, flavor, "Dev")
                                }).notify(project)
                        }
                    }
                } catch (e: java.util.concurrent.CancellationException) { throw e }
                catch (e: Exception) {
                    // A missing remote, rclone or offline connection must never cause a startup notification.
                    Logger.getInstance(UnityDevStartupActivity::class.java).debug("No se pudo comprobar dev/$flavor", e)
                }
            }
        }
    }
}

object UnityDevVersions {
    private val remoteCache = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    private fun key(root: File, flavor: String) = "${root.absolutePath}:$flavor"
    fun cachedDev(root: File, flavor: String): JsonObject? = remoteCache[key(root, flavor)]
    fun readDev(root: File, flavor: String): JsonObject? {
        val output = File.createTempFile("inrobics-dev-", ".json")
        var process: Process? = null
        try {
            process = ProcessBuilder("rclone", "cat", "androidBuild:Builds/dev/$flavor/latest.json",
                "--contimeout", "10s", "--timeout", "15s", "--retries", "1", "--low-level-retries", "1")
                .directory(root).redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val remote = Gson().fromJson(output.readText(Charsets.UTF_8), JsonObject::class.java) ?: return null
            if (remote.get("flavor")?.asString != flavor || remote.get("buildId")?.asString
                    ?.matches(Regex("[a-zA-Z0-9._-]+")) != true) return null
            remoteCache[key(root, flavor)] = remote
            return remote
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            output.delete()
        }
    }
    fun readInstalled(root: File, flavor: String): JsonObject? {
        val properties = Properties()
        File(root, "gradle.properties").takeIf { it.exists() }?.reader()?.use { properties.load(it) }
        val path = properties.getProperty("unityLibraryPath$flavor", "../UnityProject/androidBuild$flavor/unityLibrary")
        val library = File(path).let { if (it.isAbsolute) it else File(root, path) }
        return File(library, "unity-build.json").takeIf { it.isFile }?.let {
            Gson().fromJson(it.readText(), JsonObject::class.java)
        }
    }
    fun isNewer(remote: JsonObject, installed: JsonObject?, flavor: String): Boolean {
        val id = remote.get("buildId")?.asString ?: return false
        if (!id.matches(Regex("[a-zA-Z0-9._-]+")) || remote.get("flavor")?.asString != flavor) return false
        if (id == installed?.get("buildId")?.asString) return false
        val remoteDate = runCatching { Instant.parse(remote.get("createdUtc")?.asString) }.getOrNull()
        val localDate = runCatching { Instant.parse(installed?.get("createdUtc")?.asString) }.getOrNull()
        return if (remoteDate != null && localDate != null) remoteDate > localDate else true
    }
}
