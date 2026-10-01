import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

private val GRADLE_SYSTEM_ID = ProjectSystemId("GRADLE")

// 1. Esta clase guarda la configuración a nivel de proyecto y la persiste entre sesiones
@State(
    name = "InrobicsBuildState",
    storages = [Storage("inrobics_build.xml")]
)
@Service(Service.Level.PROJECT)
class InrobicsBuildState : PersistentStateComponent<InrobicsBuildState> {
    var flavor: String = "Clinic"
    var buildType: String = "Release"
    var outputFormat: String = "Bundle"
    var isAutoIncrementVersion: Boolean = true
    var profileBuild: Boolean = false
    var clinicVersionDate: String = ""
    var clinicVersionSeq: String = ""
    var clinicVersionHotfix: String = "a"
    var virtualVersionPatch: String = ""
    var virtualVersionDate: String = ""
    var careVersionDate: String = ""
    var educaVersionDate: String = ""
    var lastValidatorsTime: String = ""
    var lastValidatorsPassed: Boolean = false

    override fun getState(): InrobicsBuildState = this

    override fun loadState(state: InrobicsBuildState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): InrobicsBuildState = project.getService(InrobicsBuildState::class.java)
    }
}

// 2. Gestión de versión y credenciales de firma
object InrobicsVersionManager {
    private const val MANIFEST_PATH = "inrobics/src/main/AndroidManifest.xml"
    private const val SIGNING_CREDENTIALS_FILE = ".signing_credentials.json"

    val KEYSTORE_MAP = mapOf(
        "clinic"  to "upload-keystore2.jks",
        "care"    to "upload-keystore-care.jks",
        "virtual" to "upload-keystore-virtual.jks",
        "educa"   to "upload-keystore-educa.jks"
    )

    data class VersionInfo(val code: String, val name: String)
    data class SigningCredentials(
        val keystoreFile: String,
        val keyAlias: String,
        val keystorePassword: String,
        val keyPassword: String
    )

    // Rutas candidatas donde puede estar el AndroidManifest con versionCode/versionName
    private val CANDIDATE_PATHS = listOf(
        "inrobics/src/main/AndroidManifest.xml",
        "app/src/main/AndroidManifest.xml",
        "src/main/AndroidManifest.xml"
    )

    fun readVersion(projectPath: String, flavor: String? = null): VersionInfo? {
        if (flavor != null) FlavorVersionFile.find(projectPath, flavor)?.let { return it.version }
        for (path in CANDIDATE_PATHS) {
            val file = File("$projectPath/$path")
            if (!file.exists()) continue
            val content = file.readText()
            val code = Regex("android:versionCode=\"(\\d+)\"").find(content)?.groupValues?.get(1) ?: continue
            val name = Regex("android:versionName=\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: continue
            return VersionInfo(code, name)
        }
        return null
    }

    fun resolveVersionPath(projectPath: String, flavor: String): String? =
        FlavorVersionFile.find(projectPath, flavor)?.file?.absolutePath
            ?: resolveManifestPath(projectPath)?.let { File(projectPath, it).absolutePath }

    /** Devuelve el primer AndroidManifest encontrado con versionCode, o null. Útil para mostrar el path al usuario. */
    fun resolveManifestPath(projectPath: String): String? =
        CANDIDATE_PATHS.firstOrNull { File("$projectPath/$it").exists() }

    /** Lee el applicationId del proyecto Android.
     *  1) Atributo package= del manifest (AGP < 7.3)
     *  2) applicationId / namespace en build.gradle(.kts) (AGP >= 7.3) */
    fun readPackageName(projectPath: String): String? {
        // 1. Manifest
        val manifestPath = resolveManifestPath(projectPath)
        if (manifestPath != null) {
            runCatching {
                Regex("""package\s*=\s*"([^"]+)"""")
                    .find(File("$projectPath/$manifestPath").readText())
                    ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return it }
        }
        // 2. build.gradle / build.gradle.kts
        val gradleCandidates = listOf(
            "inrobics/build.gradle.kts", "inrobics/build.gradle",
            "app/build.gradle.kts",      "app/build.gradle"
        )
        val appIdRe = Regex("""applicationId\s*[=]?\s*"([^"]+)"""")
        val nsRe    = Regex("""namespace\s*[=]?\s*"([^"]+)"""")
        for (gp in gradleCandidates) {
            val f = File("$projectPath/$gp")
            if (!f.exists()) continue
            val txt = f.readText()
            appIdRe.find(txt)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
            nsRe.find(txt)?.groupValues?.get(1)?.takeIf    { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** Calcula el siguiente versionCode Android (YYMMDD + contador del día). */
    fun computeNextVersionCode(currentCode: String): String {
        val now = LocalDateTime.now()
        val today = now.format(DateTimeFormatter.ofPattern("yyMMdd"))
        return if (currentCode.startsWith(today)) {
            today + (currentCode.last().digitToInt() + 1).toString()
        } else {
            "${today}0"
        }
    }

    /** VersionName para sabores no-Clinic: solo la fecha actual YYYY.MM.DD */
    fun computeNextVersionNonClinic(current: VersionInfo): VersionInfo {
        val now = LocalDateTime.now()
        val newName = "${now.year}.${now.monthValue.toString().padStart(2, '0')}.${now.dayOfMonth.toString().padStart(2, '0')}"
        return VersionInfo(computeNextVersionCode(current.code), newName)
    }

    /** Cambia únicamente el último componente numérico, conservando versionCode. */
    fun withVirtualPatch(current: VersionInfo, patch: String): VersionInfo? {
        if (!Regex("[0-9]+").matches(patch)) return null
        val match = Regex("^(.+\\.)([0-9]+)$").matchEntire(current.name) ?: return null
        return current.copy(name = match.groupValues[1] + patch)
    }

    fun buildVirtualVersion(current: VersionInfo, date: String, autoIncrement: Boolean): VersionInfo? {
        val validDate = runCatching {
            java.time.LocalDate.parse(date, DateTimeFormatter.ofPattern("uuuu.MM.dd")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT))
        }.isSuccess
        if (!validDate) return null
        return VersionInfo(if (autoIncrement) computeNextVersionCode(current.code) else current.code, date)
    }

    /** Parsea el versionName de Clinic en (fecha, release, hotfix). Hotfix 'a' = sin sufijo. */
    fun parseClinicVersionName(versionName: String): Triple<String, String, String> {
        val parts = versionName.split("-", limit = 2)
        val date = parts.getOrElse(0) { "" }
        val rest = parts.getOrElse(1) { "01" }
        val m = Regex("""^(\d+)([a-z]*)$""").matchEntire(rest)
        val seq = m?.groupValues?.get(1) ?: rest
        val hotfix = m?.groupValues?.get(2)?.let { if (it.isEmpty()) "a" else it } ?: "a"
        return Triple(date, seq, hotfix)
    }

    /** Construye el versionName de Clinic. Hotfix 'a' = sin sufijo. */
    fun buildClinicVersionName(date: String, seq: String, hotfix: String): String =
        "$date-$seq${if (hotfix == "a" || hotfix.isEmpty()) "" else hotfix}"

    /** Construye un VersionInfo completo para Clinic con el código Android incrementado. */
    fun buildClinicVersion(currentCode: String, date: String, seq: String, hotfix: String): VersionInfo =
        VersionInfo(computeNextVersionCode(currentCode), buildClinicVersionName(date, seq, hotfix))

    fun writeVersion(projectPath: String, old: VersionInfo, new: VersionInfo, flavor: String? = null) {
        if (flavor != null) FlavorVersionFile.find(projectPath, flavor)?.let {
            FlavorVersionFile.write(it, new)
            return
        }
        val path = resolveManifestPath(projectPath) ?: return
        val file = File("$projectPath/$path")
        val content = file.readText()
            .replace("""android:versionCode="${old.code}""", """android:versionCode="${new.code}""")
            .replace("""android:versionName="${old.name}""", """android:versionName="${new.name}""")
        file.writeText(content)
    }

    fun loadCredentials(projectPath: String, flavor: String): SigningCredentials? {
        val file = File("$projectPath/$SIGNING_CREDENTIALS_FILE")
        if (!file.exists()) return null
        return try {
            val root = Gson().fromJson(file.readText(), JsonObject::class.java) ?: return null
            val entry = root.getAsJsonObject(flavor.lowercase()) ?: return null
            SigningCredentials(
                keystoreFile     = entry.get("keystore_file").asString,
                keyAlias         = entry.get("key_alias").asString,
                keystorePassword = entry.get("keystore_password").asString,
                keyPassword      = entry.get("key_password").asString
            )
        } catch (e: Exception) { null }
    }

    fun saveCredentials(projectPath: String, flavor: String, creds: SigningCredentials) {
        val file = File("$projectPath/$SIGNING_CREDENTIALS_FILE")
        val root = try {
            Gson().fromJson(if (file.exists()) file.readText() else "{}", JsonObject::class.java) ?: JsonObject()
        } catch (e: Exception) { JsonObject() }
        root.add(flavor.lowercase(), JsonObject().apply {
            addProperty("keystore_file", creds.keystoreFile)
            addProperty("key_alias", creds.keyAlias)
            addProperty("keystore_password", creds.keystorePassword)
            addProperty("key_password", creds.keyPassword)
        })
        file.writeText(Gson().toJson(root))
    }

    fun writeKeystoreProperties(projectPath: String, creds: SigningCredentials) {
        File("$projectPath/keystore.properties").writeText(
            "KEYSTORE_FILE=${creds.keystoreFile}\n" +
            "KEYSTORE_PASSWORD=${creds.keystorePassword}\n" +
            "KEY_ALIAS=${creds.keyAlias}\n" +
            "KEY_PASSWORD=${creds.keyPassword}\n"
        )
    }

    // -- Registro de versiones por sabor (.inrobics_versions.json) --
    private const val VERSIONS_JSON = ".inrobics_versions.json"

    data class VersionRecord(val versionName: String, val versionCode: String)

    fun loadVersionRecord(projectPath: String, flavor: String): VersionRecord? {
        val file = File("$projectPath/$VERSIONS_JSON")
        if (!file.exists()) return null
        return try {
            val root = Gson().fromJson(file.readText(), JsonObject::class.java) ?: return null
            val entry = root.getAsJsonObject(flavor.lowercase()) ?: return null
            VersionRecord(entry.get("versionName").asString, entry.get("versionCode").asString)
        } catch (e: Exception) { null }
    }

    fun saveVersionRecord(projectPath: String, flavor: String, record: VersionRecord) {
        val file = File("$projectPath/$VERSIONS_JSON")
        val root = try {
            Gson().fromJson(if (file.exists()) file.readText() else "{}", JsonObject::class.java) ?: JsonObject()
        } catch (e: Exception) { JsonObject() }
        root.add(flavor.lowercase(), JsonObject().apply {
            addProperty("versionName", record.versionName)
            addProperty("versionCode", record.versionCode)
        })
        file.writeText(Gson().toJson(root))
    }
}

// 3. Diálogo para introducir credenciales de firma
class SigningCredentialsDialog(
    project: Project,
    flavor: String,
    private val keystoreFile: String
) : DialogWrapper(project) {
    val keyAliasField = JTextField(20)
    val keystorePasswordField = JPasswordField(20)
    val keyPasswordField = JPasswordField(20)

    init {
        title = "Credenciales de firma · $flavor ($keystoreFile)"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }
        fun row(label: String, field: JComponent, row: Int) {
            c.gridx = 0; c.gridy = row; panel.add(JLabel(label), c)
            c.gridx = 1; panel.add(field, c)
        }
        row("Key alias:", keyAliasField, 0)
        row("Keystore password:", keystorePasswordField, 1)
        row("Key password:", keyPasswordField, 2)
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2
        panel.add(JLabel("<html><small>Keystore: $keystoreFile</small></html>"), c)
        return panel
    }

    override fun doOKAction() {
        if (keyAliasField.text.isBlank()) { setErrorText("Introduce el key alias", keyAliasField); return }
        if (keystorePasswordField.password.isEmpty()) { setErrorText("Introduce la keystore password", keystorePasswordField); return }
        super.doOKAction()
    }
}

// 4. Runner para generate signed release
object SignedBuildRunner {
    fun execute(project: Project, state: InrobicsBuildState) {
        val projectPath = project.basePath ?: return
        val flavorKey = state.flavor.lowercase()
        val keystoreFile = InrobicsVersionManager.KEYSTORE_MAP[flavorKey]
        if (keystoreFile == null) {
            Messages.showErrorDialog(
                project,
                "No hay keystore configurado para el flavor '${state.flavor}'.\nFlavors soportados: Clinic, Care, Virtual, Educa.",
                "Error de firma"
            )
            return
        }
        var creds = InrobicsVersionManager.loadCredentials(projectPath, flavorKey)
        if (creds == null) {
            val dialog = SigningCredentialsDialog(project, state.flavor, keystoreFile)
            if (!dialog.showAndGet()) return
            creds = InrobicsVersionManager.SigningCredentials(
                keystoreFile     = keystoreFile,
                keyAlias         = dialog.keyAliasField.text,
                keystorePassword = String(dialog.keystorePasswordField.password),
                keyPassword      = String(dialog.keyPasswordField.password)
            )
            InrobicsVersionManager.saveCredentials(projectPath, flavorKey, creds)
        }
        InrobicsVersionManager.writeKeystoreProperties(projectPath, creds)
        val flavor = state.flavor.replaceFirstChar { it.uppercase() }
        val taskName = "bundle${flavor}Release"
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = projectPath
            taskNames = listOf(taskName)
            externalSystemIdString = GRADLE_SYSTEM_ID.id
        }
        ExternalSystemUtil.runTask(settings, DefaultRunExecutor.EXECUTOR_ID, project, GRADLE_SYSTEM_ID)
    }
}

// 5. Este objeto centraliza la ejecución para no repetir código
object InrobicsCommandRunner {
    fun execute(project: Project, state: InrobicsBuildState, log: ((String) -> Unit)? = null) {
        val flavor    = state.flavor.replaceFirstChar { it.uppercase() }
        val buildType = state.buildType.replaceFirstChar { it.uppercase() }
        val basePath  = project.basePath ?: return
        val packageName = when (state.flavor.lowercase()) {
            "clinic" -> "com.inrobics.app"
            else     -> "com.inrobics.${state.flavor.lowercase()}"
        }

        val isApkMode = state.outputFormat != "Bundle"

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = basePath
            taskNames = if (!isApkMode)
                listOf("bundle${flavor}${buildType}", "assemble${flavor}${buildType}")
            else
                listOf("assemble${flavor}${buildType}")  // Solo compilar el APK
            if (state.profileBuild) scriptParameters = "--profile"
            externalSystemIdString = GRADLE_SYSTEM_ID.id
        }

        val buildStart = System.currentTimeMillis()
        log?.invoke("🔨 Iniciando Gradle ${if (isApkMode) "assemble" else "bundle"}${flavor}${buildType}...")

        val callback = object : TaskCallback {
            override fun onSuccess() {
                val buildMs = System.currentTimeMillis() - buildStart
                log?.invoke("⏱️ Gradle build: ${buildMs}ms")
                log?.invoke("✅ onSuccess — modo: ${if (isApkMode) "APK" else "Bundle"} | adb: ${adbPath()}")
                if (state.profileBuild) {
                    val reportDir = java.io.File("$basePath/build/reports/profile")
                    val report = reportDir.listFiles { f -> f.extension == "html" }
                        ?.maxByOrNull { it.lastModified() }
                    if (report != null) {
                        log?.invoke("📊 Informe: ${report.absolutePath}")
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                            .openFile(com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                .refreshAndFindFileByIoFile(report)!!, true)
                    }
                }

                if (!isApkMode) {
                    // Bundle mode: instalar con bundletool (igual que Android Studio / buildInrobics.py)
                    val bundleOutputDir = java.io.File("$basePath/inrobics/build/outputs/bundle")
                    val aabFile = bundleOutputDir.walkTopDown()
                        .filter { it.extension == "aab" }
                        .maxByOrNull { it.lastModified() }
                    if (aabFile == null) {
                        log?.invoke("⚠️ No se encontró .aab en ${bundleOutputDir.path}")
                        return
                    }
                    log?.invoke("📦 AAB: ${aabFile.name}")

                    val toolsDir = java.io.File("$basePath/tools")
                    val bundletoolJar = java.io.File(toolsDir, "bundletool.jar")
                    if (!bundletoolJar.exists()) {
                        log?.invoke("📥 Descargando bundletool.jar...")
                        toolsDir.mkdirs()
                        try {
                            java.net.URL("https://github.com/google/bundletool/releases/download/1.16.0/bundletool-all-1.16.0.jar")
                                .openStream().use { input ->
                                    bundletoolJar.outputStream().use { output -> input.copyTo(output) }
                                }
                            log?.invoke("✅ bundletool.jar listo")
                        } catch (e: Exception) {
                            log?.invoke("❌ Error descargando bundletool: ${e.message}")
                            return
                        }
                    }

                    val apksOutput = java.io.File("$basePath/inrobics/build/outputs/bundle/app.apks")
                    val tempDir = java.io.File("$basePath/inrobics/build/outputs/bundle/temp")
                    // Si el .apks ya existe y es más nuevo que el .aab, no hace falta regenerarlo
                    val skipBuildApks = apksOutput.exists() && apksOutput.lastModified() >= aabFile.lastModified()
                    if (!skipBuildApks) apksOutput.delete()
                    tempDir.mkdirs()

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        val serial = resolveAdbSerial()
                        log?.invoke("📱 Serial ADB: ${serial ?: "(default/ninguno)"} | adb: ${adbPath()}")
                        val serialFlag = if (serial != null) listOf("--device-id=$serial") else emptyList()
                        val deviceArgs = if (serial != null) listOf("-s", serial) else emptyList()
                        val cacheDesc = if (skipBuildApks) " (build-apks cacheado)" else ""
                        log?.invoke("📲 Instalando bundle$cacheDesc...")
                        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                            if (!skipBuildApks) {
                                val t0 = System.currentTimeMillis()
                                val args = buildList {
                                    addAll(listOf("java", "-Xmx8G", "-jar", bundletoolJar.absolutePath, "build-apks"))
                                    add("--bundle=${aabFile.absolutePath}")
                                    add("--output=${apksOutput.absolutePath}")
                                    add("--connected-device")
                                    addAll(serialFlag)
                                }
                                val (exit, out) = runProcess(args, log)
                                if (exit != 0) { log?.invoke("❌ build-apks falló: $out"); return@executeOnPooledThread }
                                log?.invoke("⏱️ build-apks: ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s")
                            }
                            // install-apks instala directamente al dispositivo (no necesita extract-apks)
                            val t0 = System.currentTimeMillis()
                            val installArgs = buildList {
                                addAll(listOf("java", "-Xmx8G", "-jar", bundletoolJar.absolutePath, "install-apks"))
                                add("--apks=${apksOutput.absolutePath}")
                                addAll(serialFlag)
                            }
                            val (exit, out) = runProcess(installArgs, log)
                            log?.invoke("⏱️ install-apks: ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s")
                            val isVersionError = out.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ||
                                                 out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                            if (exit == 0 && !isVersionError) {
                                log?.invoke("✅ Instalada correctamente")
                                runProcess(buildList {
                                    add(adbPath()); addAll(deviceArgs)
                                    addAll(listOf("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
                                }, log)
                                log?.invoke("🚀 App lanzada")
                            } else if (isVersionError) {
                                log?.invoke("⚠️ Versión incompatible detectada")
                                val latch = java.util.concurrent.CountDownLatch(1)
                                var doUninstall = false
                                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                    doUninstall = Messages.showOkCancelDialog(
                                        project,
                                        "La versión instalada en el dispositivo es incompatible con esta build.\n\n" +
                                        "¿Desinstalar la app del dispositivo e instalar de nuevo?",
                                        "Versión incompatible", "Desinstalar e instalar", "Cancelar",
                                        Messages.getWarningIcon()
                                    ) == Messages.OK
                                    latch.countDown()
                                }
                                latch.await()
                                if (doUninstall) {
                                    runProcess(buildList { add(adbPath()); addAll(deviceArgs); addAll(listOf("uninstall", packageName)) }, log)
                                    val (retryExit, retryOut) = runProcess(installArgs, log)
                                    if (retryExit == 0 && !retryOut.contains("Failure [")) {
                                        log?.invoke("✅ Instalada correctamente")
                                        runProcess(buildList { add(adbPath()); addAll(deviceArgs); addAll(listOf("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")) }, log)
                                        log?.invoke("🚀 App lanzada")
                                    } else { log?.invoke("❌ Error al reinstalar: $retryOut") }
                                }
                            } else { log?.invoke("❌ install-apks falló: $out") }
                        }
                    }
                    return
                }

                log?.invoke("APK generado; build completada")
            }
            override fun onFailure() {
                log?.invoke("❌ Build fallida")
            }
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GRADLE_SYSTEM_ID,
            callback,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            false
        )
    }

    /** Devuelve el serial cuando hay exactamente un dispositivo ADB conectado. */
    private fun resolveAdbSerial(): String? = try {
        val proc = ProcessBuilder(adbPath(), "devices").start()
        val lines = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        // Líneas válidas: "<serial>\tdevice" o "<serial>\temulator"
        val devices = lines.drop(1)
            .filter { it.contains("\tdevice") }
            .map { it.substringBefore("\t").trim() }
            .filter { it.isNotBlank() }
        if (devices.size == 1) devices.first() else null
    } catch (_: Exception) { null }

    private fun adbPath(): String {
        val sdkHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getProperty("user.home") + "\\AppData\\Local\\Android\\Sdk"
        val adbExe = java.io.File(sdkHome, "platform-tools\\adb.exe")
        return if (adbExe.exists()) adbExe.absolutePath else "adb"
    }

    private fun runProcess(args: List<String>, log: ((String) -> Unit)? = null): Pair<Int, String> {
        return try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val sb = StringBuilder()
            proc.inputStream.bufferedReader().forEachLine { line -> sb.appendLine(line); log?.invoke(line) }
            Pair(proc.waitFor(), sb.toString().trim())
        } catch (e: Exception) {
            val msg = "Error ejecutando proceso [${args.firstOrNull()}]: ${e.message}"
            log?.invoke("❌ $msg")
            Pair(-1, msg)
        }
    }

    /** Abre una sesión independiente sin usar la ruta de shell configurada en el IDE. */
    fun runValidatorsInTerminal(project: Project, onResult: (Boolean, String) -> Unit) {
        val basePath = project.basePath ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        if (toolWindow == null) {
            Messages.showErrorDialog(project, "La ventana Terminal no está disponible.", "Validadores")
            return
        }
        toolWindow.show {
            var sessionDirectory: File? = null
            try {
                val directory = java.nio.file.Files.createTempDirectory("inrobics-validators-").toFile()
                sessionDirectory = directory
                val resultFile = File(directory, "result.json")
                val wrapper = File(directory, "run.py")
                wrapper.writeText("""
                    import datetime, json, pathlib, subprocess, sys, traceback
                    code = 1
                    try:
                        code = subprocess.call([sys.executable, '-X', 'utf8', 'buildInrobics.py', '--run_validators'])
                    except Exception:
                        traceback.print_exc()
                    finally:
                        destination = pathlib.Path(__file__).with_name('result.json')
                        temporary = destination.with_suffix('.tmp')
                        temporary.write_text(json.dumps({'passed': code == 0, 'time': datetime.datetime.now().strftime('%d/%m/%Y %H:%M:%S')}), encoding='utf-8')
                        temporary.replace(destination)
                    sys.exit(code)
                """.trimIndent(), Charsets.UTF_8)
                val windows = System.getProperty("os.name").lowercase().contains("win")
                val shell = if (windows) {
                    val cmd = System.getenv("ComSpec")
                        ?: File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/cmd.exe").absolutePath
                    listOf(cmd, "/d", "/k", "chcp 65001 >nul")
                } else listOf("/bin/sh", "-i")
                val widget = TerminalToolWindowManager.getInstance(project)
                    .createNewSession(basePath, "Inrobics Validators", shell, true, true)
                val scriptPath = if (windows) "\"${wrapper.absolutePath}\""
                    else "'${wrapper.absolutePath.replace("'", "'\\''")}'"
                val command = if (windows)
                    "set \"PYTHONUTF8=1\" && set \"PYTHONIOENCODING=utf-8\" && python -X utf8 $scriptPath"
                else "PYTHONUTF8=1 PYTHONIOENCODING=utf-8 python -X utf8 $scriptPath"
                @Suppress("DEPRECATION", "removal")
                ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).executeCommand(command)
                val started = System.currentTimeMillis()
                val timer = javax.swing.Timer(500, null)
                timer.addActionListener {
                    if (project.isDisposed || System.currentTimeMillis() - started > 24 * 60 * 60 * 1000L) {
                        timer.stop()
                        directory.deleteRecursively()
                    } else if (resultFile.exists()) {
                        timer.stop()
                        try {
                            val result = Gson().fromJson(resultFile.readText(Charsets.UTF_8), JsonObject::class.java)
                            onResult(result.get("passed").asBoolean, result.get("time").asString)
                        } finally {
                            directory.deleteRecursively()
                        }
                    }
                }
                timer.start()
            } catch (ex: Exception) {
                sessionDirectory?.deleteRecursively()
                onResult(false, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")))
                Messages.showErrorDialog(project, "No se pudo abrir la terminal: ${ex.message}", "Validadores")
            }
        }
    }

    /** Corre los validadores de forma síncrona con diálogo de progreso. Devuelve (pasó, output). */
    fun runValidatorsSync(project: Project): Pair<Boolean, String> {
        val basePath = project.basePath ?: return Pair(false, "No project path")
        var exitCode = -1
        var output = ""
        com.intellij.openapi.progress.ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                {
                    try {
                        val cmd = listOf("python", "-X", "utf8", "buildInrobics.py", "--run_validators")
                        val proc = ProcessBuilder(cmd)
                            .directory(File(basePath))
                            .redirectErrorStream(true)
                            .apply {
                                environment()["PYTHONUTF8"] = "1"
                                environment()["PYTHONIOENCODING"] = "utf-8"
                            }
                            .start()
                        output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                        exitCode = proc.waitFor()
                    } catch (ex: Exception) {
                        output = ex.stackTraceToString()
                        ex.printStackTrace()
                    }
                },
                "Ejecutando validadores…",
                false,
                project
            )
        return Pair(exitCode == 0, output)
    }
}
