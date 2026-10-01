import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ItemEvent

class BuildInrobicsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val state = InrobicsBuildState.getInstance(project) // Obtenemos el estado actual
        val panel = object : JPanel(GridBagLayout()), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = 16
            override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = 64
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }
        val c = GridBagConstraints()
        c.fill = GridBagConstraints.HORIZONTAL
        c.insets = Insets(5, 10, 5, 10) // Márgenes entre componentes
        c.weightx = 1.0

        // Componentes Visuales
        val flavorCombo = JComboBox(arrayOf("Clinic", "Care", "Virtual", "Educa"))
        flavorCombo.selectedItem = state.flavor // Cargar valor guardado
        flavorCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) state.flavor = it.item as String }

        val buildTypeCombo = JComboBox(arrayOf("Debug", "Release"))
        buildTypeCombo.selectedItem = state.buildType
        buildTypeCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) state.buildType = it.item as String }

        // Migración: valor guardado antiguo "APK (desarrollo)" → nuevo nombre
        if (state.outputFormat == "APK (desarrollo)") state.outputFormat = "APK"
        val outputFormatCombo = JComboBox(arrayOf("Bundle", "APK"))
        outputFormatCombo.selectedItem = state.outputFormat
        outputFormatCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) state.outputFormat = it.item as String }

        val btnValidators = JButton("Ejecutar Validadores 🔍")
        btnValidators.addActionListener { InrobicsCommandRunner.runValidatorsInTerminal(project) }

        val projectPath = project.basePath ?: ""

        fun makeGreenButton(label: String): JButton = object : JButton(label) {
            init {
                foreground = java.awt.Color.WHITE
                isContentAreaFilled = false
                isOpaque = false
                isBorderPainted = false
                isFocusPainted = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = when {
                    model.isPressed  -> java.awt.Color(0x1B5E20)
                    model.isRollover -> java.awt.Color(0x388E3C)
                    else             -> java.awt.Color(0x2E7D32)
                }
                g2.fillRoundRect(0, 0, width, height, 8, 8)
                g2.dispose()
                super.paintComponent(g)
            }
        }

        val logArea = javax.swing.JTextArea(6, 0).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
            background = java.awt.Color(0x1E, 0x1E, 0x1E)
            foreground = java.awt.Color(0xCC, 0xCC, 0xCC)
        }
        fun appendLog(msg: String) {
            val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            javax.swing.SwingUtilities.invokeLater {
                logArea.append("[$time] $msg\n")
                logArea.caretPosition = logArea.document.length
            }
        }

        val btnEjecutar = makeGreenButton("Ejecutar Build 🚀")
        btnEjecutar.addActionListener {
            InrobicsCommandRunner.execute(project, state, ::appendLog)
        }

        // -- Sección: Signed Release --
        fun makeVersionText(v: InrobicsVersionManager.VersionInfo?) =
            if (v != null) "Versión: ${v.name}  (code ${v.code})" else "Versión: no detectada"

        val currentVersion = InrobicsVersionManager.readVersion(projectPath)
        val versionLabel = JLabel(makeVersionText(currentVersion)).apply {
            toolTipText = InrobicsVersionManager.resolveManifestPath(projectPath)
                ?.let { "Leyendo de: $projectPath/$it" }
                ?: "AndroidManifest no encontrado en rutas conocidas (projectPath=$projectPath)"
        }

        val refreshBtn = JButton("↺").apply {
            toolTipText = "Releer versión del AndroidManifest"
            preferredSize = java.awt.Dimension(36, 24)
        }
        refreshBtn.addActionListener {
            val v = InrobicsVersionManager.readVersion(projectPath)
            versionLabel.text = makeVersionText(v)
            versionLabel.toolTipText = InrobicsVersionManager.resolveManifestPath(projectPath)
                ?.let { "Leyendo de: $projectPath/$it" }
                ?: "AndroidManifest no encontrado (projectPath=$projectPath)"
        }

        val versionPanel = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(versionLabel, java.awt.BorderLayout.CENTER)
            add(refreshBtn, java.awt.BorderLayout.EAST)
        }

        // Preview de la versión resultante para sabores no-Clinic (visible solo con auto-increment activo)
        val nonClinicPreviewLabel = JLabel()
        nonClinicPreviewLabel.isVisible = false

        // -- Panel de versión Clinic (solo visible cuando se selecciona Clinic) --
        val prevClinicRecord = InrobicsVersionManager.loadVersionRecord(projectPath, "clinic")
        val prevClinicName = prevClinicRecord?.versionName
            ?: InrobicsVersionManager.readVersion(projectPath)?.name
            ?: ""
        val (prevDate, prevSeq, prevHotfix) = if (prevClinicName.isNotEmpty())
            InrobicsVersionManager.parseClinicVersionName(prevClinicName)
        else Triple("", "01", "a")

        val today = java.time.LocalDate.now().let {
            "${it.year}.${it.monthValue.toString().padStart(2, '0')}.${it.dayOfMonth.toString().padStart(2, '0')}"
        }
        val dateField = JTextField(state.clinicVersionDate.ifEmpty { today })
        val seqField = JTextField(state.clinicVersionSeq.ifEmpty { prevSeq }, 5)
        val hotfixOptions = (listOf("a") + ('b'..'z').map { it.toString() }).toTypedArray()
        val hotfixCombo = JComboBox(hotfixOptions)
        hotfixCombo.selectedItem = state.clinicVersionHotfix.ifEmpty { "a" }
        val previewLabel = JLabel()

        fun updateClinicPreview() {
            val hotfix = hotfixCombo.selectedItem as String
            val name = InrobicsVersionManager.buildClinicVersionName(
                dateField.text.trim(), seqField.text.trim(), hotfix
            )
            val newCode = InrobicsVersionManager.computeNextVersionCode(
                InrobicsVersionManager.readVersion(projectPath)?.code ?: ""
            )
            previewLabel.text = "<html>&nbsp;→ <b>$name</b>&nbsp;&nbsp;<small>(code $newCode)</small></html>"
            state.clinicVersionDate = dateField.text.trim()
            state.clinicVersionSeq = seqField.text.trim()
            state.clinicVersionHotfix = hotfix
        }

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateClinicPreview()
            override fun removeUpdate(e: DocumentEvent) = updateClinicPreview()
            override fun changedUpdate(e: DocumentEvent) = updateClinicPreview()
        }
        dateField.document.addDocumentListener(docListener)
        seqField.document.addDocumentListener(docListener)
        hotfixCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) updateClinicPreview() }

        val clinicVersionPanel = JPanel(GridBagLayout()).apply {
            border = javax.swing.BorderFactory.createTitledBorder("Versión Clinic (dispositivo médico)")
            isOpaque = false
        }
        val cc = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL; insets = Insets(2, 4, 2, 4) }

        cc.gridx = 0; cc.gridy = 0; cc.gridwidth = 2; cc.weightx = 1.0
        clinicVersionPanel.add(JLabel("<html><small>Anterior: <b>${prevClinicName.ifEmpty { "—" }}</b></small></html>"), cc)
        cc.gridwidth = 1

        cc.gridx = 0; cc.gridy = 1; cc.weightx = 0.0
        clinicVersionPanel.add(JLabel("<html><small>Fecha &nbsp;&nbsp;(${prevDate.ifEmpty { "—" }} →)</small></html>"), cc)
        cc.gridx = 1; cc.weightx = 1.0; clinicVersionPanel.add(dateField, cc)

        cc.gridx = 0; cc.gridy = 2; cc.weightx = 0.0
        clinicVersionPanel.add(JLabel("<html><small>Release (${prevSeq.ifEmpty { "—" }} →)</small></html>"), cc)
        cc.gridx = 1; cc.weightx = 1.0; clinicVersionPanel.add(seqField, cc)

        cc.gridx = 0; cc.gridy = 3; cc.weightx = 0.0
        clinicVersionPanel.add(JLabel("<html><small>Hotfix &nbsp;&nbsp;($prevHotfix →)</small></html>"), cc)
        cc.gridx = 1; cc.weightx = 1.0; clinicVersionPanel.add(hotfixCombo, cc)

        cc.gridx = 0; cc.gridy = 4; cc.gridwidth = 2; cc.weightx = 1.0
        clinicVersionPanel.add(previewLabel, cc)

        updateClinicPreview()
        // Refrescar el code del preview de Clinic cuando se releerá la versión del manifest
        refreshBtn.addActionListener { if (clinicVersionPanel.isVisible) updateClinicPreview() }
        clinicVersionPanel.isVisible = state.flavor == "Clinic"

        flavorCombo.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                clinicVersionPanel.isVisible = it.item == "Clinic"
                if (it.item == "Clinic") updateClinicPreview()
                panel.revalidate()
                panel.repaint()
            }
        }

        val autoIncrementCheck = JCheckBox("Auto-increment version al firmar")
        autoIncrementCheck.isSelected = state.isAutoIncrementVersion
        autoIncrementCheck.addActionListener { state.isAutoIncrementVersion = autoIncrementCheck.isSelected }

        fun updateNonClinicPreview() {
            val show = autoIncrementCheck.isSelected && !(flavorCombo.selectedItem as? String).equals("Clinic", ignoreCase = true)
            if (show) {
                val cur = InrobicsVersionManager.readVersion(projectPath)
                val nv = cur?.let { InrobicsVersionManager.computeNextVersionNonClinic(it) }
                nonClinicPreviewLabel.text = if (nv != null)
                    "<html>&nbsp;→ <b>${nv.name}</b>&nbsp;&nbsp;<small>(code ${nv.code})</small></html>"
                else "<html>&nbsp;→ <i>no detectada</i></html>"
            }
            nonClinicPreviewLabel.isVisible = show
            panel.revalidate()
            panel.repaint()
        }
        autoIncrementCheck.addActionListener { updateNonClinicPreview() }
        flavorCombo.addItemListener { if (it.stateChange == ItemEvent.SELECTED) updateNonClinicPreview() }
        updateNonClinicPreview()

        val btnSignedBuild = makeGreenButton("Generar Signed Release 🔏")
        btnSignedBuild.addActionListener {
            val (validatorsPassed, validatorsOutput) = InrobicsCommandRunner.runValidatorsSync(project)
            if (!validatorsPassed) {
                val textArea = javax.swing.JTextArea(validatorsOutput, 20, 70).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
                }
                val choice = javax.swing.JOptionPane.showOptionDialog(
                    null,
                    javax.swing.JScrollPane(textArea),
                    "Validadores fallidos",
                    javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.ERROR_MESSAGE,
                    null,
                    arrayOf("Cancelar", "Bypass ⚠️"),
                    "Cancelar"
                )
                if (choice != 1) return@addActionListener  // 1 = Bypass
            }
            if (state.isAutoIncrementVersion) {
                val current = InrobicsVersionManager.readVersion(projectPath)
                val newVer = if (state.flavor.equals("Clinic", ignoreCase = true)) {
                    InrobicsVersionManager.buildClinicVersion(
                        current?.code ?: "", state.clinicVersionDate,
                        state.clinicVersionSeq, state.clinicVersionHotfix
                    )
                } else {
                    current?.let { InrobicsVersionManager.computeNextVersionNonClinic(it) }
                }
                if (newVer != null && current != null) {
                    InrobicsVersionManager.writeVersion(projectPath, current, newVer)
                    InrobicsVersionManager.saveVersionRecord(projectPath, state.flavor,
                        InrobicsVersionManager.VersionRecord(newVer.name, newVer.code))
                    versionLabel.text = makeVersionText(newVer)
                    updateNonClinicPreview()
                    if (clinicVersionPanel.isVisible) updateClinicPreview()
                }
            }
            SignedBuildRunner.execute(project, state)
        }

        val btnOpenVersions = JButton("📋 Versiones guardadas")
        btnOpenVersions.toolTipText = "Abre .inrobics_versions.json en el editor"
        btnOpenVersions.addActionListener {
            val file = java.io.File("$projectPath/.inrobics_versions.json")
            if (!file.exists()) {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "El archivo .inrobics_versions.json aún no existe.\nGenera una firma con auto-increment activado para crearlo.",
                    "Sin versiones guardadas"
                )
            } else {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (vf != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }

        // Posicionamiento en la cuadrícula (GridBagLayout para que se adapte al ancho del panel)
        c.gridy = 0; panel.add(JLabel("Flavor:"), c)
        c.gridy = 1; panel.add(flavorCombo, c)

        c.gridy = 2; panel.add(JLabel("Build Type:"), c)
        c.gridy = 3; panel.add(buildTypeCombo, c)

        c.gridy = 4; panel.add(JLabel("Output Format:"), c)
        c.gridy = 5; panel.add(outputFormatCombo, c)

        val profileCheck = JCheckBox("Benchmark tasks (--profile)")
        profileCheck.isSelected = state.profileBuild
        profileCheck.addActionListener { state.profileBuild = profileCheck.isSelected }
        c.gridy = 6; c.insets = Insets(0, 10, 2, 10)
        panel.add(profileCheck, c)

        c.gridy = 7; c.insets = Insets(5, 10, 5, 10)
        panel.add(btnValidators, c)

        c.gridy = 8; c.insets = Insets(10, 10, 5, 10)
        panel.add(btnEjecutar, c)

        c.gridy = 9; c.insets = Insets(15, 10, 5, 10)
        panel.add(JSeparator(), c)

        c.gridy = 10; c.insets = Insets(5, 10, 2, 10)
        panel.add(versionPanel, c)

        c.gridy = 11; c.insets = Insets(0, 10, 2, 10)
        panel.add(nonClinicPreviewLabel, c)

        c.gridy = 12; c.insets = Insets(2, 6, 2, 6)
        panel.add(clinicVersionPanel, c)

        c.gridy = 13; c.insets = Insets(2, 10, 2, 10)
        panel.add(autoIncrementCheck, c)

        c.gridy = 15; c.insets = Insets(10, 10, 5, 10)
        panel.add(btnSignedBuild, c)

        c.gridy = 16; c.insets = Insets(2, 10, 5, 10)
        panel.add(btnOpenVersions, c)

        c.gridy = 17; c.insets = Insets(10, 10, 2, 10)
        panel.add(JLabel("Asset log:"), c)

        c.gridy = 18; c.insets = Insets(0, 6, 6, 6)
        val logScroll = JScrollPane(logArea).apply {
            border = javax.swing.BorderFactory.createLineBorder(java.awt.Color(0x55, 0x55, 0x55))
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        panel.add(logScroll, c)

        // Espaciador invisible al final para empujar todo hacia arriba
        c.gridy = 20; c.weighty = 1.0; c.insets = Insets(0, 0, 0, 0)
        panel.add(JPanel(), c)

        // Añadir el panel al contenedor oficial de la Tool Window (con scroll)
        val scrollPane = JScrollPane(panel).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(scrollPane, "", false)
        toolWindow.contentManager.addContent(content)
    }
}