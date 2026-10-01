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

        val btnValidators = JButton("Ejecutar Validadores 🔍")

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

        val validatorsStatus = JLabel()
        fun refreshValidatorsStatus() {
            validatorsStatus.text = if (state.lastValidatorsTime.isEmpty()) "Sin ejecuciones de validadores"
                else "Última ejecución: ${state.lastValidatorsTime} · ${if (state.lastValidatorsPassed) "PASÓ" else "FALLÓ"}"
            validatorsStatus.foreground = if (state.lastValidatorsTime.isEmpty()) UIManager.getColor("Label.foreground")
                else if (state.lastValidatorsPassed) java.awt.Color(0x388E3C) else java.awt.Color(0xE53935)
        }
        fun recordValidatorsResult(passed: Boolean, time: String) {
            state.lastValidatorsTime = time
            state.lastValidatorsPassed = passed
            refreshValidatorsStatus()
            appendLog("Validadores: ${if (passed) "PASÓ" else "FALLÓ"} · $time")
        }
        refreshValidatorsStatus()
        btnValidators.addActionListener {
            InrobicsCommandRunner.runValidatorsInTerminal(project, ::recordValidatorsResult)
        }

        // -- Sección: Signed Release --
        fun makeVersionText(v: InrobicsVersionManager.VersionInfo?) =
            if (v != null) "Versión: ${v.name}  (code ${v.code})" else "Versión: no detectada"

        val currentVersion = InrobicsVersionManager.readVersion(projectPath, state.flavor)
        val versionLabel = JLabel(makeVersionText(currentVersion))
        fun refreshVersion() {
            versionLabel.text = makeVersionText(InrobicsVersionManager.readVersion(projectPath, state.flavor))
            versionLabel.toolTipText = InrobicsVersionManager.resolveVersionPath(projectPath, state.flavor)
                ?.let { "Leyendo de: $it" } ?: "Version no encontrada"
        }
        refreshVersion()
        val refreshBtn = JButton("\u21ba").apply {
            toolTipText = "Releer version del flavor seleccionado"
            preferredSize = java.awt.Dimension(36, 24)
        }
        refreshBtn.addActionListener { refreshVersion() }

        val versionPanel = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(versionLabel, java.awt.BorderLayout.CENTER)
            add(refreshBtn, java.awt.BorderLayout.EAST)
        }

        // -- Panel de versión Clinic (solo visible cuando se selecciona Clinic) --
        val prevClinicRecord = InrobicsVersionManager.loadVersionRecord(projectPath, "clinic")
        val prevClinicName = InrobicsVersionManager.readVersion(projectPath, "Clinic")?.name
            ?: prevClinicRecord?.versionName
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
                InrobicsVersionManager.readVersion(projectPath, "Clinic")?.code ?: ""
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

        fun selectedDate(): String = when (state.flavor.lowercase()) {
            "care" -> state.careVersionDate
            "educa" -> state.educaVersionDate
            else -> state.virtualVersionDate
        }
        val versionDateField = JTextField(selectedDate().ifEmpty { today })
        val datePreviousLabel = JLabel()
        val dateFieldLabel = JLabel()
        val datePreview = JLabel()
        val dateVersionPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Versi\u00f3n Virtual")
            isOpaque = false
        }
        val vc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 4, 2, 4)
        }
        vc.gridx = 0; vc.gridy = 0; vc.gridwidth = 2; vc.weightx = 1.0
        dateVersionPanel.add(datePreviousLabel, vc)
        vc.gridwidth = 1
        vc.gridx = 0; vc.gridy = 1; vc.weightx = 0.0
        dateVersionPanel.add(dateFieldLabel, vc)
        vc.gridx = 1; vc.weightx = 1.0; dateVersionPanel.add(versionDateField, vc)
        vc.gridx = 0; vc.gridy = 2; vc.gridwidth = 2; vc.weightx = 1.0
        dateVersionPanel.add(datePreview, vc)

        fun updateDatePreview() {
            dateVersionPanel.isVisible = !state.flavor.equals("Clinic", true)
            dateVersionPanel.border = BorderFactory.createTitledBorder("Versi\u00f3n ${state.flavor}")
            val date = versionDateField.text.trim()
            when (state.flavor.lowercase()) {
                "virtual" -> state.virtualVersionDate = date
                "care" -> state.careVersionDate = date
                "educa" -> state.educaVersionDate = date
            }
            val current = InrobicsVersionManager.readVersion(projectPath, state.flavor)
            datePreviousLabel.text = "<html><small>Anterior: <b>${current?.name ?: "\u2014"}</b></small></html>"
            dateFieldLabel.text = "<html><small>Fecha (${current?.name?.substringBefore('-') ?: "\u2014"} \u2192)</small></html>"
            val next = current?.let { InrobicsVersionManager.buildVirtualVersion(it,
                date, state.isAutoIncrementVersion) }
            datePreview.text = next?.let {
                "<html>&nbsp;\u2192 <b>${it.name}</b>&nbsp;&nbsp;<small>(code ${it.code})</small></html>"
            } ?: "Fecha YYYY.MM.DD requerida"
            panel.revalidate()
            panel.repaint()
        }
        val dateListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateDatePreview()
            override fun removeUpdate(e: DocumentEvent) = updateDatePreview()
            override fun changedUpdate(e: DocumentEvent) = updateDatePreview()
        }
        versionDateField.document.addDocumentListener(dateListener)
        refreshBtn.addActionListener { updateDatePreview() }
        updateDatePreview()

        autoIncrementCheck.addActionListener {
            state.isAutoIncrementVersion = autoIncrementCheck.isSelected
            updateDatePreview()
        }
        flavorCombo.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                state.flavor = it.item as String
                refreshVersion()
                versionDateField.text = selectedDate().ifEmpty { today }
                updateDatePreview()
            }
        }

        val btnSignedBuild = makeGreenButton("Generar build firmada 🔏")
        btnSignedBuild.addActionListener {
            val dateVersion = if (!state.flavor.equals("Clinic", true)) {
                val current = InrobicsVersionManager.readVersion(projectPath, state.flavor)
                val next = current?.let { InrobicsVersionManager.buildVirtualVersion(it, versionDateField.text.trim(), state.isAutoIncrementVersion) }
                if (next == null) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(project,
                        "Introduce una fecha YYYY.MM.DD válida y comprueba la versión del flavor.", "Versión ${state.flavor}")
                    return@addActionListener
                }
                Pair(current, next)
            } else null
            val (validatorsPassed, validatorsOutput) = InrobicsCommandRunner.runValidatorsSync(project)
            recordValidatorsResult(validatorsPassed, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")))
            appendLog(validatorsOutput)
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
            if (state.isAutoIncrementVersion && state.flavor.equals("Clinic", true)) {
                val current = InrobicsVersionManager.readVersion(projectPath, state.flavor)
                val newVer = InrobicsVersionManager.buildClinicVersion(
                    current?.code ?: "", state.clinicVersionDate,
                    state.clinicVersionSeq, state.clinicVersionHotfix
                )
                if (current != null) {
                    InrobicsVersionManager.writeVersion(projectPath, current, newVer, state.flavor)
                    InrobicsVersionManager.saveVersionRecord(projectPath, state.flavor,
                        InrobicsVersionManager.VersionRecord(newVer.name, newVer.code))
                    versionLabel.text = makeVersionText(newVer)
                    if (clinicVersionPanel.isVisible) updateClinicPreview()
                }
            }
            if (dateVersion != null) {
                val (current, next) = dateVersion
                InrobicsVersionManager.writeVersion(projectPath, current, next, state.flavor)
                InrobicsVersionManager.saveVersionRecord(projectPath, state.flavor,
                    InrobicsVersionManager.VersionRecord(next.name, next.code))
                versionLabel.text = makeVersionText(next)
                updateDatePreview()
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

        val unityPanel = UnityBuildPanel(project, { state.flavor }, ::appendLog)
        flavorCombo.addItemListener {
            if (it.stateChange == ItemEvent.SELECTED) {
                javax.swing.SwingUtilities.invokeLater { unityPanel.refresh() }
            }
        }
        // Descargas Unity separadas del flujo de firma.
        var row = 0
        fun addRow(component: JComponent) {
            c.gridy = row++
            panel.add(component, c)
        }
        addRow(unityPanel)
        addRow(JSeparator())
        addRow(JLabel("Build firmada"))
        addRow(JLabel("Flavor:"))
        addRow(flavorCombo)
        addRow(versionPanel)
        addRow(clinicVersionPanel)
        addRow(dateVersionPanel)
        addRow(autoIncrementCheck)
        addRow(btnValidators)
        addRow(btnSignedBuild)
        addRow(btnOpenVersions)
        addRow(JLabel("Validadores:"))
        addRow(validatorsStatus)
        addRow(JScrollPane(logArea).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        })

        // Espaciador invisible al final para empujar todo hacia arriba
        c.gridy = row; c.weighty = 1.0; c.insets = Insets(0, 0, 0, 0)
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
