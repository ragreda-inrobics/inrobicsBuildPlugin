import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.awt.Color
import java.io.File
import java.util.Properties
import javax.swing.*

/** Local installation is reread before displaying the cached remote dev status. */
class UnityBuildPanel(private val project: Project, private val flavor: () -> String,
                      private val log: (String) -> Unit) : JPanel() {
    private val status = JLabel()
    private val defined = JButton("Descargar versión master definida")
    private val dev = JButton("Comprobando build dev…")
    private var busy = false
    private val checking = mutableSetOf<String>()
    private val checkedAt = mutableMapOf<String, Long>()
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(JLabel("Build de Unity"))
        add(status)
        add(defined)
        add(dev)
        defined.addActionListener { download("Defined") }
        dev.addActionListener { download("Dev") }
        refresh()
        Timer(5000) { event ->
            if (project.isDisposed) (event.source as Timer).stop() else refresh()
        }.start()
    }
    fun refresh() {
        val selected = flavor()
        isVisible = selected == "Care" || selected == "Virtual"
        if (!isVisible) return
        try {
            val root = File(project.basePath ?: return)
            val config = File(root, "unity-builds.json")
            val pin = if (config.exists()) Gson().fromJson(config.readText(), JsonObject::class.java)
                ?.getAsJsonObject(selected)?.get("buildId")?.asString.orEmpty() else ""
            val properties = Properties()
            File(root, "gradle.properties").takeIf { it.exists() }?.reader()?.use { properties.load(it) }
            val path = properties.getProperty("unityLibraryPath$selected", "../UnityProject/androidBuild$selected/unityLibrary")
            val library = File(path).let { if (it.isAbsolute) it else File(root, path) }
            val metadata = File(library, "unity-build.json")
            val installed = if (metadata.exists()) Gson().fromJson(metadata.readText(), JsonObject::class.java)
                ?.get("buildId")?.asString.orEmpty() else ""
            val matches = pin.isNotEmpty() && pin == installed
            status.text = "<html>master: ${escape(pin.ifEmpty { "sin definir" })}<br>Instalada: ${escape(installed.ifEmpty { "sin identificar" })}<br>" +
                (if (matches) "Actualizada" else "⚠ La build instalada no coincide con master") + "</html>"
            status.foreground = if (matches) Color(0x388E3C) else Color(0xC68A22)
            defined.isEnabled = !busy && pin.isNotEmpty()
            val remote = UnityDevVersions.cachedDev(root, selected)
            val newDev = remote?.let { UnityDevVersions.isNewer(it, UnityDevVersions.readInstalled(root, selected), selected) }
            dev.text = when {
                busy -> "Descarga en curso…"
                newDev == true -> "Descargar última dev"
                newDev == false -> "Build dev actualizada"
                selected in checking -> "Comprobando build dev…"
                else -> "Comprobar build dev"
            }
            dev.isEnabled = !busy && newDev != false && selected !in checking
            if (System.currentTimeMillis() - (checkedAt[selected] ?: 0) > 60000 && checking.add(selected)) {
                dev.isEnabled = false
                if (remote == null) dev.text = "Comprobando build dev…"
                object : SwingWorker<Unit, Unit>() {
                    override fun doInBackground() { runCatching { UnityDevVersions.readDev(root, selected) } }
                    override fun done() {
                        checking.remove(selected)
                        checkedAt[selected] = System.currentTimeMillis()
                        if (!project.isDisposed) refresh()
                    }
                }.execute()
            }
        } catch (e: Exception) {
            status.text = "No se puede leer la versión Unity: ${e.message}"
            defined.isEnabled = false
            dev.text = "Comprobar build dev"
            dev.isEnabled = !busy
        }
        revalidate()
        repaint()
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun download(channel: String) {
        if (busy) return
        val selected = flavor()
        busy = true
        refresh()
        UnityBuildDownloads.download(project, selected, channel, log) {
            busy = false
            refresh()
        }
    }
}
