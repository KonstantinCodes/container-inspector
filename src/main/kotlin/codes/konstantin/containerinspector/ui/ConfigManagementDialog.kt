package codes.konstantin.containerinspector.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import codes.konstantin.containerinspector.model.Config
import codes.konstantin.containerinspector.model.ServiceGroup
import codes.konstantin.containerinspector.settings.SymfonyContainerSettings
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog for managing configuration profiles
 */
class ConfigManagementDialog(
    private val project: Project,
    private val onConfigChanged: (Config) -> Unit
) : DialogWrapper(project) {

    private val settings = SymfonyContainerSettings.getInstance(project)
    private val configs = settings.getAllConfigs().toMutableList()
    private var selectedConfig: Config? = settings.getActiveConfig()

    private val configListModel = DefaultListModel<String>()
    private val configList = JBList(configListModel)

    // Current config being edited
    private var editingConfig: Config? = selectedConfig

    // UI state variables
    private var configName = editingConfig?.name ?: ""
    private var compiledContainerXmlPath = editingConfig?.compiledContainerXmlPath ?: ""
    private var appClassNameRegex = editingConfig?.appClassNameRegex ?: "^App\\\\.*"
    private var appServiceIdRegex = editingConfig?.appServiceIdRegex ?: "^app\\..*"
    private var minifyNonApp = editingConfig?.minifyNonApp ?: false
    private var minifyClassNameRegex = editingConfig?.minifyClassNameRegex?.joinToString("\n") ?: ""
    private var minifyClassNames = editingConfig?.minifyClassNames?.joinToString("\n") ?: ""
    private var minifyServiceIdRegex = editingConfig?.minifyServiceIdRegex?.joinToString("\n") ?: ""
    private var minifyServiceIds = editingConfig?.minifyServiceIds?.joinToString("\n") ?: ""
    private var neverMinifyClassNames = editingConfig?.neverMinifyClassNames?.joinToString("\n") ?: ""
    private var neverMinifyServiceIds = editingConfig?.neverMinifyServiceIds?.joinToString("\n") ?: ""
    private var persistToFile = editingConfig?.persistToFile ?: false

    // Groups data
    private val groupsListModel = DefaultListModel<String>()
    private val groupsList = mutableListOf<ServiceGroup>()
    private lateinit var groupsListComponent: JBList<String>

    // Store UI components for manual updates
    private lateinit var nameTextField: JTextField
    private lateinit var compiledContainerXmlPathTextField: JTextField
    private lateinit var appClassNameRegexTextField: JTextField
    private lateinit var appServiceIdRegexTextField: JTextField
    private lateinit var persistToFileCheckBox: JCheckBox
    private lateinit var minifyNonAppCheckBox: JCheckBox
    private lateinit var minifyClassNameRegexTextArea: JTextArea
    private lateinit var minifyClassNamesTextArea: JTextArea
    private lateinit var minifyServiceIdRegexTextArea: JTextArea
    private lateinit var minifyServiceIdsTextArea: JTextArea
    private lateinit var neverMinifyClassNamesTextArea: JTextArea
    private lateinit var neverMinifyServiceIdsTextArea: JTextArea

    init {
        title = "Configuration Profiles"
        loadGroups()
        refreshConfigList()
        setupListListener()
        init()

        // After UI is created, load the selected config into the editor
        selectedConfig?.let { loadConfigIntoEditor(it) }
    }

    override fun createCenterPanel(): JComponent? {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(900, 600)

        // Left side - config list with toolbar
        val leftPanel = JPanel(BorderLayout())
        leftPanel.preferredSize = Dimension(250, 600)

        // Toolbar with icon buttons (PHPStorm style)
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar(
                "ConfigManagement",
                com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
                    add(object : com.intellij.openapi.actionSystem.AnAction("Add Configuration", "Create new configuration", com.intellij.icons.AllIcons.General.Add) {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            createNewConfig()
                        }
                    })
                    add(object : com.intellij.openapi.actionSystem.AnAction("Remove Configuration", "Delete selected configuration", com.intellij.icons.AllIcons.General.Remove) {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            deleteConfig()
                        }
                        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            e.presentation.isEnabled = selectedConfig != null
                        }
                    })
                    add(object : com.intellij.openapi.actionSystem.AnAction("Copy Configuration", "Duplicate selected configuration", com.intellij.icons.AllIcons.Actions.Copy) {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            duplicateConfig()
                        }
                        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            e.presentation.isEnabled = selectedConfig != null
                        }
                    })
                },
                true
            )

        val scrollPane = com.intellij.ui.components.JBScrollPane(configList)
        leftPanel.add(toolbar.component, BorderLayout.NORTH)
        leftPanel.add(scrollPane, BorderLayout.CENTER)

        // Right side - config editor with tabs
        val tabbedPane = JBTabbedPane()

        // Tab 1: General
        val generalTabPanel = panel {
            group("Configuration Name") {
                row("Name:") {
                    nameTextField = textField()
                        .bindText(::configName)
                        .columns(COLUMNS_LARGE)
                        .comment("Name of this configuration profile")
                        .component
                }
            }

            group("Container XML Path") {
                row("Container XML:") {
                    compiledContainerXmlPathTextField = textField()
                        .bindText(::compiledContainerXmlPath)
                        .columns(COLUMNS_LARGE)
                        .comment("Path to compiled container XML (e.g., var/cache/dev/App_KernelDevDebugContainer.xml)")
                        .component
                    button("Browse...") {
                        val fileChooser = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                        fileChooser.title = "Select Container XML File"
                        val projectBasePath = project.basePath ?: ""
                        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(projectBasePath)
                        val chosenFile = com.intellij.openapi.fileChooser.FileChooser.chooseFile(fileChooser, project, baseDir)
                        if (chosenFile != null) {
                            // Make path relative to project root if possible
                            val relativePath = if (chosenFile.path.startsWith(projectBasePath)) {
                                chosenFile.path.substring(projectBasePath.length).trimStart('/')
                            } else {
                                chosenFile.path
                            }
                            compiledContainerXmlPathTextField.text = relativePath
                            compiledContainerXmlPath = relativePath
                        }
                    }
                }
            }

            group("Sharing") {
                row {
                    persistToFileCheckBox = checkBox("Persist to project file (.container-inspector.yaml)")
                        .bindSelected(::persistToFile)
                        .comment("Save this config to a file in the project root for team sharing")
                        .component
                }
            }

            group("App Service Patterns") {
                row {
                    comment("Define regex patterns to identify your application services")
                }
                row("Class Name Regex:") {
                    appClassNameRegexTextField = textField()
                        .bindText(::appClassNameRegex)
                        .columns(COLUMNS_LARGE)
                        .comment("e.g., ^App\\\\.*")
                        .component
                }
                row("Service ID Regex:") {
                    appServiceIdRegexTextField = textField()
                        .bindText(::appServiceIdRegex)
                        .columns(COLUMNS_LARGE)
                        .comment("e.g., ^app\\..*")
                        .component
                }
            }
        }
        tabbedPane.addTab("General", generalTabPanel)

        // Tab 2: Minify
        val minifyTab = createMinifyTab()
        tabbedPane.addTab("Minify", minifyTab)

        // Tab 3: Groups
        val groupsTab = createGroupsTab()
        tabbedPane.addTab("Groups", groupsTab)

        mainPanel.add(leftPanel, BorderLayout.WEST)
        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createMinifyTab(): JComponent {
        return panel {
            group("Minify Rules") {
                row {
                    minifyNonAppCheckBox = checkBox("Minify non-app services")
                        .bindSelected(::minifyNonApp)
                        .comment("Automatically minify services that don't match app service patterns")
                        .component
                }

                row {
                    label("Class Name Regex (one per line):")
                        .bold()
                }
                row {
                    minifyClassNameRegexTextArea = textArea()
                        .bindText(::minifyClassNameRegex)
                        .rows(4)
                        .columns(COLUMNS_LARGE)
                        .comment("Regular expressions to match class names for minification")
                        .component
                }

                row {
                    label("Class Names (one per line):")
                        .bold()
                }
                row {
                    minifyClassNamesTextArea = textArea()
                        .bindText(::minifyClassNames)
                        .rows(4)
                        .columns(COLUMNS_LARGE)
                        .comment("Exact class names to minify")
                        .component
                }

                row {
                    label("Service ID Regex (one per line):")
                        .bold()
                }
                row {
                    minifyServiceIdRegexTextArea = textArea()
                        .bindText(::minifyServiceIdRegex)
                        .rows(4)
                        .columns(COLUMNS_LARGE)
                        .comment("Regular expressions to match service IDs for minification")
                        .component
                }

                row {
                    label("Service IDs (one per line):")
                        .bold()
                }
                row {
                    minifyServiceIdsTextArea = textArea()
                        .bindText(::minifyServiceIds)
                        .rows(4)
                        .columns(COLUMNS_LARGE)
                        .comment("Exact service IDs to minify")
                        .component
                }
            }

            group("Never Minify") {
                row {
                    label("Never Minify Class Names (one per line):")
                        .bold()
                }
                row {
                    neverMinifyClassNamesTextArea = textArea()
                        .bindText(::neverMinifyClassNames)
                        .rows(4)
                        .columns(COLUMNS_LARGE)
                        .comment("Class names that should never be minified")
                        .component
                }

                row {
                    label("Never Minify Service IDs (one per line):")
                        .bold()
                }
                row {
                    neverMinifyServiceIdsTextArea = textArea()
                        .bindText(::neverMinifyServiceIds)
                        .rows(4)
                        .columns(COLUMNS_LARGE)
                        .comment("Service IDs that should never be minified")
                        .component
                }
            }
        }
    }

    private fun createGroupsTab(): JComponent {
        // Groups editor with modern UI and toolbar
        groupsListComponent = JBList(groupsListModel)

        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar(
                "GroupsManagement",
                com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
                    add(object : com.intellij.openapi.actionSystem.AnAction("Add Group", "Create new group", com.intellij.icons.AllIcons.General.Add) {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            addGroup()
                        }
                    })
                    add(object : com.intellij.openapi.actionSystem.AnAction("Remove Group", "Delete selected group", com.intellij.icons.AllIcons.General.Remove) {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            deleteGroup()
                        }
                        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            e.presentation.isEnabled = groupsListComponent.selectedIndex >= 0
                        }
                    })
                    add(object : com.intellij.openapi.actionSystem.AnAction("Edit Group", "Edit selected group", com.intellij.icons.AllIcons.Actions.Edit) {
                        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            editGroup()
                        }
                        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                            e.presentation.isEnabled = groupsListComponent.selectedIndex >= 0
                        }
                    })
                },
                true
            )
        toolbar.targetComponent = groupsListComponent

        val panel = JPanel(BorderLayout())
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(JScrollPane(groupsListComponent), BorderLayout.CENTER)

        return panel {
            row {
                comment("Define groups to visually organize related services in the graph")
            }
            row {
                cell(panel)
                    .align(Align.FILL)
            }.resizableRow()
        }
    }

    private fun refreshConfigList() {
        configListModel.clear()
        configs.forEach { config ->
            configListModel.addElement(config.name)
        }

        // Select current config
        val index = configs.indexOfFirst { it.name == selectedConfig?.name }
        if (index >= 0) {
            configList.selectedIndex = index
        }
    }

    private fun setupListListener() {
        configList.addListSelectionListener {
            if (!it.valueIsAdjusting && configList.selectedIndex >= 0) {
                saveCurrentConfigState()
                selectedConfig = configs[configList.selectedIndex]
                loadConfigIntoEditor(selectedConfig!!)
            }
        }
    }

    private fun saveCurrentConfigState() {
        if (selectedConfig == null) {
            println("DEBUG saveCurrentConfigState: Skipping (selectedConfig is null)")
            return
        }

        // Read values from UI components, not from property variables
        // because the property variables are only updated by user typing, not by programmatic changes
        if (!::nameTextField.isInitialized) {
            println("DEBUG saveCurrentConfigState: UI not initialized yet, skipping")
            return
        }

        val currentName = nameTextField.text
        println("DEBUG saveCurrentConfigState: Saving config. selectedConfig.name=${selectedConfig!!.name}, currentName=$currentName")
        println("DEBUG saveCurrentConfigState: persistToFileCheckBox.isSelected=${persistToFileCheckBox.isSelected}")
        println("DEBUG saveCurrentConfigState: minifyNonAppCheckBox.isSelected=${minifyNonAppCheckBox.isSelected}")

        val updatedConfig = Config(
            name = currentName,
            compiledContainerXmlPath = compiledContainerXmlPathTextField.text,
            appClassNameRegex = appClassNameRegexTextField.text,
            appServiceIdRegex = appServiceIdRegexTextField.text,
            minifyNonApp = minifyNonAppCheckBox.isSelected,
            minifyClassNameRegex = minifyClassNameRegexTextArea.text.split("\n").filter { it.isNotBlank() },
            minifyClassNames = minifyClassNamesTextArea.text.split("\n").filter { it.isNotBlank() },
            minifyServiceIdRegex = minifyServiceIdRegexTextArea.text.split("\n").filter { it.isNotBlank() },
            minifyServiceIds = minifyServiceIdsTextArea.text.split("\n").filter { it.isNotBlank() },
            neverMinifyClassNames = neverMinifyClassNamesTextArea.text.split("\n").filter { it.isNotBlank() },
            neverMinifyServiceIds = neverMinifyServiceIdsTextArea.text.split("\n").filter { it.isNotBlank() },
            groups = groupsList.toList(),
            persistToFile = persistToFileCheckBox.isSelected
        )

        println("DEBUG saveCurrentConfigState: updatedConfig.name=${updatedConfig.name}, updatedConfig.persistToFile=${updatedConfig.persistToFile}, updatedConfig.minifyNonApp=${updatedConfig.minifyNonApp}")

        val index = configs.indexOfFirst { it.name == selectedConfig!!.name }
        println("DEBUG saveCurrentConfigState: Found index=$index in configs list")
        if (index >= 0) {
            println("DEBUG saveCurrentConfigState: Replacing configs[$index] (was ${configs[index].name}) with ${updatedConfig.name}")
            configs[index] = updatedConfig
        }
    }

    private fun loadConfigIntoEditor(config: Config) {
        println("DEBUG: loadConfigIntoEditor called for config: ${config.name}")
        editingConfig = config
        configName = config.name
        compiledContainerXmlPath = config.compiledContainerXmlPath
        appClassNameRegex = config.appClassNameRegex
        appServiceIdRegex = config.appServiceIdRegex
        minifyNonApp = config.minifyNonApp
        minifyClassNameRegex = config.minifyClassNameRegex.joinToString("\n")
        minifyClassNames = config.minifyClassNames.joinToString("\n")
        minifyServiceIdRegex = config.minifyServiceIdRegex.joinToString("\n")
        minifyServiceIds = config.minifyServiceIds.joinToString("\n")
        neverMinifyClassNames = config.neverMinifyClassNames.joinToString("\n")
        neverMinifyServiceIds = config.neverMinifyServiceIds.joinToString("\n")
        persistToFile = config.persistToFile
        println("DEBUG: Set configName to: $configName, compiledContainerXmlPath to: $compiledContainerXmlPath")
        loadGroupsFromConfig(config.groups)

        // Manually update UI components
        if (::nameTextField.isInitialized) {
            nameTextField.text = config.name
            compiledContainerXmlPathTextField.text = config.compiledContainerXmlPath
            appClassNameRegexTextField.text = config.appClassNameRegex
            appServiceIdRegexTextField.text = config.appServiceIdRegex
            persistToFileCheckBox.isSelected = config.persistToFile
            minifyNonAppCheckBox.isSelected = config.minifyNonApp
            minifyClassNameRegexTextArea.text = config.minifyClassNameRegex.joinToString("\n")
            minifyClassNamesTextArea.text = config.minifyClassNames.joinToString("\n")
            minifyServiceIdRegexTextArea.text = config.minifyServiceIdRegex.joinToString("\n")
            minifyServiceIdsTextArea.text = config.minifyServiceIds.joinToString("\n")
            neverMinifyClassNamesTextArea.text = config.neverMinifyClassNames.joinToString("\n")
            neverMinifyServiceIdsTextArea.text = config.neverMinifyServiceIds.joinToString("\n")
        }
    }

    private fun createNewConfig() {
        val name = JOptionPane.showInputDialog(contentPane, "Enter config name:", "New Config", JOptionPane.PLAIN_MESSAGE)
        if (!name.isNullOrBlank()) {
            println("DEBUG createNewConfig: Creating new config with name: ${name.trim()}")
            println("DEBUG createNewConfig: Before save - configs.size=${configs.size}, configs=${configs.map { it.name }}")

            // Save current config before creating new one
            saveCurrentConfigState()

            println("DEBUG createNewConfig: After save - configs.size=${configs.size}, configs=${configs.map { it.name }}")

            val newConfig = Config(name = name.trim())
            configs.add(newConfig)
            println("DEBUG createNewConfig: After add - configs.size=${configs.size}, configs=${configs.map { it.name }}")

            selectedConfig = newConfig

            // Load the new config into editor FIRST before refreshing list
            // This ensures text fields have correct values before any list selection events fire
            loadConfigIntoEditor(newConfig)

            refreshConfigList()
            configList.selectedIndex = configs.size - 1
        }
    }

    private fun duplicateConfig() {
        if (selectedConfig == null) return

        val name = JOptionPane.showInputDialog(contentPane, "Enter new config name:", "Duplicate Config", JOptionPane.PLAIN_MESSAGE)
        if (!name.isNullOrBlank()) {
            saveCurrentConfigState()
            val duplicated = selectedConfig!!.copy(name = name.trim())
            configs.add(duplicated)
            selectedConfig = duplicated

            // Load the duplicated config into editor FIRST before refreshing list
            loadConfigIntoEditor(duplicated)

            refreshConfigList()
            configList.selectedIndex = configs.size - 1
        }
    }

    private fun deleteConfig() {
        val selectedIndex = configList.selectedIndex
        if (selectedIndex < 0 || selectedIndex >= configs.size) return

        val configToDelete = configs[selectedIndex]

        val result = JOptionPane.showConfirmDialog(
            contentPane,
            "Delete config '${configToDelete.name}'?",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION
        )

        if (result == JOptionPane.YES_OPTION) {
            val deletedConfigName = configToDelete.name
            val currentActiveConfigName = settings.activeConfigName

            // Remove the config at the selected index
            configs.removeAt(selectedIndex)

            // Determine which config should be selected next
            selectedConfig = if (configs.isNotEmpty()) {
                // Select the config at the same index, or the last one if we deleted the last config
                val newIndex = minOf(selectedIndex, configs.size - 1)
                configs[newIndex]
            } else {
                null
            }

            // Load the new selected config into the editor
            selectedConfig?.let { loadConfigIntoEditor(it) }

            refreshConfigList()
        }
    }

    private fun loadGroups() {
        groupsList.clear()
        groupsListModel.clear()

        editingConfig?.groups?.forEach { group ->
            groupsList.add(group)
            groupsListModel.addElement(group.name)
        }
    }

    private fun loadGroupsFromConfig(groups: List<ServiceGroup>) {
        groupsList.clear()
        groupsListModel.clear()

        groups.forEach { group ->
            groupsList.add(group)
            groupsListModel.addElement(group.name)
        }
    }

    private fun addGroup() {
        // Show group editor dialog
        val dialog = GroupEditorDialog(project, null) { group ->
            println("DEBUG addGroup: Adding group: ${group.name}")
            if (group.name.isBlank()) {
                println("DEBUG addGroup: Group name is blank, skipping")
                return@GroupEditorDialog
            }
            groupsList.add(group)
            groupsListModel.addElement(group.name)
            println("DEBUG addGroup: Groups list size: ${groupsList.size}, model size: ${groupsListModel.size()}")
        }
        dialog.show()
    }

    private fun editGroup() {
        val selectedIndex = groupsListComponent.selectedIndex
        if (selectedIndex < 0 || selectedIndex >= groupsList.size) {
            println("DEBUG editGroup: No group selected or invalid index")
            return
        }

        val group = groupsList[selectedIndex]
        val dialog = GroupEditorDialog(project, group) { updatedGroup ->
            println("DEBUG editGroup: Updating group at index $selectedIndex: ${updatedGroup.name}")
            groupsList[selectedIndex] = updatedGroup
            groupsListModel.set(selectedIndex, updatedGroup.name)
        }
        dialog.show()
    }

    private fun deleteGroup() {
        val selectedIndex = groupsListComponent.selectedIndex
        if (selectedIndex < 0 || selectedIndex >= groupsList.size) {
            println("DEBUG deleteGroup: No group selected or invalid index")
            return
        }

        println("DEBUG deleteGroup: Removing group at index $selectedIndex")
        groupsList.removeAt(selectedIndex)
        groupsListModel.remove(selectedIndex)
        println("DEBUG deleteGroup: Groups list size: ${groupsList.size}, model size: ${groupsListModel.size()}")
    }

    override fun doOKAction() {
        // Save current state before closing
        println("DEBUG doOKAction: About to call saveCurrentConfigState()")
        saveCurrentConfigState()
        println("DEBUG doOKAction: After saveCurrentConfigState()")

        println("DEBUG doOKAction: Saving configs, count: ${configs.size}")
        configs.forEach {
            println("DEBUG doOKAction: Config: ${it.name}, persistToFile: ${it.persistToFile}, minifyNonApp: ${it.minifyNonApp}")
        }

        // Save all configs to settings
        settings.saveConfigs(configs)
        settings.activeConfigName = selectedConfig?.name ?: ""

        println("DEBUG: After save, activeConfigName: ${settings.activeConfigName}")
        println("DEBUG: getAllConfigs after save: ${settings.getAllConfigs().map { it.name }}")

        // Notify callback with selected config
        selectedConfig?.let { onConfigChanged(it) }

        super.doOKAction()
    }

    /**
     * Simple group editor dialog
     */
    private class GroupEditorDialog(
        private val project: Project,
        private val existingGroup: ServiceGroup?,
        private val onSave: (ServiceGroup) -> Unit
    ) : DialogWrapper(project) {

        private var groupName = existingGroup?.name ?: ""
        private var serviceIds = existingGroup?.serviceIds?.joinToString("\n") ?: ""
        private var classNames = existingGroup?.classNames?.joinToString("\n") ?: ""
        private var serviceIdRegex = existingGroup?.serviceIdRegex?.joinToString("\n") ?: ""
        private var classNameRegex = existingGroup?.classNameRegex?.joinToString("\n") ?: ""

        // Store UI components
        private lateinit var groupNameTextField: JTextField
        private lateinit var serviceIdsTextArea: JTextArea
        private lateinit var classNamesTextArea: JTextArea
        private lateinit var serviceIdRegexTextArea: JTextArea
        private lateinit var classNameRegexTextArea: JTextArea

        init {
            title = if (existingGroup == null) "Add Group" else "Edit Group"
            init()
        }

        override fun createCenterPanel(): JComponent {
            return panel {
                row("Group Name:") {
                    groupNameTextField = textField()
                        .bindText(::groupName)
                        .columns(COLUMNS_LARGE)
                        .component
                }

                group("Match Services") {
                    row("Service IDs (one per line):") {}
                    row {
                        serviceIdsTextArea = textArea()
                            .bindText(::serviceIds)
                            .rows(4)
                            .columns(COLUMNS_LARGE)
                            .component
                    }

                    row("Class Names (one per line):") {}
                    row {
                        classNamesTextArea = textArea()
                            .bindText(::classNames)
                            .rows(4)
                            .columns(COLUMNS_LARGE)
                            .component
                    }

                    row("Service ID Regex (one per line):") {}
                    row {
                        serviceIdRegexTextArea = textArea()
                            .bindText(::serviceIdRegex)
                            .rows(3)
                            .columns(COLUMNS_LARGE)
                            .component
                    }

                    row("Class Name Regex (one per line):") {}
                    row {
                        classNameRegexTextArea = textArea()
                            .bindText(::classNameRegex)
                            .rows(3)
                            .columns(COLUMNS_LARGE)
                            .component
                    }
                }
            }
        }

        override fun doOKAction() {
            // Read values directly from UI components
            val nameFromField = groupNameTextField.text
            println("DEBUG GroupEditorDialog.doOKAction: nameFromField='$nameFromField', groupName property='$groupName'")

            if (nameFromField.isBlank()) {
                println("DEBUG GroupEditorDialog.doOKAction: Group name is blank, showing error")
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Group name cannot be empty",
                    "Invalid Group Name"
                )
                return
            }

            val group = ServiceGroup(
                name = nameFromField.trim(),
                serviceIds = serviceIdsTextArea.text.split("\n").filter { it.isNotBlank() },
                classNames = classNamesTextArea.text.split("\n").filter { it.isNotBlank() },
                serviceIdRegex = serviceIdRegexTextArea.text.split("\n").filter { it.isNotBlank() },
                classNameRegex = classNameRegexTextArea.text.split("\n").filter { it.isNotBlank() }
            )
            println("DEBUG GroupEditorDialog.doOKAction: Calling onSave with group: ${group.name}")
            onSave(group)
            super.doOKAction()
        }
    }
}
