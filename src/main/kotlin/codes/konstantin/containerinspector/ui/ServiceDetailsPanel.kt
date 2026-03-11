package codes.konstantin.containerinspector.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import codes.konstantin.containerinspector.model.ServiceDefinition
import codes.konstantin.containerinspector.service.ContainerService
import codes.konstantin.containerinspector.service.CoverageService
import codes.konstantin.containerinspector.settings.SymfonyContainerSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel for displaying service details and dependencies
 */
class ServiceDetailsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val containerService = ContainerService.getInstance(project)
    private val coverageService = CoverageService.getInstance(project)
    private val settings = SymfonyContainerSettings.getInstance(project)

    private val coverageIndicator = CoverageIndicator()
    private val contentPanel = JPanel()
    private val scrollPane = JBScrollPane(contentPanel)
    private var currentCoveragePercentage: Double? = null

    init {
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        // Layout: coverage bar at top (fixed), content scrollable below
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(coverageIndicator, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        add(mainPanel, BorderLayout.CENTER)
        border = JBUI.Borders.empty(5)
    }

    fun showService(service: ServiceDefinition) {
        contentPanel.removeAll()

        // Update coverage indicator and get coverage percentage
        currentCoveragePercentage = null

        if (service.className != null && coverageService.isCoverageAvailable()) {
            val coverage = coverageService.getCoveragePercentage(service.className)
            if (coverage != null) {
                currentCoveragePercentage = coverage
                coverageIndicator.setCoverage(coverage)
                coverageIndicator.isVisible = true
            } else {
                coverageIndicator.isVisible = false
            }
        } else {
            coverageIndicator.isVisible = false
        }

        // Service header with coverage percentage
        addServiceHeaderSection("Service", service.id)

        // Class
        if (service.className != null && service.className != service.id) {
            addSection("Class", service.className)
        }

        // Properties
        val properties = buildList {
            if (service.isPublic) add("public")
            if (service.isAutowired) add("autowired")
            if (service.isAutoconfigured) add("autoconfigured")
            if (service.isLazy) add("lazy")
            if (service.isDeprecated) add("deprecated")
            if (service.isSynthetic) add("synthetic")
            if (service.isAbstract) add("abstract")
        }
        if (properties.isNotEmpty()) {
            addSection("Properties", properties.joinToString(", "))
        }

        // Aliases
        if (service.aliases.isNotEmpty()) {
            addListSection("Aliases", service.aliases)
        }

        // Tags
        if (service.tags.isNotEmpty()) {
            addTagsSection(service.tags)
        }

        // Separator
        contentPanel.add(Box.createVerticalStrut(10))
        contentPanel.add(JSeparator())
        contentPanel.add(Box.createVerticalStrut(10))

        // Dependencies (outbound)
        val graph = containerService.getGraph()
        if (graph != null) {
            val dependencies = graph.getDependenciesRecursive(service.id, settings.defaultExpansionDepth)
            addDependencySection("Dependencies (what this service depends on)", dependencies)

            contentPanel.add(Box.createVerticalStrut(10))
            contentPanel.add(JSeparator())
            contentPanel.add(Box.createVerticalStrut(10))

            // Dependents (inbound)
            val dependents = graph.getDependentsRecursive(service.id, settings.defaultExpansionDepth)
            addDependencySection("Used by (what depends on this service)", dependents)
        }

        contentPanel.add(Box.createVerticalGlue())
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    fun clear() {
        contentPanel.removeAll()
        currentCoveragePercentage = null
        coverageIndicator.isVisible = false
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun addServiceHeaderSection(label: String, value: String) {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(2, 5)

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 0.0

        val labelComponent = JBLabel("$label: ").apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(labelComponent, gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        val valueComponent = JBLabel(value).apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(valueComponent, gbc)

        // Add coverage percentage if available
        if (currentCoveragePercentage != null) {
            gbc.gridx = 2
            gbc.weightx = 0.0
            val coverageText = String.format("%.1f%%", currentCoveragePercentage)
            val coverageLabel = JBLabel(coverageText).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = when {
                    currentCoveragePercentage!! >= 80.0 -> Color(76, 175, 80) // Green
                    currentCoveragePercentage!! >= 60.0 -> Color(255, 193, 7) // Yellow/Orange
                    else -> Color(244, 67, 54) // Red
                }
                border = JBUI.Borders.empty(0, 10, 0, 0)
            }
            panel.add(coverageLabel, gbc)
        }

        contentPanel.add(panel)
    }

    private fun addSection(label: String, value: String, bold: Boolean = false) {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(2, 5)

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 0.0

        val labelComponent = JBLabel("$label: ").apply {
            if (bold) font = font.deriveFont(Font.BOLD)
        }
        panel.add(labelComponent, gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        val valueComponent = JBLabel(value).apply {
            if (bold) font = font.deriveFont(Font.BOLD)
        }
        panel.add(valueComponent, gbc)

        contentPanel.add(panel)
    }

    private fun addListSection(label: String, items: List<String>) {
        val titleLabel = JBLabel(label).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(5, 5, 2, 5)
        }
        contentPanel.add(titleLabel)

        items.forEach { item ->
            val itemLabel = JBLabel("  • $item").apply {
                border = JBUI.Borders.empty(2, 15, 2, 5)
            }
            contentPanel.add(itemLabel)
        }
    }

    private fun addTagsSection(tags: List<codes.konstantin.containerinspector.model.Tag>) {
        val titleLabel = JBLabel("Tags").apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(5, 5, 2, 5)
        }
        contentPanel.add(titleLabel)

        tags.forEach { tag ->
            if (tag.parameters.isEmpty()) {
                val tagLabel = JBLabel("  • ${tag.name}").apply {
                    border = JBUI.Borders.empty(2, 15, 2, 5)
                }
                contentPanel.add(tagLabel)
            } else {
                val tagLabel = JBLabel("  • ${tag.name}").apply {
                    border = JBUI.Borders.empty(2, 15, 0, 5)
                }
                contentPanel.add(tagLabel)

                tag.parameters.forEach { (key, value) ->
                    val paramLabel = JBLabel("    - $key: $value").apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.PLAIN, font.size - 1f)
                        border = JBUI.Borders.empty(0, 25, 0, 5)
                    }
                    contentPanel.add(paramLabel)
                }
                contentPanel.add(Box.createVerticalStrut(2))
            }
        }
    }

    private fun addDependencySection(title: String, services: Map<Int, List<ServiceDefinition>>) {
        val titleLabel = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(5)
        }
        contentPanel.add(titleLabel)

        if (services.isEmpty()) {
            val noneLabel = JBLabel("  None").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(2, 10)
            }
            contentPanel.add(noneLabel)
        } else {
            services.entries.sortedBy { it.key }.forEach { (depth, serviceList) ->
                val depthLabel = JBLabel("  Level $depth:").apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(2, 10)
                }
                contentPanel.add(depthLabel)

                serviceList.forEach { service ->
                    addClickableService(service, depth)
                }
            }
        }
    }

    private fun addClickableService(service: ServiceDefinition, indent: Int) {
        val indentSize = 20 + (indent * 10)
        val label = JBLabel("${service.getShortName()} (${service.id})").apply {
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(2, indentSize)
        }

        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (service.className != null) {
                    NavigationHelper.navigateToClass(project, service.className)
                }
            }

            override fun mouseEntered(e: MouseEvent) {
                label.text = "<html><u>${service.getShortName()} (${service.id})</u></html>"
            }

            override fun mouseExited(e: MouseEvent) {
                label.text = "${service.getShortName()} (${service.id})"
            }
        })

        contentPanel.add(label)
    }

    /**
     * Custom component for displaying code coverage as a progress bar
     */
    private class CoverageIndicator : JPanel() {
        private var coveragePercentage: Double = 0.0

        init {
            preferredSize = java.awt.Dimension(0, 30)
            minimumSize = java.awt.Dimension(0, 30)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 30)
            isVisible = false
        }

        fun setCoverage(percentage: Double) {
            coveragePercentage = percentage.coerceIn(0.0, 100.0)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val width = width
            val height = height

            // Background
            g.color = JBColor.LIGHT_GRAY
            g.fillRect(0, 0, width, height)

            // Calculate coverage bar width
            val coverageWidth = (width * (coveragePercentage / 100.0)).toInt()

            // Determine color based on coverage percentage
            val coverageColor = when {
                coveragePercentage >= 80.0 -> Color(76, 175, 80) // Green
                coveragePercentage >= 60.0 -> Color(255, 193, 7) // Yellow/Orange
                else -> Color(244, 67, 54) // Red
            }

            // Draw coverage bar
            g.color = coverageColor
            g.fillRect(0, 0, coverageWidth, height)

            // Draw text
            val text = String.format("%.1f%% coverage", coveragePercentage)
            g.color = JBColor.BLACK
            val fontMetrics = g.fontMetrics
            val textWidth = fontMetrics.stringWidth(text)
            val textHeight = fontMetrics.height
            val x = (width - textWidth) / 2
            val y = (height - textHeight) / 2 + fontMetrics.ascent
            g.drawString(text, x, y)
        }
    }
}
