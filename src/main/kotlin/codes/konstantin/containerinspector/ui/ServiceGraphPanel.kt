package codes.konstantin.containerinspector.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import codes.konstantin.containerinspector.layout.ElkLayoutAdapter
import codes.konstantin.containerinspector.model.ServiceDefinition
import codes.konstantin.containerinspector.model.ServiceGraph
import codes.konstantin.containerinspector.model.ServiceGroup
import codes.konstantin.containerinspector.settings.SymfonyContainerSettings
import java.awt.*
import java.awt.event.*
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import javax.swing.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Visual graph panel showing service dependencies as a node-link diagram
 */
class ServiceGraphPanel(
    private val project: Project,
    private val onSelectionChanged: ((ServiceDefinition?, Int, Int) -> Unit)? = null,
    private val onClose: (() -> Unit)? = null,
    private val onNavigateToEditor: (() -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val settings = codes.konstantin.containerinspector.settings.SymfonyContainerSettings.getInstance(project)
    private val graphCanvas = GraphCanvas()
    private val scrollPane = JBScrollPane(graphCanvas).apply {
        // Hide tooltip when scrolling
        viewport.addChangeListener {
            graphCanvas.hideTooltipOnScroll()
        }
    }
    private val searchField = JBTextField().apply {
        emptyText.text = "Search"
    }
    private val resultsLabel = JBLabel("")
    private val prevButton = ActionButton(
        object : AnAction("Previous Occurrence", "Go to previous search result", AllIcons.Actions.PreviousOccurence) {
            override fun actionPerformed(e: AnActionEvent) {
                navigateToPrevious()
            }
        },
        null,
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )
    private val nextButton = ActionButton(
        object : AnAction("Next Occurrence", "Go to next search result", AllIcons.Actions.NextOccurence) {
            override fun actionPerformed(e: AnActionEvent) {
                navigateToNext()
            }
        },
        null,
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )

    // Config dropdown
    private val configComboBox = com.intellij.openapi.ui.ComboBox<String>().apply {
        preferredSize = Dimension(150, 25)
    }

    // Graph display options
    private var showVendor = settings.showVendor
    private val showVendorAction = object : ToggleAction(
        "Toggle Vendor Services",
        "Show or hide vendor/library services",
        AllIcons.Actions.GroupByPackage
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = showVendor

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showVendor = state
            settings.showVendor = state
            graphCanvas.currentGraph?.let { graphCanvas.setGraph(it) }
        }
    }
    private val showVendorButton = ActionButton(
        showVendorAction,
        showVendorAction.templatePresentation.clone(),
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )

    private var showSingles = settings.showSingles
    private val showSinglesAction = object : ToggleAction(
        "Toggle Single Nodes",
        "Show or hide nodes without connections",
        AllIcons.General.ShowInfos
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = showSingles

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showSingles = state
            settings.showSingles = state
            graphCanvas.currentGraph?.let { graphCanvas.setGraph(it) }
        }
    }
    private val showSinglesButton = ActionButton(
        showSinglesAction,
        showSinglesAction.templatePresentation.clone(),
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )

    private var fullTrace = settings.fullTrace
    private val fullTraceAction = object : ToggleAction(
        "Toggle Full Trace",
        "Show full connection trace between nodes",
        AllIcons.Actions.ShowAsTree
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = fullTrace

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            fullTrace = state
            settings.fullTrace = state
            graphCanvas.repaint()
        }
    }
    private val fullTraceButton = ActionButton(
        fullTraceAction,
        fullTraceAction.templatePresentation.clone(),
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )

    private var focusMode = settings.focusMode
    private val focusModeAction = object : ToggleAction(
        "Toggle Focus Mode",
        "Focus on selected service and its immediate connections",
        AllIcons.General.InspectionsEye
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = focusMode

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            focusMode = state
            settings.focusMode = state
            graphCanvas.currentGraph?.let { graphCanvas.setGraph(it) }
        }
    }
    private val focusModeButton = ActionButton(
        focusModeAction,
        focusModeAction.templatePresentation.clone(),
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )

    private var linkWithEditor = settings.linkWithEditor
    private val linkWithEditorAction = object : ToggleAction(
        "Link with Editor",
        "Synchronize service selection with editor",
        AllIcons.General.Pin
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = linkWithEditor

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            linkWithEditor = state
            settings.linkWithEditor = state

            // When activating link mode, if a service is selected, open it in the editor
            if (state) {
                graphCanvas.getSelectedService()?.className?.let { className ->
                    onNavigateToEditor?.invoke()
                    // Run navigation on background thread to avoid EDT violations
                    ApplicationManager.getApplication().executeOnPooledThread {
                        NavigationHelper.navigateToClass(project, className)
                    }
                }
            }
        }
    }
    private val linkWithEditorButton = ActionButton(
        linkWithEditorAction,
        linkWithEditorAction.templatePresentation.clone(),
        ActionPlaces.TOOLBAR,
        ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
    )

    private var searchResults = listOf<Node>()
    private var currentResultIndex = -1

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        // Top control panel with BorderLayout to position items
        val topPanel = object : JPanel(BorderLayout()) {
            init {
                border = JBUI.Borders.empty(2, 5, 2, 5)

                // Left side - controls
                val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))

                // Config dropdown
                leftPanel.add(configComboBox)

                // Vertical separator
                leftPanel.add(createVerticalSeparator())

                leftPanel.add(showVendorButton)
                leftPanel.add(showSinglesButton)
                leftPanel.add(fullTraceButton)
                leftPanel.add(focusModeButton)
                leftPanel.add(linkWithEditorButton)

                // Vertical separator
                leftPanel.add(createVerticalSeparator())

                // Search components (will wrap together as a group)
                val searchGroup = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                    searchField.preferredSize = Dimension(250, 25)
                    add(searchField)
                    add(resultsLabel)
                    add(prevButton)
                    add(nextButton)
                }
                leftPanel.add(searchGroup)

                add(leftPanel, BorderLayout.CENTER)

                // Right side - zoom controls and close button
                val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))

                // Setup zoom controls
                val zoomOutButton = ActionButton(
                    object : AnAction("Zoom Out", "Decrease zoom level", AllIcons.General.ZoomOut) {
                        override fun actionPerformed(e: AnActionEvent) {
                            graphCanvas.adjustZoomCentered(-0.1)
                        }
                    },
                    null,
                    ActionPlaces.TOOLBAR,
                    ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
                )
                val zoomInButton = ActionButton(
                    object : AnAction("Zoom In", "Increase zoom level", AllIcons.General.ZoomIn) {
                        override fun actionPerformed(e: AnActionEvent) {
                            graphCanvas.adjustZoomCentered(0.1)
                        }
                    },
                    null,
                    ActionPlaces.TOOLBAR,
                    ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
                )
                val zoomResetButton = JButton("100%").apply {
                    preferredSize = Dimension(60, 25)
                    addActionListener {
                        graphCanvas.resetZoomCentered()
                    }
                }

                // Close button
                val closeButton = JButton("Close").apply {
                    preferredSize = Dimension(70, 25)
                    addActionListener {
                        onClose?.invoke()
                    }
                }

                rightPanel.add(zoomOutButton)
                rightPanel.add(zoomResetButton)
                rightPanel.add(zoomInButton)
                rightPanel.add(closeButton)

                add(rightPanel, BorderLayout.EAST)
            }
        }

        prevButton.isEnabled = false
        nextButton.isEnabled = false

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun createVerticalSeparator(): JComponent {
        return JPanel().apply {
            preferredSize = Dimension(12, 20)
            maximumSize = Dimension(12, 20)
            isOpaque = false
            layout = BorderLayout()
            border = JBUI.Borders.empty(0, 5, 0, 5)
            add(JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = Dimension(2, 20)
            }, BorderLayout.CENTER)
        }
    }

    private fun setupListeners() {
        // When user types, clear results so next Enter performs a new search
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                searchResults = emptyList()
            }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                searchResults = emptyList()
            }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                searchResults = emptyList()
            }
        })

        searchField.addActionListener {
            if (searchResults.isNotEmpty()) {
                // If we already have results, jump to next result
                navigateToNext()
            } else {
                // Otherwise, perform the search
                performSearch()
            }
        }

        // Load configs into dropdown and select active config
        refreshConfigDropdown()

        // Handle config selection change
        configComboBox.addActionListener {
            val selectedConfigName = configComboBox.selectedItem as? String
            if (selectedConfigName == "Modify Configurations...") {
                // Show config management dialog
                val dialog = ConfigManagementDialog(project) { config ->
                    // Apply new config and reload graph
                    applyConfig(config)
                }
                dialog.show()
                // Reset dropdown to the active config
                configComboBox.selectedItem = settings.activeConfigName
            } else if (selectedConfigName != null && selectedConfigName != settings.activeConfigName) {
                settings.activeConfigName = selectedConfigName
                // Clear selection before switching configs to avoid issues with minified services
                graphCanvas.clearSelection()
                graphCanvas.currentGraph?.let { graphCanvas.setGraph(it) }
            }
        }
    }

    /**
     * Refresh the config dropdown with all available configs
     */
    private fun refreshConfigDropdown() {
        val allConfigs = settings.getAllConfigs()
        configComboBox.removeAllItems()
        allConfigs.forEach { config ->
            configComboBox.addItem(config.name)
        }
        // Add separator item
        configComboBox.addItem("Modify Configurations...")
        configComboBox.selectedItem = settings.activeConfigName
    }

    /**
     * Apply a config to the graph panel
     */
    private fun applyConfig(config: codes.konstantin.containerinspector.model.Config) {
        // Refresh the dropdown to show updated configs
        refreshConfigDropdown()

        // Set the active config
        settings.activeConfigName = config.name
        configComboBox.selectedItem = config.name

        // Reload the graph with new config
        graphCanvas.currentGraph?.let { graphCanvas.setGraph(it) }
    }

    /**
     * Check if a service is an app service based on active config
     */
    fun isAppService(service: ServiceDefinition): Boolean {
        try {
            val config = settings.getActiveConfig() ?: return !service.isVendorService()
            val classNameRegex = config.appClassNameRegex.takeIf { it.isNotBlank() }?.toRegex()
            val serviceIdRegex = config.appServiceIdRegex.takeIf { it.isNotBlank() }?.toRegex()

            val matchesClassName = service.className?.let { className ->
                classNameRegex?.matches(className) ?: false
            } ?: false

            val matchesServiceId = serviceIdRegex?.matches(service.id) ?: false

            return matchesClassName || matchesServiceId
        } catch (e: Exception) {
            // Invalid regex, fallback to default behavior
            return !service.isVendorService()
        }
    }

    /**
     * Cached minify checker for performance
     */
    private inner class MinifyChecker(config: codes.konstantin.containerinspector.model.Config) {
        private val neverMinifyClassNamesList = config.neverMinifyClassNames.toSet()

        private val neverMinifyServiceIdsList = config.neverMinifyServiceIds.toSet()

        private val minifyNonApp = config.minifyNonApp

        private val classNameRegexList = config.minifyClassNameRegex
            .mapNotNull { try { it.toRegex() } catch (e: Exception) { null } }

        private val classNamesList = config.minifyClassNames.toSet()

        private val serviceIdRegexList = config.minifyServiceIdRegex
            .mapNotNull { try { it.toRegex() } catch (e: Exception) { null } }

        private val serviceIdsList = config.minifyServiceIds.toSet()

        fun shouldMinify(service: ServiceDefinition): Boolean {
            try {
                // Check never minify lists first (these take precedence)
                if (service.className != null && neverMinifyClassNamesList.contains(service.className)) {
                    return false
                }

                if (neverMinifyServiceIdsList.contains(service.id)) {
                    return false
                }

                // Check minify non-app setting
                if (minifyNonApp && !isAppService(service)) {
                    return true
                }

                // Check class name regex patterns
                if (service.className != null) {
                    for (regex in classNameRegexList) {
                        if (regex.matches(service.className)) return true
                    }
                }

                // Check class names (exact match)
                if (service.className != null && classNamesList.contains(service.className)) {
                    return true
                }

                // Check service ID regex patterns
                for (regex in serviceIdRegexList) {
                    if (regex.matches(service.id)) return true
                }

                // Check service IDs (exact match)
                return serviceIdsList.contains(service.id)
            } catch (e: Exception) {
                return false
            }
        }
    }


    private fun performSearch() {
        val query = searchField.text.trim()
        if (query.isEmpty()) {
            searchResults = emptyList()
            currentResultIndex = -1
            updateSearchUI()
            graphCanvas.highlightNodes(emptyList(), null)
            return
        }

        val lowerQuery = query.lowercase()
        searchResults = graphCanvas.nodes.filter { node ->
            node.service.id.lowercase().contains(lowerQuery) ||
            (node.service.className?.lowercase()?.contains(lowerQuery) ?: false)
        }

        currentResultIndex = if (searchResults.isNotEmpty()) 0 else -1
        updateSearchUI()

        if (currentResultIndex >= 0) {
            centerOnResult(currentResultIndex)
        } else {
            graphCanvas.highlightNodes(emptyList(), null)
        }
    }

    private fun navigateToPrevious() {
        if (searchResults.isEmpty()) return
        currentResultIndex = if (currentResultIndex > 0) currentResultIndex - 1 else searchResults.size - 1
        updateSearchUI()
        centerOnResult(currentResultIndex)
    }

    private fun navigateToNext() {
        if (searchResults.isEmpty()) return
        currentResultIndex = (currentResultIndex + 1) % searchResults.size
        updateSearchUI()
        centerOnResult(currentResultIndex)
    }

    private fun updateSearchUI() {
        if (searchResults.isEmpty()) {
            resultsLabel.text = if (searchField.text.trim().isEmpty()) "" else "No results"
            prevButton.isEnabled = false
            nextButton.isEnabled = false
            graphCanvas.highlightNodes(emptyList(), null)
        } else {
            resultsLabel.text = "${currentResultIndex + 1}/${searchResults.size}"
            prevButton.isEnabled = true
            nextButton.isEnabled = true
            graphCanvas.highlightNodes(searchResults, searchResults[currentResultIndex])
        }
    }

    private fun centerOnResult(index: Int) {
        if (index < 0 || index >= searchResults.size) return
        centerOnNode(searchResults[index])
    }

    private fun centerOnNode(node: Node) {
        val zoomFactor = graphCanvas.getZoomFactor()

        // Calculate center position in zoomed coordinates
        val centerX = ((node.x + node.width / 2) * zoomFactor).toInt()
        val centerY = ((node.y + node.height / 2) * zoomFactor).toInt()

        // Calculate viewport position to center the node
        val viewport = scrollPane.viewport
        val viewWidth = viewport.width
        val viewHeight = viewport.height

        val scrollX = max(0, centerX - viewWidth / 2)
        val scrollY = max(0, centerY - viewHeight / 2)

        viewport.viewPosition = Point(scrollX, scrollY)
        graphCanvas.repaint()
    }

    fun showGraph(graph: ServiceGraph) {
        graphCanvas.setGraph(graph)
    }

    fun clear() {
        graphCanvas.clear()
    }

    /**
     * Select the first service in the graph that matches the given class name.
     * This is used when link with editor is active and a file is opened in the editor.
     */
    fun selectServiceByClassName(className: String) {
        if (!linkWithEditor) return

        graphCanvas.selectNodeByClassName(className)
    }

    /**
     * Check if link with editor is currently active
     */
    fun isLinkWithEditorActive(): Boolean = linkWithEditor

    private inner class GraphCanvas : JPanel() {
        var nodes = listOf<Node>()
        private var edges = listOf<Edge>()
        private var groupBoxes = listOf<GroupBox>()
        private var groupError: String? = null
        private var draggedNode: Node? = null
        private var dragOffset = Point()
        private var highlightedNodes = setOf<Node>()
        private var currentResultNode: Node? = null
        private var selectedNode: Node? = null
        var currentGraph: ServiceGraph? = null
        private var zoomFactor = 1.0
        private var currentTooltipNode: Node? = null
        private var tooltipContent: String? = null
        private var isLoading = false

        fun getZoomFactor(): Double = zoomFactor

        fun clearSelection() {
            selectedNode = null
            nodes.forEach { it.isSelected = false }
        }

        fun adjustZoom(delta: Double) {
            val oldZoom = zoomFactor
            zoomFactor = (zoomFactor + delta).coerceIn(0.1, 3.0)
            if (oldZoom != zoomFactor) {
                updatePreferredSize()
                revalidate()
                repaint()
            }
        }

        fun adjustZoomCentered(delta: Double) {
            val viewport = scrollPane.viewport
            val oldZoom = zoomFactor

            // Get the center point of the viewport in content coordinates (before zoom)
            val viewRect = viewport.viewRect
            val centerX = (viewRect.x + viewRect.width / 2.0) / oldZoom
            val centerY = (viewRect.y + viewRect.height / 2.0) / oldZoom

            // Apply zoom
            zoomFactor = (zoomFactor + delta).coerceIn(0.1, 3.0)

            if (oldZoom != zoomFactor) {
                updatePreferredSize()
                revalidate()

                // Calculate new viewport position to keep the same center point
                val newX = (centerX * zoomFactor - viewRect.width / 2.0).toInt().coerceAtLeast(0)
                val newY = (centerY * zoomFactor - viewRect.height / 2.0).toInt().coerceAtLeast(0)

                viewport.viewPosition = Point(newX, newY)
                repaint()
            }
        }

        fun resetZoom() {
            val oldZoom = zoomFactor
            zoomFactor = 1.0
            if (oldZoom != zoomFactor) {
                updatePreferredSize()
                revalidate()
                repaint()
            }
        }

        fun resetZoomCentered() {
            val viewport = scrollPane.viewport
            val oldZoom = zoomFactor

            // Get the center point of the viewport in content coordinates (before zoom)
            val viewRect = viewport.viewRect
            val centerX = (viewRect.x + viewRect.width / 2.0) / oldZoom
            val centerY = (viewRect.y + viewRect.height / 2.0) / oldZoom

            // Reset zoom to 1.0
            zoomFactor = 1.0

            if (oldZoom != zoomFactor) {
                updatePreferredSize()
                revalidate()

                // Get the updated viewport dimensions after revalidate
                val newViewRect = viewport.viewRect

                // Calculate new viewport position to keep the same center point
                val newX = (centerX * zoomFactor - newViewRect.width / 2.0).toInt().coerceAtLeast(0)
                val newY = (centerY * zoomFactor - newViewRect.height / 2.0).toInt().coerceAtLeast(0)

                viewport.viewPosition = Point(newX, newY)
                repaint()
            }
        }

        init {
            preferredSize = Dimension(2000, 2000)
            background = JBColor.background()

            addMouseListener(object : MouseAdapter() {
                private var pressedPoint: Point? = null
                private var pressedNode: Node? = null

                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        val scaledPoint = Point((e.x / zoomFactor).toInt(), (e.y / zoomFactor).toInt())
                        nodes.find { it.contains(scaledPoint) }?.let { node ->
                            showContextMenu(e, node)
                        }
                        return
                    }

                    if (!SwingUtilities.isLeftMouseButton(e)) return

                    val scaledPoint = Point((e.x / zoomFactor).toInt(), (e.y / zoomFactor).toInt())
                    pressedPoint = scaledPoint
                    pressedNode = nodes.find { it.contains(scaledPoint) }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    
                    val scaledPoint = Point((e.x / zoomFactor).toInt(), (e.y / zoomFactor).toInt())
                    val releasedNode = nodes.find { it.contains(scaledPoint) }
                    
                    // Only process if released on same node as pressed (no drag)
                    if (pressedNode == releasedNode && pressedNode != null) {
                        val clickedNode = pressedNode
                        
                        // Single click - select/deselect node instantly
                        val previousSelection = selectedNode
                        selectedNode = if (selectedNode == clickedNode) null else clickedNode

                        // Update selection state on all nodes
                        nodes.forEach { node ->
                            node.isSelected = node == selectedNode
                        }

                        // If link with editor is active, open the class in editor
                        if (linkWithEditor && selectedNode != null) {
                            selectedNode!!.service.className?.let { className ->
                                // Notify that we're navigating to editor (to prevent feedback loop)
                                onNavigateToEditor?.invoke()
                                // Run navigation on background thread to avoid EDT violations
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    NavigationHelper.navigateToClass(project, className)
                                }
                            }
                        }

                        // Notify listener about selection change
                        if (selectedNode != null && currentGraph != null) {
                            val dependencies = currentGraph!!.getDirectDependencies(selectedNode!!.service.id).size
                            val dependents = currentGraph!!.getDirectDependents(selectedNode!!.service.id).size
                            onSelectionChanged?.invoke(selectedNode!!.service, dependents, dependencies)
                        } else {
                            onSelectionChanged?.invoke(null, 0, 0)
                        }

                        // Handle focus mode
                        if (selectedNode == null) {
                            // Deselected - turn off focus mode and reset checkbox
                            if (focusMode) {
                                focusMode = false
                                currentGraph?.let { setGraph(it) }
                            } else {
                                repaint()
                            }
                        } else if (focusMode && previousSelection != selectedNode) {
                            // Different service selected in focus mode - re-layout
                            currentGraph?.let { setGraph(it) }
                        } else {
                            repaint()
                        }
                    }
                    
                    pressedPoint = null
                    pressedNode = null
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (e.clickCount != 2) return
                    
                    val scaledPoint = Point((e.x / zoomFactor).toInt(), (e.y / zoomFactor).toInt())
                    val clickedNode = nodes.find { it.contains(scaledPoint) }
                    
                    // Double-click: open in editor and re-select to keep it selected
                    clickedNode?.let { node ->
                        val className = node.service.className ?: return
                        
                        // Run navigation on background thread to avoid EDT violations
                        ApplicationManager.getApplication().executeOnPooledThread {
                            NavigationHelper.navigateToClass(project, className)
                        }

                        // Re-select the node to ensure it stays selected
                        selectedNode = node
                        nodes.forEach { it.isSelected = it == selectedNode }

                        if (currentGraph != null) {
                            val dependencies = currentGraph!!.getDirectDependencies(node.service.id).size
                            val dependents = currentGraph!!.getDirectDependents(node.service.id).size
                            onSelectionChanged?.invoke(node.service, dependents, dependencies)
                        }
                        repaint()
                    }
                }
            })

            addMouseListener(object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    // Hide tooltip when mouse leaves the canvas
                    if (currentTooltipNode != null) {
                        currentTooltipNode = null
                        tooltipContent = null
                        repaint()
                    }
                }
            })

            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    // Dragging disabled
                }

                override fun mouseMoved(e: MouseEvent) {
                    val scaledPoint = Point((e.x / zoomFactor).toInt(), (e.y / zoomFactor).toInt())

                    // Check if hovering over tag icon first (before checking node bounds)
                    var isOverTagIcon = false
                    var tagIconNode: Node? = null

                    for (node in nodes) {
                        if (node.containsTagIcon(scaledPoint)) {
                            isOverTagIcon = true
                            tagIconNode = node
                            break
                        }
                    }

                    // Now check for hovered node
                    val hoveredNode = nodes.find { it.contains(scaledPoint) }

                    // Update hover states
                    nodes.forEach { node ->
                        node.isHovered = false
                        node.isTagIconHovered = false
                    }

                    // Determine which node should show tags/aliases
                    val nodeWithInfo = if (isOverTagIcon) {
                        tagIconNode
                    } else {
                        hoveredNode?.takeIf { it.service.tags.isNotEmpty() || it.service.aliases.isNotEmpty() }
                    }

                    if (nodeWithInfo != null) {
                        if (isOverTagIcon) {
                            nodeWithInfo.isTagIconHovered = true
                        }

                        // Only update tooltip if it's not already showing for this node
                        if (currentTooltipNode != nodeWithInfo) {
                            val content = buildString {
                                // Add aliases if present
                                if (nodeWithInfo.service.aliases.isNotEmpty()) {
                                    append("Aliases:\n")
                                    nodeWithInfo.service.aliases.forEach { alias ->
                                        append("  • $alias\n")
                                    }
                                    if (nodeWithInfo.service.tags.isNotEmpty()) {
                                        append("\n")
                                    }
                                }

                                // Add tags if present
                                if (nodeWithInfo.service.tags.isNotEmpty()) {
                                    append("Tags:\n")
                                    nodeWithInfo.service.tags.forEach { tag ->
                                        if (tag.parameters.isEmpty()) {
                                            append("  • ${tag.name}\n")
                                        } else {
                                            append("  • ${tag.name}\n")
                                            tag.parameters.forEach { (key, value) ->
                                                append("      - $key: $value\n")
                                            }
                                        }
                                    }
                                }
                            }.trimEnd()

                            currentTooltipNode = nodeWithInfo
                            tooltipContent = content
                        }
                    } else {
                        if (currentTooltipNode != null) {
                            currentTooltipNode = null
                            tooltipContent = null
                        }
                    }

                    hoveredNode?.isHovered = true

                    cursor = if (hoveredNode != null || isOverTagIcon) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }

                    repaint()
                }
            })
        }

        fun hideTooltipOnScroll() {
            if (currentTooltipNode != null) {
                currentTooltipNode = null
                tooltipContent = null
                repaint()
            }
        }

        private fun showContextMenu(e: MouseEvent, node: Node) {
            val popup = JPopupMenu()

            // Add to minify by service ID
            popup.add(JMenuItem("Add to Minify (by Service ID)").apply {
                addActionListener {
                    addToMinifyList(node.service.id, isServiceId = true)
                }
            })

            // Add to minify by class name (if available)
            node.service.className?.let { className ->
                popup.add(JMenuItem("Add to Minify (by Class Name)").apply {
                    addActionListener {
                        addToMinifyList(className, isServiceId = false)
                    }
                })
            }

            popup.show(e.component, e.x, e.y)
        }

        private fun addToMinifyList(value: String, isServiceId: Boolean) {
            val config = settings.getActiveConfig() ?: return

            // Create updated config with the new minify value
            val updatedConfig = if (isServiceId) {
                config.copy(minifyServiceIds = config.minifyServiceIds + value)
            } else {
                config.copy(minifyClassNames = config.minifyClassNames + value)
            }

            // Get all configs and replace the active one
            val allConfigs = settings.getAllConfigs().toMutableList()
            val index = allConfigs.indexOfFirst { it.name == config.name }
            if (index >= 0) {
                allConfigs[index] = updatedConfig
                settings.saveConfigs(allConfigs)

                // Reload the graph to apply the new minify settings
                currentGraph?.let { setGraph(it) }
            }
        }

        fun setGraph(graph: ServiceGraph) {
            currentGraph = graph

            // Show loading state
            val previousNodes = nodes
            val previousEdges = edges
            isLoading = true
            nodes = emptyList()
            edges = emptyList()

            // Reset canvas size to fit viewport during loading
            preferredSize = null
            revalidate()
            repaint()

            // Run layout on background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val services = graph.getAllServices()

                    // Create minify checker once for performance
                    val config = settings.getActiveConfig()
                    val minifyChecker = config?.let { MinifyChecker(it) }

                    // Filter services based on configuration
                    val filteredServices = services.filter { service ->
                        // Exclude minified services from having their own nodes
                        if (minifyChecker?.shouldMinify(service) == true) return@filter false

                        // Check if we should show vendor services
                        if (!showVendor && !this@ServiceGraphPanel.isAppService(service)) {
                            return@filter false
                        }

                        true
                    }

                    // Create nodes - initially without positions
                    var tempNodes = filteredServices.map { service ->
                        Node(
                            service = service,
                            x = 0.0,
                            y = 0.0,
                            isVendorFn = { !isAppService(it) },
                            minifiedDeps = graph.getDirectDependencies(service.id)
                                .filter { minifyChecker?.shouldMinify(it) == true }
                        )
                    }

                    // Create edges (excluding edges to minified services)
                    val nodeMap = tempNodes.associateBy { it.service.id }
                    var tempEdges = buildList {
                        tempNodes.forEach { fromNode ->
                            graph.getDirectDependencies(fromNode.service.id).forEach { depService ->
                                if (minifyChecker?.shouldMinify(depService) != true) {
                                    nodeMap[depService.id]?.let { toNode ->
                                        add(Edge(fromNode, toNode))
                                    }
                                }
                            }
                        }
                    }

                    // Filter out singles if checkbox is not checked
                    if (!showSingles) {
                        val connectedNodeIds = tempEdges.flatMap { edge ->
                            listOf(edge.from.service.id, edge.to.service.id)
                        }.toSet()

                        tempNodes = tempNodes.filter { node ->
                            connectedNodeIds.contains(node.service.id)
                        }

                        // Update edges to only include remaining nodes
                        val remainingNodeIds = tempNodes.map { it.service.id }.toSet()
                        tempEdges = tempEdges.filter { edge ->
                            remainingNodeIds.contains(edge.from.service.id) &&
                            remainingNodeIds.contains(edge.to.service.id)
                        }
                    }

                    // Apply focus mode filtering if enabled and a service is selected
                    if (focusMode && selectedNode != null) {
                        val focusedServiceIds = mutableSetOf(selectedNode!!.service.id)

                        if (fullTrace) {
                            // Get all recursive dependencies
                            val toProcessDeps = mutableListOf(selectedNode!!.service.id)
                            while (toProcessDeps.isNotEmpty()) {
                                val serviceId = toProcessDeps.removeAt(0)
                                graph.getDirectDependencies(serviceId).forEach { dep ->
                                    if (dep.id !in focusedServiceIds) {
                                        focusedServiceIds.add(dep.id)
                                        toProcessDeps.add(dep.id)
                                    }
                                }
                            }

                            // Get all recursive dependents
                            val toProcessDependents = mutableListOf(selectedNode!!.service.id)
                            while (toProcessDependents.isNotEmpty()) {
                                val serviceId = toProcessDependents.removeAt(0)
                                graph.getDirectDependents(serviceId).forEach { dep ->
                                    if (dep.id !in focusedServiceIds) {
                                        focusedServiceIds.add(dep.id)
                                        toProcessDependents.add(dep.id)
                                    }
                                }
                            }
                        } else {
                            // Only direct connections
                            graph.getDirectDependencies(selectedNode!!.service.id).forEach { dep ->
                                focusedServiceIds.add(dep.id)
                            }
                            graph.getDirectDependents(selectedNode!!.service.id).forEach { dep ->
                                focusedServiceIds.add(dep.id)
                            }
                        }

                        // Filter nodes to only focused services
                        tempNodes = tempNodes.filter { node ->
                            focusedServiceIds.contains(node.service.id)
                        }

                        // Update edges to only include remaining nodes
                        val remainingNodeIds = tempNodes.map { it.service.id }.toSet()
                        tempEdges = tempEdges.filter { edge ->
                            remainingNodeIds.contains(edge.from.service.id) &&
                            remainingNodeIds.contains(edge.to.service.id)
                        }
                    }

                    // Store temporarily for layout
                    val layoutNodes = tempNodes
                    val layoutEdges = tempEdges

                    // Calculate group assignments and detect conflicts (reuse config)
                    val groups = config?.let { loadGroups(it) } ?: emptyList()
                    println("DEBUG: Loaded ${groups.size} groups: ${groups.map { it.name }}")
                    groups.forEach { group ->
                        println("DEBUG: Group '${group.name}': serviceIds=${group.serviceIds}, classNames=${group.classNames}, serviceIdRegex=${group.serviceIdRegex}, classNameRegex=${group.classNameRegex}")
                    }
                    val groupAssignments = config?.let { calculateGroupAssignmentsForLayout(it, groups, layoutNodes) } ?: GroupAssignmentResult(emptyMap(), null)
                    println("DEBUG: Group assignments: ${groupAssignments.assignments.size} nodes assigned, error: ${groupAssignments.error}")

                    // Apply hierarchical layout with groups (heavy computation here)
                    val layoutResult = computeHierarchicalLayout(groupAssignments, groups, layoutNodes, layoutEdges)

                    // Update UI on EDT
                    SwingUtilities.invokeLater {
                        isLoading = false
                        nodes = layoutResult.nodes
                        edges = layoutResult.edges
                        groupBoxes = layoutResult.groupBoxes
                        groupError = layoutResult.groupError

                        println("DEBUG: Setting groupError to: $groupError")

                        // Show notification if there's a group error
                        if (groupError != null) {
                            println("DEBUG: Showing notification for group error")
                            com.intellij.notification.NotificationGroupManager.getInstance()
                                .getNotificationGroup("Container Inspector for Symfony")
                                .createNotification(
                                    "Group Configuration Error",
                                    groupError!!,
                                    com.intellij.notification.NotificationType.ERROR
                                )
                                .notify(project)
                        }

                        // Restore selection state after re-creating nodes
                        val shouldCenter = selectedNode != null
                        if (selectedNode != null) {
                            val selectedServiceId = selectedNode!!.service.id
                            selectedNode = nodes.find { it.service.id == selectedServiceId }
                            nodes.forEach { node ->
                                node.isSelected = node == selectedNode
                            }
                        }

                        // Update preferred size based on node positions
                        updatePreferredSize()

                        revalidate()
                        repaint()

                        // Re-apply search if there's an active search query
                        if (searchField.text.trim().isNotEmpty()) {
                            performSearch()
                        }

                        // Center on selected node after layout is complete
                        if (shouldCenter && selectedNode != null) {
                            SwingUtilities.invokeLater {
                                centerOnNode(selectedNode!!)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // On error, restore previous state
                    SwingUtilities.invokeLater {
                        isLoading = false
                        nodes = previousNodes
                        edges = previousEdges
                        repaint()
                    }
                    e.printStackTrace()
                }
            }
        }

        /**
         * Compute ELK hierarchical layout (runs on background thread)
         */
        private fun computeHierarchicalLayout(
            groupAssignments: GroupAssignmentResult,
            groups: List<ServiceGroup>,
            layoutNodes: List<Node>,
            layoutEdges: List<Edge>
        ): LayoutResult {
            if (layoutNodes.isEmpty()) {
                return LayoutResult(layoutNodes, layoutEdges, emptyList(), groupAssignments.error)
            }

            try {
                val elkAdapter = ElkLayoutAdapter()

                // Prepare input for ELK
                val nodeInputs = layoutNodes.map { node ->
                    ElkLayoutAdapter.NodeInput(
                        id = node.service.id,
                        width = node.width,
                        height = node.height
                    )
                }

                val edgeInputs = layoutEdges.map { edge ->
                    ElkLayoutAdapter.EdgeInput(
                        fromId = edge.from.service.id,
                        toId = edge.to.service.id
                    )
                }

                // Prepare group inputs if no errors
                val groupInputs = if (groupAssignments.error == null) {
                    val nodesByGroup = groupAssignments.assignments.entries.groupBy({ it.value }, { it.key })
                    nodesByGroup.map { (group, groupNodes) ->
                        ElkLayoutAdapter.GroupInput(
                            id = "group_${group.name}",
                            nodeIds = groupNodes.map { it.service.id }
                        )
                    }
                } else {
                    emptyList()
                }

                // Compute layout using ELK with groups (THIS IS THE SLOW PART - but now on background thread)
                val elkLayoutResult = elkAdapter.computeLayout(nodeInputs, edgeInputs, groupInputs)

                // Apply layout results to nodes
                val nodeLayoutMap = elkLayoutResult.nodes.associateBy { it.id }
                layoutNodes.forEach { node ->
                    val layout = nodeLayoutMap[node.service.id]
                    if (layout != null) {
                        node.x = layout.x + 50.0 // Add padding
                        node.y = layout.y + 50.0
                    }
                }

                // Create group boxes from layout results
                val resultGroupBoxes = elkLayoutResult.groups.map { groupLayout ->
                    val groupName = groupLayout.groupId.removePrefix("group_")
                    val group = groups.find { it.name == groupName }
                    if (group != null) {
                        GroupBox(
                            group = group,
                            x = groupLayout.x + 50.0,
                            y = groupLayout.y + 50.0,
                            width = groupLayout.width,
                            height = groupLayout.height
                        )
                    } else {
                        null
                    }
                }.filterNotNull()

                // Store edge routing paths
                val edgePathMap = elkLayoutResult.edges.associate { edgeLayout ->
                    val edge = layoutEdges.find { it.from.service.id == edgeLayout.fromId && it.to.service.id == edgeLayout.toId }
                    edge to edgeLayout.points.map { Point2D(it.x + 50.0, it.y + 50.0) }
                }
                layoutEdges.forEach { edge ->
                    edge.elkPath = edgePathMap[edge] ?: emptyList()
                }

                return LayoutResult(layoutNodes, layoutEdges, resultGroupBoxes, groupAssignments.error)

            } catch (e: Exception) {
                // Fallback to simple grid layout if ELK fails
                println("ELK layout failed: ${e.message}")
                e.printStackTrace()
                applySimpleGridLayoutToNodes(layoutNodes)
                return LayoutResult(layoutNodes, layoutEdges, emptyList(), groupAssignments.error)
            }
        }

        /**
         * Simple fallback layout in case ELK fails
         */
        private fun applySimpleGridLayoutToNodes(nodesToLayout: List<Node>) {
            val startX = 50.0
            val startY = 50.0
            val spacingX = 250.0
            val spacingY = 120.0
            val cols = (sqrt(nodesToLayout.size.toDouble()) * 1.5).toInt().coerceAtLeast(1)

            nodesToLayout.forEachIndexed { index, node ->
                val row = index / cols
                val col = index % cols
                node.x = startX + col * spacingX
                node.y = startY + row * spacingY
            }
        }

        private fun calculateGroupAssignmentsForLayout(config: codes.konstantin.containerinspector.model.Config, groups: List<ServiceGroup>, nodesToLayout: List<Node>): GroupAssignmentResult {
            if (groups.isEmpty()) {
                println("DEBUG: No groups configured")
                return GroupAssignmentResult(emptyMap(), null)
            }

            val assignments = mutableMapOf<Node, ServiceGroup>()

            // Assign each node to matching groups
            for (node in nodesToLayout) {
                val matchingGroups = groups.filter { group ->
                    val matches = group.matches(node.service)
                    if (matches) {
                        println("DEBUG: Service '${node.service.id}' matches group '${group.name}'")
                    }
                    matches
                }
                when {
                    matchingGroups.isEmpty() -> continue
                    matchingGroups.size == 1 -> assignments[node] = matchingGroups.first()
                    matchingGroups.size > 1 -> {
                        // Conflict: service matches multiple groups
                        val groupNames = matchingGroups.joinToString(", ") { it.name }
                        return GroupAssignmentResult(
                            emptyMap(),
                            "Service '${node.service.id}' belongs to multiple groups: $groupNames"
                        )
                    }
                }
            }

            return GroupAssignmentResult(assignments, null)
        }

        private fun loadGroups(config: codes.konstantin.containerinspector.model.Config): List<ServiceGroup> {
            return config.groups
        }

        private fun updatePreferredSize() {
            if (nodes.isEmpty()) return

            // Calculate bounds including node width/height
            val maxX = nodes.maxOfOrNull { it.x + it.width } ?: 0.0
            val maxY = nodes.maxOfOrNull { it.y + it.height } ?: 0.0

            // Also consider group boxes
            val groupMaxX = groupBoxes.maxOfOrNull { it.x + it.width } ?: 0.0
            val groupMaxY = groupBoxes.maxOfOrNull { it.y + it.height } ?: 0.0

            // Consider edge routing paths (bendpoints can extend beyond nodes)
            val edgeMaxX = edges.flatMap { it.getRoutingPath() }.maxOfOrNull { it.x } ?: 0.0
            val edgeMaxY = edges.flatMap { it.getRoutingPath() }.maxOfOrNull { it.y } ?: 0.0

            val contentWidth = max(max(maxX, groupMaxX), edgeMaxX)
            val contentHeight = max(max(maxY, groupMaxY), edgeMaxY)

            // Apply zoom factor and add padding
            preferredSize = Dimension(
                ((contentWidth + 200) * zoomFactor).toInt(),
                ((contentHeight + 200) * zoomFactor).toInt()
            )
        }

        fun clear() {
            nodes = emptyList()
            edges = emptyList()
            highlightedNodes = emptySet()
            currentResultNode = null
            repaint()
        }

        fun highlightNodes(results: List<Node>, currentResult: Node?) {
            highlightedNodes = results.toSet()
            currentResultNode = currentResult
            nodes.forEach { node ->
                node.isHighlighted = node in highlightedNodes
                node.isCurrentResult = node == currentResultNode
            }
            repaint()
        }

        fun getSelectedService(): ServiceDefinition? {
            return selectedNode?.service
        }

        fun selectNodeByClassName(className: String) {
            val matchingNode = nodes.firstOrNull { node ->
                node.service.className == className
            }

            if (matchingNode != null) {
                // Update selection
                val previousSelection = selectedNode
                selectedNode = matchingNode
                nodes.forEach { it.isSelected = it == selectedNode }

                // Notify listener about selection change
                currentGraph?.let { graph ->
                    val dependencies = graph.getDirectDependencies(matchingNode.service.id).size
                    val dependents = graph.getDirectDependents(matchingNode.service.id).size
                    onSelectionChanged?.invoke(matchingNode.service, dependents, dependencies)
                }

                // Only center if the node is not currently visible in viewport
                val viewport = scrollPane.viewport
                val viewRect = viewport.viewRect
                val nodeScreenX = (matchingNode.x * zoomFactor).toInt()
                val nodeScreenY = (matchingNode.y * zoomFactor).toInt()
                val nodeScreenWidth = (matchingNode.width * zoomFactor).toInt()
                val nodeScreenHeight = (matchingNode.height * zoomFactor).toInt()

                val nodeVisible = viewRect.intersects(
                    nodeScreenX.toDouble(),
                    nodeScreenY.toDouble(),
                    nodeScreenWidth.toDouble(),
                    nodeScreenHeight.toDouble()
                )

                if (!nodeVisible) {
                    centerOnNode(matchingNode)
                }

                // Repaint to show selection
                repaint()
            } else if (focusMode) {
                // Node not found in current focused graph - need to find it in the full graph and rebuild
                currentGraph?.let { graph ->
                    val service = graph.getAllServices().firstOrNull { it.className == className }
                    if (service != null) {
                        // Create minify checker to determine minified dependencies
                        val config = settings.getActiveConfig()
                        val minifyChecker = config?.let { MinifyChecker(it) }

                        // Create a temporary node to select it and trigger graph rebuild
                        selectedNode = Node(
                            service = service,
                            x = 0.0,
                            y = 0.0,
                            isVendorFn = { !isAppService(it) },
                            minifiedDeps = graph.getDirectDependencies(service.id)
                                .filter { minifyChecker?.shouldMinify(it) == true }
                        )
                        // Rebuild the graph with the new selection - this will create focused view around new service
                        setGraph(graph)
                    }
                }
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Show loading indicator if loading
            if (isLoading) {
                g2.color = JBColor.GRAY
                g2.font = Font("Dialog", Font.PLAIN, 16)
                val message = "Computing graph layout..."
                val fm = g2.fontMetrics
                val x = (width - fm.stringWidth(message)) / 2
                val y = height / 2
                g2.drawString(message, x, y)
                return
            }

            // Apply zoom transformation
            g2.scale(zoomFactor, zoomFactor)

            // Draw group error if present
            if (groupError != null) {
                println("DEBUG: Drawing group error in paintComponent: $groupError")
                val errorTitle = "⚠ Group Configuration Error"
                val errorMessage = groupError!!

                // Measurements
                val padding = 20
                val titleFont = Font("Dialog", Font.BOLD, 16)
                val messageFont = Font("Dialog", Font.PLAIN, 14)

                g2.font = titleFont
                val titleMetrics = g2.fontMetrics
                val titleWidth = titleMetrics.stringWidth(errorTitle)

                g2.font = messageFont
                val messageMetrics = g2.fontMetrics

                // Word wrap the error message
                val maxWidth = (width / zoomFactor - padding * 4).toInt()
                val wrappedLines = mutableListOf<String>()
                val words = errorMessage.split(" ")
                var currentLine = ""

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (messageMetrics.stringWidth(testLine) <= maxWidth) {
                        currentLine = testLine
                    } else {
                        if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)

                val messageWidth = wrappedLines.maxOfOrNull { messageMetrics.stringWidth(it) } ?: 0
                val boxWidth = maxOf(titleWidth, messageWidth) + padding * 2
                val lineHeight = messageMetrics.height
                val boxHeight = titleMetrics.height + lineHeight * wrappedLines.size + padding * 2

                // Position at top center
                val boxX = ((width / zoomFactor - boxWidth) / 2).toInt()
                val boxY = padding

                // Draw semi-transparent background
                g2.color = Color(255, 240, 240, 240)
                g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 10, 10)

                // Draw border
                g2.color = Color(220, 50, 50)
                g2.stroke = BasicStroke(2f)
                g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 10, 10)

                // Draw title
                g2.color = Color(180, 0, 0)
                g2.font = titleFont
                g2.drawString(errorTitle, boxX + padding, boxY + padding + titleMetrics.ascent)

                // Draw message lines
                g2.color = Color(80, 0, 0)
                g2.font = messageFont
                var yOffset = boxY + padding + titleMetrics.height + lineHeight
                for (line in wrappedLines) {
                    g2.drawString(line, boxX + padding, yOffset)
                    yOffset += lineHeight
                }

                // Reset stroke
                g2.stroke = BasicStroke(1f)
            }

            // Draw group boxes first (behind everything)
            groupBoxes.forEach { box ->
                box.draw(g2)
            }

            // Draw edges (on top of group boxes, behind nodes)
            // First draw normal edges, then highlighted edges on top
            val normalEdges = mutableListOf<Edge>()
            val highlightedEdges = mutableListOf<Edge>()

            // Calculate full trace if enabled
            val upstreamNodes = mutableSetOf<Node>()
            val downstreamNodes = mutableSetOf<Node>()

            if (selectedNode != null && fullTrace && currentGraph != null) {
                // Get all upstream dependencies (recursive)
                val allUpstreamIds = mutableSetOf<String>()
                val toProcess = mutableListOf(selectedNode!!.service.id)
                while (toProcess.isNotEmpty()) {
                    val serviceId = toProcess.removeAt(0)
                    currentGraph!!.getDirectDependencies(serviceId).forEach { dep ->
                        if (dep.id !in allUpstreamIds) {
                            allUpstreamIds.add(dep.id)
                            toProcess.add(dep.id)
                        }
                    }
                }
                upstreamNodes.addAll(nodes.filter { it.service.id in allUpstreamIds })

                // Get all downstream dependents (recursive)
                val allDownstreamIds = mutableSetOf<String>()
                val toProcessDown = mutableListOf(selectedNode!!.service.id)
                while (toProcessDown.isNotEmpty()) {
                    val serviceId = toProcessDown.removeAt(0)
                    currentGraph!!.getDirectDependents(serviceId).forEach { dep ->
                        if (dep.id !in allDownstreamIds) {
                            allDownstreamIds.add(dep.id)
                            toProcessDown.add(dep.id)
                        }
                    }
                }
                downstreamNodes.addAll(nodes.filter { it.service.id in allDownstreamIds })
            }

            edges.forEach { edge ->
                val isDirectUpstream = selectedNode != null && edge.to == selectedNode
                val isDirectDownstream = selectedNode != null && edge.from == selectedNode

                // For full trace: check if both nodes of edge are in the same chain
                // Edge is in upstream chain if both nodes are dependents (or edge points to selected)
                val isInUpstreamChain = fullTrace && selectedNode != null &&
                    ((edge.from in downstreamNodes && edge.to in downstreamNodes) ||
                     (edge.from in downstreamNodes && edge.to == selectedNode))

                // Edge is in downstream chain if both nodes are dependencies (or edge points from selected)
                val isInDownstreamChain = fullTrace && selectedNode != null &&
                    ((edge.from in upstreamNodes && edge.to in upstreamNodes) ||
                     (edge.from == selectedNode && edge.to in upstreamNodes))

                if (isDirectUpstream || isDirectDownstream || isInUpstreamChain || isInDownstreamChain) {
                    highlightedEdges.add(edge)
                } else {
                    normalEdges.add(edge)
                }
            }

            // Draw normal edges first
            normalEdges.forEach { edge ->
                val path = edge.getRoutingPath()

                g2.color = JBColor.GRAY
                g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Draw orthogonal path segments
                for (i in 0 until path.size - 1) {
                    val p1 = path[i]
                    val p2 = path[i + 1]
                    g2.draw(Line2D.Double(p1.x, p1.y, p2.x, p2.y))
                }

                // Draw arrow head at the end
                if (path.size >= 2) {
                    val lastTwo = path.takeLast(2)
                    drawArrowHead(g2, lastTwo[0].x, lastTwo[0].y, lastTwo[1].x, lastTwo[1].y)
                }
            }

            // Draw highlighted edges on top
            highlightedEdges.forEach { edge ->
                val path = edge.getRoutingPath()

                // Determine color based on which chain the edge belongs to
                // Red: dependency chain (what selected depends on)
                // Blue: dependent chain (what depends on selected)
                val isDirectDependency = edge.from == selectedNode
                val isDirectDependent = edge.to == selectedNode
                val isInDependencyChain = (edge.from == selectedNode && edge.to in upstreamNodes) ||
                                          (edge.from in upstreamNodes && edge.to in upstreamNodes)
                val isInDependentChain = (edge.to == selectedNode && edge.from in downstreamNodes) ||
                                         (edge.from in downstreamNodes && edge.to in downstreamNodes)

                g2.color = when {
                    isInDependencyChain || isDirectDependency -> JBColor.RED    // Dependencies (what selected depends on)
                    isInDependentChain || isDirectDependent -> JBColor.BLUE     // Dependents (what depends on selected)
                    else -> JBColor.RED
                }
                g2.stroke = BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Draw orthogonal path segments
                for (i in 0 until path.size - 1) {
                    val p1 = path[i]
                    val p2 = path[i + 1]
                    g2.draw(Line2D.Double(p1.x, p1.y, p2.x, p2.y))
                }

                // Draw arrow head at the end
                if (path.size >= 2) {
                    val lastTwo = path.takeLast(2)
                    drawArrowHead(g2, lastTwo[0].x, lastTwo[0].y, lastTwo[1].x, lastTwo[1].y)
                }
            }

            // Draw nodes (on top of everything)
            nodes.forEach { node ->
                node.draw(g2)
            }

            // Draw tooltip as part of the canvas (on top of everything else)
            if (currentTooltipNode != null && tooltipContent != null) {
                drawTooltip(g2, currentTooltipNode!!, tooltipContent!!)
            }
        }

        private fun drawTooltip(g2: Graphics2D, node: Node, content: String) {
            val lines = content.split("\n")

            // Measure text
            g2.font = Font("Dialog", Font.PLAIN, 11)
            val fm = g2.fontMetrics
            val lineHeight = fm.height
            val maxWidth = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0

            // Tooltip dimensions
            val padding = 8
            val tooltipWidth = maxWidth + padding * 2
            val tooltipHeight = lines.size * lineHeight + padding * 2

            // Position relative to tag icon if it exists, otherwise top right of node
            val tooltipX: Double
            val tooltipY: Double

            val tagIconBounds = node.getTagIconBounds()
            if (tagIconBounds != null) {
                tooltipX = tagIconBounds.x + tagIconBounds.width + 5
                tooltipY = tagIconBounds.y
            } else {
                // No tag icon, position at top right of service box
                tooltipX = node.x + node.width + 5
                tooltipY = node.y
            }

            // Draw background with border
            g2.color = JBColor.background()
            g2.fillRect(tooltipX.toInt(), tooltipY.toInt(), tooltipWidth, tooltipHeight)

            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1.0f)
            g2.drawRect(tooltipX.toInt(), tooltipY.toInt(), tooltipWidth, tooltipHeight)

            // Draw text lines
            g2.color = JBColor.foreground()
            var currentY = tooltipY + padding + fm.ascent
            lines.forEach { line ->
                g2.drawString(line, (tooltipX + padding).toFloat(), currentY.toFloat())
                currentY += lineHeight
            }
        }

        private fun drawArrowHead(g2: Graphics2D, x1: Double, y1: Double, x2: Double, y2: Double) {
            val dx = x2 - x1
            val dy = y2 - y1
            val angle = kotlin.math.atan2(dy, dx)
            val arrowLength = 10
            val arrowAngle = Math.PI / 6

            val x3 = x2 - arrowLength * kotlin.math.cos(angle - arrowAngle)
            val y3 = y2 - arrowLength * kotlin.math.sin(angle - arrowAngle)
            val x4 = x2 - arrowLength * kotlin.math.cos(angle + arrowAngle)
            val y4 = y2 - arrowLength * kotlin.math.sin(angle + arrowAngle)

            g2.drawLine(x2.toInt(), y2.toInt(), x3.toInt(), y3.toInt())
            g2.drawLine(x2.toInt(), y2.toInt(), x4.toInt(), y4.toInt())
        }
    }

    private data class LayoutResult(
        val nodes: List<Node>,
        val edges: List<Edge>,
        val groupBoxes: List<GroupBox>,
        val groupError: String?
    )

    private data class Node(
        val service: ServiceDefinition,
        var x: Double,
        var y: Double,
        val isVendorFn: (ServiceDefinition) -> Boolean,
        val minifiedDeps: List<ServiceDefinition> = emptyList(),
        var isHovered: Boolean = false,
        var isHighlighted: Boolean = false,
        var isCurrentResult: Boolean = false,
        var isSelected: Boolean = false,
        var isTagIconHovered: Boolean = false
    ) {
        companion object {
            const val MIN_WIDTH = 150
            const val MIN_HEIGHT = 70
            const val PADDING = 10
        }

        var width: Double = MIN_WIDTH.toDouble()
        var height: Double = MIN_HEIGHT.toDouble()

        init {
            calculateSize()
        }

        private fun calculateSize() {
            // Create temporary graphics to measure text
            val dummyImage = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2 = dummyImage.createGraphics()

            val serviceIdDiffers = service.className != null && service.className != service.id

            val maxTextWidth: Int
            var totalHeight = PADDING * 2

            if (serviceIdDiffers) {
                // Layout: FQCN (small) + @serviceId (large)
                val fullClassName = service.className ?: service.id

                // Measure FQCN
                g2.font = Font("Dialog", Font.PLAIN, 9)
                val fqcnFm = g2.fontMetrics
                val fqcnWidth = fqcnFm.stringWidth(fullClassName)

                // Measure @serviceId
                g2.font = Font("Dialog", Font.BOLD, 13)
                val serviceIdFm = g2.fontMetrics
                val serviceIdText = "@${service.id}"
                val serviceIdWidth = serviceIdFm.stringWidth(serviceIdText)

                maxTextWidth = max(fqcnWidth, serviceIdWidth)
                totalHeight += fqcnFm.height + 3 + serviceIdFm.height
            } else {
                // Layout: namespace (small) + className (large)
                val (namespace, className) = parseClassName()

                // Measure namespace
                g2.font = Font("Dialog", Font.PLAIN, 9)
                val namespaceFm = g2.fontMetrics
                val namespaceWidth = if (namespace.isNotEmpty()) namespaceFm.stringWidth(namespace) else 0

                // Measure class name
                g2.font = Font("Dialog", Font.BOLD, 13)
                val classNameFm = g2.fontMetrics
                val classNameWidth = classNameFm.stringWidth(className)

                maxTextWidth = max(namespaceWidth, classNameWidth)
                if (namespace.isNotEmpty()) totalHeight += namespaceFm.height + 3
                totalHeight += classNameFm.height
            }

            // Measure minified dependencies
            g2.font = Font("Dialog", Font.PLAIN, 9)
            val minifiedFm = g2.fontMetrics
            val minifiedWidths = minifiedDeps.map { minifiedFm.stringWidth("@ ${it.id}") }
            val maxMinifiedWidth = minifiedWidths.maxOrNull() ?: 0

            g2.dispose()

            // Calculate required dimensions
            val finalMaxWidth = max(maxTextWidth, maxMinifiedWidth)
            width = max(MIN_WIDTH.toDouble(), finalMaxWidth + PADDING * 2.0)

            // Add minified dependencies to height
            if (minifiedDeps.isNotEmpty()) {
                totalHeight += 5 + (minifiedDeps.size * (minifiedFm.height + 2))
            }

            height = max(MIN_HEIGHT.toDouble(), totalHeight.toDouble())
        }

        private fun parseClassName(): Pair<String, String> {
            val fullName = service.className ?: service.id

            // Try to extract namespace and class name
            val lastSlash = fullName.lastIndexOf('\\')
            val lastDot = fullName.lastIndexOf('.')
            val separator = max(lastSlash, lastDot)

            return if (separator > 0) {
                val namespace = fullName.substring(0, separator)
                val className = fullName.substring(separator + 1)
                Pair(namespace, className)
            } else {
                Pair("", fullName)
            }
        }

        fun contains(point: Point): Boolean {
            return point.x >= x && point.x <= x + width &&
                    point.y >= y && point.y <= y + height
        }

        fun getAliasIconBounds(): Rectangle2D.Double? {
            if (service.aliases.isEmpty()) return null
            val iconSize = 16.0
            val tagIconOffset = if (service.tags.isNotEmpty()) iconSize + 4 else 0.0
            // Position icon to the left of the tag icon (or at top right if no tags)
            val centerX = x + width - tagIconOffset
            val centerY = y
            val iconX = centerX - iconSize / 2
            val iconY = centerY - iconSize / 2
            return Rectangle2D.Double(iconX, iconY, iconSize, iconSize)
        }

        fun getTagIconBounds(): Rectangle2D.Double? {
            if (service.tags.isEmpty()) return null
            val iconSize = 16.0
            // Position icon so its center point is at the top right corner of the service box
            val centerX = x + width  // Corner X coordinate
            val centerY = y          // Corner Y coordinate
            val iconX = centerX - iconSize / 2
            val iconY = centerY - iconSize / 2
            return Rectangle2D.Double(iconX, iconY, iconSize, iconSize)
        }

        fun containsTagIcon(point: Point): Boolean {
            val bounds = getTagIconBounds() ?: return false
            return point.x >= bounds.x && point.x <= bounds.x + bounds.width &&
                    point.y >= bounds.y && point.y <= bounds.y + bounds.height
        }

        fun draw(g2: Graphics2D) {
            // Background with solid color
            val isVendor = isVendorFn(service)
            g2.color = when {
                isSelected -> JBColor(Color(255, 255, 200), Color(120, 120, 60))  // Yellow for selection
                isHovered -> JBColor(Color(200, 230, 255), Color(100, 150, 200))
                isVendor -> JBColor(Color(240, 240, 240), Color(80, 80, 80))
                else -> JBColor.WHITE
            }
            g2.fill(Rectangle2D.Double(x, y, width, height))

            // Border
            g2.color = when {
                isSelected -> JBColor(Color(200, 150, 0), Color(200, 200, 100))  // Dark yellow/gold for selected
                isCurrentResult -> JBColor.RED
                isHighlighted -> JBColor.ORANGE
                service.isPublic -> JBColor.BLUE
                else -> JBColor.GRAY
            }
            g2.stroke = BasicStroke(
                when {
                    isSelected -> 4.0f
                    isCurrentResult -> 3.0f
                    isHighlighted || service.isPublic -> 2.0f
                    else -> 1.0f
                }
            )
            g2.draw(Rectangle2D.Double(x, y, width, height))

            var currentY = y + PADDING

            // Check if service ID differs from class name
            val serviceIdDiffers = service.className != null && service.className != service.id

            if (serviceIdDiffers) {
                // For services with different ID: show full FQCN on top, @serviceId below
                val fullClassName = service.className ?: service.id

                // Draw FQCN (small font, gray)
                g2.color = JBColor.GRAY
                g2.font = Font("Dialog", Font.PLAIN, 9)
                val fqcnFm = g2.fontMetrics
                val maxFqcnWidth = (width - PADDING * 2).toInt()
                val truncatedFqcn = truncateText(fullClassName, fqcnFm, maxFqcnWidth)

                g2.drawString(truncatedFqcn, (x + PADDING).toFloat(), (currentY + fqcnFm.ascent).toFloat())
                currentY += fqcnFm.height + 3

                // Draw @serviceId (large font, bold)
                g2.color = JBColor.foreground()
                g2.font = Font("Dialog", Font.BOLD, 13)
                val serviceIdFm = g2.fontMetrics
                val serviceIdText = "@${service.id}"
                val truncatedServiceId = truncateText(serviceIdText, serviceIdFm, (width - PADDING * 2).toInt())

                val serviceIdX = (x + PADDING).toFloat()
                val serviceIdY = (currentY + serviceIdFm.ascent).toFloat()
                g2.drawString(truncatedServiceId, serviceIdX, serviceIdY)

                // Draw strikethrough if deprecated
                if (service.isDeprecated) {
                    val textWidth = serviceIdFm.stringWidth(truncatedServiceId)
                    val strikeY = serviceIdY - serviceIdFm.ascent / 2
                    g2.drawLine(serviceIdX.toInt(), strikeY.toInt(), (serviceIdX + textWidth).toInt(), strikeY.toInt())
                }

                currentY += serviceIdFm.height
            } else {
                // For normal services: show namespace on top, class name below
                val (namespace, className) = parseClassName()

                // Draw namespace (small font)
                if (namespace.isNotEmpty()) {
                    g2.color = JBColor.GRAY
                    g2.font = Font("Dialog", Font.PLAIN, 9)
                    val namespaceFm = g2.fontMetrics

                    // Truncate namespace if too long
                    val maxNamespaceWidth = (width - PADDING * 2).toInt()
                    val truncatedNamespace = truncateText(namespace, namespaceFm, maxNamespaceWidth)

                    g2.drawString(truncatedNamespace, (x + PADDING).toFloat(), (currentY + namespaceFm.ascent).toFloat())
                    currentY += namespaceFm.height + 3
                }

                // Draw class name (larger font)
                g2.color = JBColor.foreground()
                g2.font = Font("Dialog", Font.BOLD, 13)
                val classNameFm = g2.fontMetrics

                val classNameX = (x + PADDING).toFloat()
                val classNameY = (currentY + classNameFm.ascent).toFloat()
                g2.drawString(className, classNameX, classNameY)

                // Draw strikethrough if deprecated
                if (service.isDeprecated) {
                    val textWidth = classNameFm.stringWidth(className)
                    val strikeY = classNameY - classNameFm.ascent / 2
                    g2.drawLine(classNameX.toInt(), strikeY.toInt(), (classNameX + textWidth).toInt(), strikeY.toInt())
                }

                currentY += classNameFm.height
            }

            // Draw minified dependencies with @ symbol and underlined service IDs
            if (minifiedDeps.isNotEmpty()) {
                currentY += 5
                g2.color = JBColor.GRAY
                g2.font = Font("Dialog", Font.PLAIN, 9)
                g2.stroke = BasicStroke(1.0f)  // Reset stroke to thin line
                val minifiedFm = g2.fontMetrics

                minifiedDeps.forEach { dep ->
                    val atSymbol = "@ "
                    val serviceId = dep.id

                    // Draw @ symbol
                    val atX = (x + PADDING).toFloat()
                    val atY = (currentY + minifiedFm.ascent).toFloat()
                    g2.drawString(atSymbol, atX, atY)

                    // Calculate position for service ID
                    val atWidth = minifiedFm.stringWidth(atSymbol)
                    val serviceIdX = atX + atWidth

                    // Truncate service ID if needed
                    val maxServiceIdWidth = (width - PADDING * 2 - atWidth).toInt()
                    val truncatedServiceId = truncateText(serviceId, minifiedFm, maxServiceIdWidth)

                    // Draw underlined service ID
                    g2.drawString(truncatedServiceId, serviceIdX, atY)

                    // Draw underline with thin stroke
                    val serviceIdWidth = minifiedFm.stringWidth(truncatedServiceId)
                    val underlineY = atY.toInt() + 1
                    g2.drawLine(serviceIdX.toInt(), underlineY, (serviceIdX + serviceIdWidth).toInt(), underlineY)

                    currentY += minifiedFm.height + 2
                }
            }

            // Draw service flags (without tags count)
            currentY += 3
            g2.font = Font("Dialog", Font.PLAIN, 8)
            val flagFm = g2.fontMetrics
            val flags = mutableListOf<String>()

            if (service.isPublic) flags.add("PUBLIC")
            if (service.isLazy) flags.add("LAZY")
            if (!service.isAutowired) flags.add("!autowired")
            if (!service.isAutoconfigured) flags.add("!autoconfigured")
            if (service.isDeprecated) flags.add("DEPRECATED")

            if (flags.isNotEmpty()) {
                g2.color = JBColor.GRAY
                val flagsText = flags.joinToString(" ")
                val truncatedFlags = truncateText(flagsText, flagFm, (width - PADDING * 2).toInt())
                g2.drawString(truncatedFlags, (x + PADDING).toFloat(), (currentY + flagFm.ascent).toFloat())
            }

            // Draw alias icon to the left of tag icon if service has aliases
            if (service.aliases.isNotEmpty()) {
                val iconBounds = getAliasIconBounds()!!
                val iconX = iconBounds.x
                val iconY = iconBounds.y
                val iconSize = iconBounds.width

                // Draw alias icon (overlapping circles representing linked services)
                g2.color = JBColor(Color(255, 140, 0), Color(200, 120, 50)) // Orange color
                g2.stroke = BasicStroke(2.5f) // Thicker stroke

                // Draw two overlapping circles
                val circleSize = (iconSize * 0.6).toInt()
                val offset = (iconSize * 0.2).toInt()
                g2.drawOval((iconX + offset).toInt(), (iconY + offset).toInt(), circleSize, circleSize)
                g2.drawOval((iconX + offset + 5).toInt(), (iconY + offset).toInt(), circleSize, circleSize)

                // Draw a thicker link line between them
                g2.stroke = BasicStroke(2.0f)
                g2.drawLine(
                    (iconX + offset + circleSize / 2).toInt(),
                    (iconY + offset + circleSize / 2).toInt(),
                    (iconX + offset + 5 + circleSize / 2).toInt(),
                    (iconY + offset + circleSize / 2).toInt()
                )
            }

            // Draw tag icon in top right corner if service has tags
            if (service.tags.isNotEmpty()) {
                val iconBounds = getTagIconBounds()!!
                val iconX = iconBounds.x
                val iconY = iconBounds.y
                val iconSize = iconBounds.width

                // Draw a simple tag icon (a rectangle with a corner cut)
                g2.color = JBColor(Color(100, 150, 255), Color(150, 180, 220))
                val tagPath = java.awt.Polygon()
                tagPath.addPoint(iconX.toInt(), iconY.toInt())
                tagPath.addPoint((iconX + iconSize * 0.7).toInt(), iconY.toInt())
                tagPath.addPoint((iconX + iconSize).toInt(), (iconY + iconSize * 0.3).toInt())
                tagPath.addPoint((iconX + iconSize).toInt(), (iconY + iconSize).toInt())
                tagPath.addPoint(iconX.toInt(), (iconY + iconSize).toInt())
                g2.fill(tagPath)

                // Draw small circle (tag hole) - use background color
                val holeSize = 3
                g2.color = when {
                    isSelected -> JBColor(Color(255, 255, 200), Color(120, 120, 60))
                    isHovered -> JBColor(Color(200, 230, 255), Color(100, 150, 200))
                    isVendor -> JBColor(Color(240, 240, 240), Color(80, 80, 80))
                    else -> JBColor.WHITE
                }
                g2.fillOval((iconX + 3).toInt(), (iconY + 3).toInt(), holeSize, holeSize)
            }
        }

        private fun truncateText(text: String, fm: FontMetrics, maxWidth: Int): String {
            if (fm.stringWidth(text) <= maxWidth) return text

            var truncated = text
            while (truncated.isNotEmpty() && fm.stringWidth("$truncated...") > maxWidth) {
                truncated = truncated.dropLast(1)
            }
            return if (truncated.isEmpty()) "..." else "$truncated..."
        }
    }

    private data class Edge(val from: Node, val to: Node) {
        // ELK-computed routing path
        var elkPath: List<Point2D> = emptyList()

        /**
         * Get the routing path for this edge
         * Uses ELK path if available, otherwise computes simple orthogonal path
         */
        fun getRoutingPath(): List<Point2D> {
            if (elkPath.isNotEmpty()) {
                return elkPath
            }

            // Fallback: calculate simple orthogonal routing path
            val points = mutableListOf<Point2D>()

            // Start point (bottom center of source)
            val startX = from.x + from.width / 2
            val startY = from.y + from.height

            // End point (top center of target)
            val endX = to.x + to.width / 2
            val endY = to.y

            points.add(Point2D(startX, startY))

            // If nodes are in different layers (hierarchical)
            if (kotlin.math.abs(startY - endY) > 50) {
                // Add intermediate point for orthogonal routing
                val midY = (startY + endY) / 2
                points.add(Point2D(startX, midY))
                points.add(Point2D(endX, midY))
            } else {
                // Same layer - use simple path
                points.add(Point2D(endX, startY))
            }

            points.add(Point2D(endX, endY))

            return points
        }
    }

    private data class Point2D(var x: Double, var y: Double)

    private data class GroupAssignmentResult(
        val assignments: Map<Node, ServiceGroup>,
        val error: String?
    )

    private data class GroupBox(
        val group: ServiceGroup,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    ) {
        fun draw(g2: Graphics2D) {
            // Draw filled background
            g2.color = JBColor(Color(230, 240, 255, 100), Color(60, 80, 120, 100))
            g2.fill(Rectangle2D.Double(x, y, width, height))

            // Draw border
            g2.color = JBColor(Color(100, 150, 255), Color(150, 180, 220))
            g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10.0f, floatArrayOf(10.0f, 5.0f), 0.0f)
            g2.draw(Rectangle2D.Double(x, y, width, height))

            // Draw group name
            g2.color = JBColor(Color(50, 100, 200), Color(180, 200, 240))
            g2.font = Font("Dialog", Font.BOLD, 12)
            g2.stroke = BasicStroke(1.0f)
            g2.drawString(group.name, (x + 10).toFloat(), (y + 15).toFloat())
        }
    }
}
