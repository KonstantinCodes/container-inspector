package codes.konstantin.containerinspector.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Symfony Container tool window
 */
class SymfonyContainerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val containerPanel = SymfonyContainerPanel(project)
        val content = ContentFactory.getInstance().createContent(containerPanel, "", false)
        content.setDisposer(containerPanel.graphPanel)
        toolWindow.contentManager.addContent(content)
    }
}
