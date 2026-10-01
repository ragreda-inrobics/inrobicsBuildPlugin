import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingWorker

object UnityBuildDownloads {
    private val running = ConcurrentHashMap.newKeySet<String>()
    fun download(project: Project, flavor: String, channel: String,
                 log: (String) -> Unit = {}, complete: () -> Unit = {}) {
        val root = File(project.basePath ?: return)
        val key = root.canonicalPath
        if (!running.add(key)) { log("Ya hay una descarga Unity en curso."); complete(); return }
        object : SwingWorker<Int, String>() {
            private val output = StringBuilder()
            private var alreadyUpdated = false
            override fun doInBackground(): Int {
                if (channel == "Dev") {
                    publish("Comprobando la versión dev de Unity $flavor...")
                    val remote = UnityDevVersions.readDev(root, flavor)
                        ?: error("No se pudo comprobar la versión dev. No se ha iniciado la descarga.")
                    if (!UnityDevVersions.isNewer(remote, UnityDevVersions.readInstalled(root, flavor), flavor)) {
                        alreadyUpdated = true
                        publish("Build dev actualizada. No es necesario descargarla.")
                        return 0
                    }
                }
                val shell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/WindowsPowerShell/v1.0/powershell.exe")
                val process = ProcessBuilder(shell.absolutePath, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    File(root, "download_unity_build.ps1").absolutePath, flavor, "-Channel", channel)
                    .directory(root).redirectErrorStream(true).start()
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                        if (output.length > 8000) output.delete(0, output.length - 4000)
                        output.append(it).append('\n'); publish(it)
                    } }
                    return process.waitFor()
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            override fun process(chunks: MutableList<String>) { if (!project.isDisposed) chunks.forEach(log) }
            override fun done() {
                running.remove(key)
                if (project.isDisposed) return
                val failure = try { if (get() == 0) null else output.toString().takeLast(4000) }
                    catch (e: Exception) { e.cause?.message ?: e.message ?: "Error de descarga" }
                log(when {
                    failure != null -> "Descarga Unity fallida: $failure"
                    alreadyUpdated -> "Build dev actualizada. Descarga omitida."
                    else -> "Build Unity $flavor descargada. Sincroniza Gradle si es necesario."
                })
                if (failure != null) NotificationGroupManager.getInstance().getNotificationGroup("Inrobics Unity")
                    .createNotification("Descarga Unity $flavor fallida", "Consulta el log del IDE o vuelve a descargar desde el menú Inrobics.", NotificationType.ERROR)
                    .notify(project)
                complete()
            }
        }.execute()
    }
}
