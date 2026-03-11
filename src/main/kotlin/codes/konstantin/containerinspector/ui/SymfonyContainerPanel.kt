package codes.konstantin.containerinspector.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import codes.konstantin.containerinspector.model.ServiceDefinition
import codes.konstantin.containerinspector.model.ServiceGraph
import codes.konstantin.containerinspector.service.ContainerLoadListener
import codes.konstantin.containerinspector.service.ContainerService
import codes.konstantin.containerinspector.settings.SymfonyContainerSettings
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*

/**
 * Main UI panel for the Symfony Container Inspector
 */
class SymfonyContainerPanel(private val project: Project) : JPanel(BorderLayout()), ContainerLoadListener {

    private val containerService = ContainerService.getInstance(project)
    private val settings = SymfonyContainerSettings.getInstance(project)

    // UI Components
    private val searchField = JBTextField()
    private val serviceListModel = DefaultListModel<ServiceDefinition>()
    private val serviceList = JBList(serviceListModel)
    private val detailsPanel = ServiceDetailsPanel(project)
    internal val graphPanel = ServiceGraphPanel(
        project,
        onSelectionChanged = { service, dependents, dependencies ->
            if (service != null) {
                statusLabel.text = "Selected: ${service.getShortName()} - $dependents dependents, $dependencies dependencies"
            } else {
                val graph = containerService.getGraph()
                if (graph != null) {
                    val stats = graph.getStatistics()
                    statusLabel.text = "Container loaded: ${stats.totalServices} services " +
                            "(${stats.appServices} app, ${stats.vendorServices} vendor)"
                } else {
                    statusLabel.text = "No container loaded"
                }
            }
        },
        onClose = { closeContainer() },
        onNavigateToEditor = {
            // Set flag to prevent editor listener from reacting
            isProcessingEditorChange = true
            // Clear flag after a longer delay to allow editor change to complete
            // Use a timer to ensure we wait long enough for the navigation to complete
            javax.swing.Timer(500) { 
                isProcessingEditorChange = false
            }.apply {
                isRepeats = false
                start()
            }
        }
    )
    private val statusLabel = JBLabel("No container loaded")
    private val loadingFormPanel = JPanel()
    private val contentPanel = JPanel(BorderLayout())
    
    // Flag to prevent feedback loop between graph selection and editor selection
    private var isProcessingEditorChange = false

    init {
        setupUI()
        setupListeners()
        setupEditorListener()
        containerService.addListener(this)

        // Show loading form or content based on container state
        if (containerService.getGraph() == null) {
            // Try to auto-load container if there's an active config
            val activeConfig = settings.getActiveConfig()
            if (activeConfig != null && activeConfig.compiledContainerXmlPath.isNotEmpty()) {
                // Auto-load the container
                val containerFile = if (activeConfig.compiledContainerXmlPath.startsWith("/")) {
                    File(activeConfig.compiledContainerXmlPath)
                } else {
                    File(project.basePath, activeConfig.compiledContainerXmlPath)
                }

                if (containerFile.exists()) {
                    containerService.loadContainer(containerFile)
                    showContent()
                } else {
                    showLoadingForm()
                }
            } else {
                showLoadingForm()
            }
        } else {
            showContent()
        }
    }

    private fun setupUI() {
        // Content panel wraps the main UI
        contentPanel.add(graphPanel, BorderLayout.CENTER)

        // Main layout
        add(contentPanel, BorderLayout.CENTER)
    }

    private fun showLoadingForm() {
        // Remove content panel if it's present
        remove(contentPanel)
        contentPanel.isVisible = false

        loadingFormPanel.removeAll()
        loadingFormPanel.layout = BorderLayout()

        val formPanel = JPanel()
        formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)
        formPanel.border = JBUI.Borders.empty(40, 40, 40, 40)

        // Title
        val titleLabel = JBLabel("Load Symfony Container").apply {
            font = font.deriveFont(20f).deriveFont(java.awt.Font.BOLD)
        }
        titleLabel.alignmentX = JPanel.LEFT_ALIGNMENT
        formPanel.add(titleLabel)
        formPanel.add(Box.createVerticalStrut(10))

        // Subtitle
        val subtitleLabel = JBLabel("Select a configuration to load the container").apply {
            foreground = com.intellij.ui.JBColor.GRAY
        }
        subtitleLabel.alignmentX = JPanel.LEFT_ALIGNMENT
        formPanel.add(subtitleLabel)
        formPanel.add(Box.createVerticalStrut(30))

        val allConfigs = settings.getAllConfigs()

        // Create list of config items
        val configListModel = DefaultListModel<codes.konstantin.containerinspector.model.Config>()
        allConfigs.forEach { configListModel.addElement(it) }

        val loadConfigAction: (codes.konstantin.containerinspector.model.Config) -> Unit = { selectedConfig ->
            // Update active config
            settings.activeConfigName = selectedConfig.name

            if (selectedConfig.compiledContainerXmlPath.isEmpty()) {
                Messages.showErrorDialog(
                    project,
                    "The selected configuration does not have a compiled container XML path. Please edit the configuration and add the path.",
                    "Missing Required Path in Config"
                )
            } else {
                // Use config's paths (relative to project root)
                val projectBasePath = project.basePath ?: ""
                val containerFile = File(projectBasePath, selectedConfig.compiledContainerXmlPath)

                // Save settings (for backward compatibility)
                settings.debugContainerPath = containerFile.absolutePath
                settings.cachedContainerPath = containerFile.absolutePath

                // Load container
                loadContainer(containerFile)
            }
        }

        val configList = JBList(configListModel).apply {
            cellRenderer = ConfigListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION

            // Select the active config by default
            val activeConfigName = settings.activeConfigName
            val activeConfigIndex = allConfigs.indexOfFirst { it.name == activeConfigName }
            if (activeConfigIndex >= 0) {
                selectedIndex = activeConfigIndex
            } else if (allConfigs.isNotEmpty()) {
                selectedIndex = 0
            }

            // Load config on single click
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 1) {
                        val index = locationToIndex(e.point)
                        if (index >= 0) {
                            val selectedConfig = model.getElementAt(index)
                            loadConfigAction(selectedConfig)
                        }
                    }
                }
            })
        }

        val scrollPane = JBScrollPane(configList).apply {
            preferredSize = Dimension(600, 300)
            alignmentX = JPanel.LEFT_ALIGNMENT
        }
        formPanel.add(scrollPane)
        formPanel.add(Box.createVerticalStrut(20))

        // Button panel
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JPanel.LEFT_ALIGNMENT
        }

        val manageConfigButton = JButton("Manage Configurations...").apply {
            addActionListener {
                val dialog = ConfigManagementDialog(project) { config ->
                    // Refresh config list
                    val updatedConfigs = settings.getAllConfigs()
                    configListModel.clear()
                    updatedConfigs.forEach { configListModel.addElement(it) }

                    // Select the newly created/edited config
                    val newIndex = updatedConfigs.indexOfFirst { it.name == config.name }
                    if (newIndex >= 0) {
                        configList.selectedIndex = newIndex
                    }
                    settings.activeConfigName = config.name
                }
                dialog.show()
            }
        }

        buttonPanel.add(manageConfigButton)
        buttonPanel.add(Box.createHorizontalGlue())

        formPanel.add(buttonPanel)

        loadingFormPanel.add(formPanel, BorderLayout.CENTER)
        add(loadingFormPanel, BorderLayout.CENTER)
        loadingFormPanel.isVisible = true

        revalidate()
        repaint()
    }

    private inner class ConfigListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val config = value as? codes.konstantin.containerinspector.model.Config

            val panel = JPanel().apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(10, 15, 10, 15)
                background = if (isSelected) {
                    com.intellij.ui.JBColor.namedColor("List.selectionBackground", com.intellij.ui.JBColor(0x0D84F1, 0x0D84F1))
                } else {
                    com.intellij.ui.JBColor.namedColor("List.background", com.intellij.ui.JBColor.background())
                }
            }

            if (config != null) {
                val contentPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }

                // Config name (bold, larger)
                val nameLabel = JBLabel(config.name).apply {
                    font = font.deriveFont(14f).deriveFont(java.awt.Font.BOLD)
                    foreground = if (isSelected) {
                        com.intellij.ui.JBColor.namedColor("List.selectionForeground", com.intellij.ui.JBColor.foreground())
                    } else {
                        com.intellij.ui.JBColor.foreground()
                    }
                }
                contentPanel.add(nameLabel)
                contentPanel.add(Box.createVerticalStrut(5))

                // Path
                val pathLabel = JBLabel("Path: ${config.compiledContainerXmlPath}").apply {
                    font = font.deriveFont(11f)
                    foreground = if (isSelected) {
                        com.intellij.ui.JBColor.namedColor("List.selectionForeground", com.intellij.ui.JBColor.foreground()).brighter()
                    } else {
                        com.intellij.ui.JBColor.GRAY
                    }
                }
                contentPanel.add(pathLabel)
                contentPanel.add(Box.createVerticalStrut(3))

                // Group names
                if (config.groups.isNotEmpty()) {
                    val groupNames = config.groups.joinToString(", ") { it.name }
                    val groupLabel = JBLabel("Groups: $groupNames").apply {
                        font = font.deriveFont(10f)
                        foreground = if (isSelected) {
                            com.intellij.ui.JBColor.namedColor("List.selectionForeground", com.intellij.ui.JBColor.foreground()).brighter()
                        } else {
                            com.intellij.ui.JBColor.GRAY
                        }
                    }
                    contentPanel.add(groupLabel)
                }

                panel.add(contentPanel, BorderLayout.CENTER)
            }

            return panel
        }
    }

    private fun showContent() {
        // Remove loading form
        remove(loadingFormPanel)
        loadingFormPanel.isVisible = false

        // Show content
        contentPanel.isVisible = true
        add(contentPanel, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    private fun closeContainer() {
        containerService.clearContainer()
        showLoadingForm()
    }

    private fun setupListeners() {

        // Search field
        searchField.addActionListener {
            performSearch()
        }

        // Service list selection
        serviceList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = serviceList.selectedValue
                if (selected != null) {
                    detailsPanel.showService(selected)
                }
            }
        }
    }

    private fun setupEditorListener() {
        // Listen for file editor changes to sync with graph panel
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    // Ignore editor changes that we triggered ourselves
                    if (isProcessingEditorChange) return
                    
                    val file = event.newFile ?: return
                    handleEditorFileSelection(file)
                }
            }
        )
    }

    private fun handleEditorFileSelection(file: VirtualFile) {
        try {
            // Access PSI requires read action
            val className = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<String?> {
                try {
                    // Get the PSI file
                    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@runReadAction null

                    // Find all PhpClass elements in the file
                    val phpClasses = PsiTreeUtil.findChildrenOfType(psiFile, PhpClass::class.java)

                    if (phpClasses.isEmpty()) return@runReadAction null

                    // Use the first class in the file and get its FQN
                    val phpClass = phpClasses.first()
                    val fqn = phpClass.fqn.toString()

                    // Remove leading backslash if present (Symfony uses FQN without leading backslash)
                    if (fqn.startsWith("\\")) fqn.substring(1) else fqn
                } catch (e: Exception) {
                    null
                }
            }

            // Update active editor and optionally select the service in the graph panel
            // (selectServiceByClassName handles both isActiveInEditor and selection based on linkWithEditor)
            if (className != null) {
                graphPanel.selectServiceByClassName(className)
            }
        } catch (e: Exception) {
            // Silently fail - not all files contain PHP classes
        }
    }


    private fun loadContainer(file: File) {
        try {
            containerService.loadContainer(file)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to load container: ${e.message}",
                "Load Error"
            )
        }
    }

    private fun performSearch() {
        val query = searchField.text.trim()
        val graph = containerService.getGraph() ?: return

        serviceListModel.clear()

        val results = if (query.isEmpty()) {
            // Show all services when no search query
            graph.getAllServices()
        } else {
            graph.searchServices(query, settings.caseSensitiveSearch)
        }

        val filtered = if (settings.hideVendorServicesByDefault) {
            results.filter { !it.isVendorService() }
        } else {
            results
        }

        filtered.forEach { serviceListModel.addElement(it) }

        if (serviceListModel.size() > 0) {
            serviceList.selectedIndex = 0
        }
    }

    // ContainerLoadListener implementation
    override fun onContainerLoaded(graph: ServiceGraph) {
        val stats = graph.getStatistics()
        statusLabel.text = "Container loaded: ${stats.totalServices} services " +
                "(${stats.appServices} app, ${stats.vendorServices} vendor)"
        searchField.text = ""
        detailsPanel.clear()
        graphPanel.showGraph(graph)
        // Don't call performSearch() - we're only showing the graph panel now, not the service list
        showContent()

        // Select the service that's currently active in the editor (if any)
        selectCurrentlyActiveEditorService()
    }

    private fun selectCurrentlyActiveEditorService() {
        // Wait for indexes to be ready before trying to access PSI
        com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
            javax.swing.SwingUtilities.invokeLater {
                try {
                    // Get the currently selected file in the editor
                    val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    val currentFile = fileEditorManager.selectedFiles.firstOrNull() ?: return@invokeLater

                    // Handle the file selection (this will update isActiveInEditor and optionally select)
                    handleEditorFileSelection(currentFile)
                } catch (e: Exception) {
                    // Silently fail - not critical
                }
            }
        }
    }

    override fun onContainerLoadFailed(exception: Exception) {
        statusLabel.text = "Failed to load container: ${exception.message}"
    }

    override fun onContainerCleared() {
        statusLabel.text = "No container loaded"
        serviceListModel.clear()
        searchField.text = ""
        detailsPanel.clear()
        graphPanel.clear()
    }
}
