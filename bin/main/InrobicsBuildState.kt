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
    var buildType: String = "Debug"
    var outputFormat: String = "Bundle"
    var isAutoIncrementVersion: Boolean = true
    var profileBuild: Boolean = false
    var clinicVersionDate: String = ""
    var clinicVersionSeq: String = ""
    var clinicVersionHotfix: String = "a"

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
        "virtual" to "upload-keystore-virtual.jks"
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

    fun readVersion(projectPath: String): VersionInfo? {
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

    fun writeVersion(projectPath: String, old: VersionInfo, new: VersionInfo) {
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
                "No hay keystore configurado para el flavor '${state.flavor}'.\nFlavors soportados: Clinic, Care, Virtual.",
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
        val taskName = if (state.outputFormat == "Bundle") "bundle${flavor}Release" else "assemble${flavor}Release"
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = projectPath
            taskNames = listOf(taskName)
            externalSystemIdString = GRADLE_SYSTEM_ID.id
        }
        ExternalSystemUtil.runTask(settings, DefaultRunExecutor.EXECUTOR_ID, project, GRADLE_SYSTEM_ID)
    }
}

// 4.5. Gestiona el intercambio de assets antes/después de compilar APK (desarrollo)
object AssetPreparer {
    private const val ASSETS_PATH = "inrobics/src/main/assets"
    private const val ORIG_PATH   = "inrobics/src/main/assets_original"
    private const val TEMP_PATH   = "inrobics/src/main/temp_assets"
    private const val FP_FILE     = ".inrobics_fingerprint.json"

    /** Fingerprint rápido: "fileCount:totalBytes:newestMtime" sin leer contenido. */
    private fun fingerprint(dir: File): String {
        if (!dir.exists()) return "0:0:0"
        var n = 0L; var b = 0L; var t = 0L
        dir.walkTopDown().filter { it.isFile }.forEach {
            n++; b += it.length(); if (it.lastModified() > t) t = it.lastModified()
        }
        return "$n:$b:$t"
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadStored(tempDir: File): Map<String, String> = try {
        val f = File(tempDir, FP_FILE)
        if (!f.exists()) emptyMap()
        else Gson().fromJson(f.readText(), Map::class.java) as? Map<String, String> ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    private fun saveFingerprints(tempDir: File, fp: Map<String, String>) {
        File(tempDir, FP_FILE).writeText(Gson().toJson(fp))
    }

    private const val STATE_FILE = ".inrobics_build_state.json"

    @Suppress("UNCHECKED_CAST")
    private fun readPreparedState(projectPath: String): Map<String, String>? = try {
        val f = File("$projectPath/$STATE_FILE")
        if (!f.exists()) null
        else Gson().fromJson(f.readText(), Map::class.java) as? Map<String, String>
    } catch (_: Exception) { null }

    private fun writePreparedState(projectPath: String, fp: Map<String, String>) {
        File("$projectPath/$STATE_FILE").writeText(Gson().toJson(fp))
    }

    fun clearPreparedState(projectPath: String) {
        File("$projectPath/$STATE_FILE").delete()
    }

    fun isPrepared(projectPath: String): Boolean =
        File("$projectPath/$STATE_FILE").exists() && File("$projectPath/$ORIG_PATH").exists()

    private fun bundleSources(root: String, flavor: String): LinkedHashMap<String, File> {
        val map = LinkedHashMap<String, File>()
        map["mediapipe_assets"] = File("$root/mediapipe_assets/src/main/assets")
        if (!flavor.equals("clinic", ignoreCase = true))
            map["audio_assets"] = File("$root/audio_assets/src/main/assets")
        if (flavor.equals("virtual", ignoreCase = true)) {
            map["unity_v_data"] = File("$root/../UnityProject/androidBuildVirtual/UnityDataAssetPack/src/main/assets")
            map["unity_v_lib"]  = File("$root/../UnityProject/androidBuildVirtual/unityLibrary/src/main/assets")
        }
        if (flavor.equals("care", ignoreCase = true)) {
            map["unity_c_data"] = File("$root/../UnityProject/androidBuildCare/UnityDataAssetPack/src/main/assets")
            map["unity_c_lib"]  = File("$root/../UnityProject/androidBuildCare/unityLibrary/src/main/assets")
            map["vosk_assets"]  = File("$root/vosk_assets/src/main/assets")
        }
        return map
    }

    /**
     * Prepara los assets antes de compilar.
     * @return null si OK, mensaje de error si falla.
     */
    fun prepare(
        projectPath: String,
        flavor: String,
        buildType: String,
        indicator: com.intellij.openapi.progress.ProgressIndicator?,
        log: ((String) -> Unit)? = null
    ): String? {
        val t0 = System.currentTimeMillis()
        var tLast = t0
        fun step(msg: String) {
            val now = System.currentTimeMillis()
            val delta = now - tLast; tLast = now
            val total = now - t0
            val tag = if (delta > 50) " [+${delta}ms / ${total}ms]" else ""
            indicator?.text = msg
            log?.invoke("$msg$tag")
        }
        val assetsDir = File("$projectPath/$ASSETS_PATH")
        val origDir   = File("$projectPath/$ORIG_PATH")
        val tempDir   = File("$projectPath/$TEMP_PATH")

        // Verificar si assets ya están preparados para este flavor/buildType/fuentes
        val storedState = readPreparedState(projectPath)
        if (storedState != null && origDir.exists()) {
            val sources = bundleSources(projectPath, flavor)
            val checkKeys = LinkedHashMap<String, File>().also { it["assets_original"] = origDir; it.putAll(sources) }
            val currentFp = LinkedHashMap(checkKeys.mapValues { (_, d) -> fingerprint(d) }).also {
                it["__config__"] = "${flavor.lowercase()}|${buildType.lowercase()}"
            }
            if (currentFp == storedState) {
                step("⚡ Assets ya preparados para ${flavor}/${buildType}, skip swap")
                return null  // Gradle no ve cambios → UP-TO-DATE garantizado
            } else {
                if (storedState["__config__"] != currentFp["__config__"])
                    step("🔄 Config cambiada (${storedState["__config__"]} → ${currentFp["__config__"]}), restaurando...")
                else
                    step("🔄 Fuentes modificadas, restaurando assets...")
                clearPreparedState(projectPath)
                if (assetsDir.exists()) { tempDir.deleteRecursively(); assetsDir.renameTo(tempDir) }
                if (!origDir.renameTo(assetsDir))
                    return "No se pudo restaurar assets_original → assets."
            }
        } else if (storedState != null && !origDir.exists()) {
            clearPreparedState(projectPath)  // state file huérfano
        }

        // Recovery: estado inconsistente de build anterior interrumpida
        if (origDir.exists()) {
            step("⚠️ Recovery: assets_original encontrado, restaurando estado previo...")
            if (assetsDir.exists()) {
                if (!tempDir.exists()) assetsDir.renameTo(tempDir)
                else assetsDir.deleteRecursively()
            }
            if (!origDir.renameTo(assetsDir))
                return "No se pudo recuperar assets. Renombra manualmente assets_original → assets."
        }

        // Step 1: assets → assets_original
        step("📂 assets → assets_original")
        if (assetsDir.exists() && !assetsDir.renameTo(origDir))
            return "No se pudo renombrar 'assets' → 'assets_original'."

        return try {
            val sources = bundleSources(projectPath, flavor)
            val allKeys = LinkedHashMap<String, File>().also { it["assets_original"] = origDir; it.putAll(sources) }

            step("🔍 Calculando cambios en assets...")
            val current = LinkedHashMap(allKeys.mapValues { (_, d) -> fingerprint(d) }).also {
                it["__config__"] = "${flavor.lowercase()}|${buildType.lowercase()}"
            }
            val stored  = loadStored(tempDir)
            val needsRebuild = (current != stored) || !tempDir.exists()
            if (needsRebuild && stored["__config__"] != null && stored["__config__"] != current["__config__"])
                step("🔄 Cambio de configuración detectado (${stored["__config__"]} → ${current["__config__"]}), reconstruyendo...")

            if (needsRebuild) {
                step("🛠️ Construyendo temp_assets (sin caché)...")
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                // Copiar assets_original
                if (origDir.exists()) {
                    val count = origDir.walkTopDown().count { it.isFile }
                    step("📄 Copiando assets_original ($count ficheros)...")
                    origDir.walkTopDown().filter { it.isFile }.forEach { src ->
                        val rel = src.relativeTo(origDir).path.replace('\\', '/')
                        val dest = File(tempDir, rel)
                        dest.parentFile?.mkdirs()
                        src.copyTo(dest, overwrite = true)
                    }
                }

                // Copiar bundles
                sources.entries.forEachIndexed { i, (key, srcDir) ->
                    if (srcDir.exists()) {
                        val count = srcDir.walkTopDown().count { it.isFile }
                        step("📄 Copiando $key ($count ficheros) [${i + 1}/${sources.size}]...")
                        srcDir.walkTopDown().filter { it.isFile }.forEach { src ->
                            val dest = File(tempDir, src.relativeTo(srcDir).path)
                            dest.parentFile?.mkdirs()
                            src.copyTo(dest, overwrite = true)
                        }
                    } else {
                        step("⚠️ $key no encontrado: ${srcDir.absolutePath}")
                    }
                }

                saveFingerprints(tempDir, current)
                step("✅ temp_assets construido")
            } else {
                step("⚡ Assets en caché ✓")
            }

            // Step 3: temp_assets → assets
            step("📂 temp_assets → assets")
            if (!tempDir.renameTo(assetsDir)) {
                origDir.renameTo(assetsDir) // best-effort restore
                return "No se pudo renombrar 'temp_assets' → 'assets'."
            }
            writePreparedState(projectPath, current)
            step("✅ Assets listos para la build")
            null
        } catch (e: Exception) {
            step("❌ Error: ${e.message}")
            clearPreparedState(projectPath)
            try { assetsDir.deleteRecursively(); origDir.renameTo(assetsDir) } catch (_: Exception) {}
            "Error preparando assets: ${e.message}"
        }
    }

    /** Restaura assets originales y guarda temp_assets para caché en la próxima build. */
    fun restore(projectPath: String, log: ((String) -> Unit)? = null) {
        clearPreparedState(projectPath)
        val assetsDir = File("$projectPath/$ASSETS_PATH")
        val origDir   = File("$projectPath/$ORIG_PATH")
        val tempDir   = File("$projectPath/$TEMP_PATH")
        if (assetsDir.exists()) {
            log?.invoke("📂 assets → temp_assets (caché)")
            tempDir.deleteRecursively()
            assetsDir.renameTo(tempDir)
        }
        if (origDir.exists()) {
            log?.invoke("📂 assets_original → assets")
            origDir.renameTo(assetsDir)
        }
        log?.invoke("✅ Assets restaurados")
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

        // Para builds de Bundle o APK: restaurar si los assets están en estado preparado (de APK desarrollo anterior)
        if (state.outputFormat != "APK (desarrollo)" && AssetPreparer.isPrepared(basePath)) {
            log?.invoke("📂 Restaurando assets originales para build de Bundle...")
            AssetPreparer.restore(basePath, log)
        }

        // Preparar assets solo en modo APK (desarrollo)
        if (state.outputFormat == "APK (desarrollo)") {
            var prepareError: String? = null
            val pm = com.intellij.openapi.progress.ProgressManager.getInstance()
            val prepareStart = System.currentTimeMillis()
            pm.runProcessWithProgressSynchronously({
                prepareError = AssetPreparer.prepare(basePath, state.flavor, state.buildType, pm.progressIndicator, log)
            }, "Preparando assets…", false, project)
            log?.invoke("⏱️ Prepare total: ${System.currentTimeMillis() - prepareStart}ms")

            if (prepareError != null) {
                log?.invoke("❌ Error: $prepareError")
                Messages.showErrorDialog(project, prepareError!!, "Error preparando assets")
                return
            }
        }

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = basePath
            taskNames = if (!isApkMode)
                listOf("bundle${flavor}${buildType}", "assemble${flavor}${buildType}")
            else
                listOf("assemble${flavor}${buildType}")  // solo build; install gestionado por el plugin
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

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val serial = resolveAdbSerial()
                    log?.invoke("📱 Serial ADB: ${serial ?: "(default/ninguno)"}")

                    // APK mode: buscar APK e instalar
                    val apkDir = java.io.File("$basePath/inrobics/build/outputs/apk/${state.flavor.lowercase()}/${state.buildType.lowercase()}")
                    log?.invoke("📂 Buscando APK en: ${apkDir.path}")
                    val apkFile = apkDir.listFiles { f -> f.extension == "apk" }?.firstOrNull()
                    if (apkFile != null) {
                        val lastInstalled = readLastInstallTime(basePath)
                        if (apkFile.lastModified() != lastInstalled) {
                            val sizeMb = apkFile.length() / 1024 / 1024
                            log?.invoke("📦 APK cambiado, instalando ($sizeMb MB)...")
                            val installArgs = buildList {
                                add(adbPath())
                                if (serial != null) { add("-s"); add(serial) }
                                addAll(listOf("install", "-r", apkFile.absolutePath))
                            }
                            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                                if (adbInstallWithRetry(project, packageName, serial, installArgs, log)) {
                                    writeLastInstallTime(basePath, apkFile.lastModified())
                                    runProcess(buildList {
                                        add(adbPath())
                                        if (serial != null) { add("-s"); add(serial) }
                                        addAll(listOf("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
                                    }, log)
                                    log?.invoke("🚀 App lanzada")
                                }
                            }
                            return@invokeLater
                        } else {
                            log?.invoke("⚡ APK sin cambios desde último install, skip")
                        }
                    } else {
                        log?.invoke("⚠️ No se encontró APK en ${apkDir.path}")
                    }

                    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                        runProcess(buildList {
                            add(adbPath())
                            if (serial != null) { add("-s"); add(serial) }
                            addAll(listOf("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
                        }, log)
                        log?.invoke("🚀 App lanzada")
                    }
                }
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

    /**
     * Ejecuta `adb devices` y devuelve el serial del dispositivo conectado.
     * - 1 dispositivo → devuelve su serial (se usará -s <serial>)
     * - 0 o >1 dispositivos → devuelve null (adb decide por defecto / falla con error explicativo)
     */
    private fun readLastInstallTime(projectPath: String): Long = try {
        java.io.File("$projectPath/.inrobics_last_install").readText().trim().toLong()
    } catch (_: Exception) { 0L }

    private fun writeLastInstallTime(projectPath: String, time: Long) {
        java.io.File("$projectPath/.inrobics_last_install").writeText(time.toString())
    }

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

    private fun openTerminalAndRun(project: Project, workDir: String?, command: String, title: String) {
        val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
        terminalToolWindow?.show {
            try {
                @Suppress("DEPRECATION", "removal")
                val widget = TerminalToolWindowManager.getInstance(project)
                    .createLocalShellWidget(workDir, title)
                @Suppress("DEPRECATION", "removal")
                widget.executeCommand(command)
            } catch (ex: Exception) { ex.printStackTrace() }
        }
    }

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

    private fun adbInstallWithRetry(
        project: Project,
        packageName: String,
        serial: String?,
        installArgs: List<String>,
        log: ((String) -> Unit)?
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val (exit, output) = runProcess(installArgs)
        log?.invoke("⏱️ adb install: ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s")

        if (exit == 0 && !output.contains("Failure [")) {
            log?.invoke("✅ Instalada correctamente")
            return true
        }

        val isVersionError = output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ||
                             output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        if (isVersionError) {
            log?.invoke("⚠️ Versión incompatible detectada")
            val latch = java.util.concurrent.CountDownLatch(1)
            var doUninstall = false
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                doUninstall = Messages.showOkCancelDialog(
                    project,
                    "La versión instalada en el dispositivo es incompatible con esta build.\n\n" +
                    "¿Desinstalar la app del dispositivo e instalar de nuevo?",
                    "Versión incompatible",
                    "Desinstalar e instalar",
                    "Cancelar",
                    Messages.getWarningIcon()
                ) == Messages.OK
                latch.countDown()
            }
            latch.await()
            if (doUninstall) {
                val uninstallArgs = buildList {
                    add(adbPath())
                    if (serial != null) { add("-s"); add(serial) }
                    addAll(listOf("uninstall", packageName))
                }
                log?.invoke("🗑️ Desinstalando $packageName...")
                val (_, uninstallOut) = runProcess(uninstallArgs)
                log?.invoke(uninstallOut.trim())
                log?.invoke("🔄 Reintentando instalación...")
                val (retryExit, retryOut) = runProcess(installArgs)
                if (retryExit == 0 && !retryOut.contains("Failure [")) {
                    log?.invoke("✅ Instalada correctamente")
                    return true
                }
                log?.invoke("❌ Error al reinstalar: $retryOut")
                return false
            }
        } else {
            log?.invoke("❌ Error de instalación: $output")
        }
        return false
    }

    /** Corre los validadores en una pestaña de terminal (async, sin bloquear). */
    fun runValidatorsInTerminal(project: Project) {
        openTerminalAndRun(project, project.basePath, "python buildInrobics.py --run_validators", "Inrobics Validators")
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
                        val isWindows = System.getProperty("os.name").lowercase().contains("win")
                        val cmd = if (isWindows)
                            listOf("cmd", "/c", "python", "buildInrobics.py", "--run_validators")
                        else
                            listOf("python", "buildInrobics.py", "--run_validators")
                        val proc = ProcessBuilder(cmd)
                            .directory(File(basePath))
                            .redirectErrorStream(true)
                            .start()
                        output = proc.inputStream.bufferedReader().readText()
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
