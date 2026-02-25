package codes.konstantin.containerinspector.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.jetbrains.php.PhpIndex

/**
 * Helper for navigating to PHP classes from service definitions
 */
object NavigationHelper {

    /**
     * Navigate to a PHP class by fully qualified name
     * This method should be called from a background thread (not EDT)
     */
    fun navigateToClass(project: Project, className: String) {
        try {
            // Run the index access in a read action on background thread
            val navigatable: Navigatable? = ReadAction.computeCancellable<Navigatable?, Exception> {
                val phpIndex = PhpIndex.getInstance(project)
                val classes = phpIndex.getClassesByFQN(className)
                if (classes.isNotEmpty()) classes.first() as? Navigatable else null
            }

            // Navigate on EDT
            if (navigatable != null) {
                ApplicationManager.getApplication().invokeLater {
                    navigatable.navigate(true)
                }
            }
        } catch (e: Exception) {
            // Silently fail - navigation not always possible
        }
    }
}
