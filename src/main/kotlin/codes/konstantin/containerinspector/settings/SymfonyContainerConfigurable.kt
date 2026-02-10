package codes.konstantin.containerinspector.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class SymfonyContainerConfigurable(private val project: Project) : Configurable {

    private var depthValue = 2
    private var hideVendorValue = false
    private var namespaceFilterValue = ""
    private var caseSensitiveValue = false

    override fun getDisplayName(): String {
        return "Container Inspector for Symfony"
    }

    override fun createComponent(): JComponent {
        val settings = SymfonyContainerSettings.getInstance(project)

        // Initialize values from settings
        depthValue = settings.defaultExpansionDepth
        hideVendorValue = settings.hideVendorServicesByDefault
        namespaceFilterValue = settings.namespaceFilter
        caseSensitiveValue = settings.caseSensitiveSearch

        return panel {
            group("General Settings") {
                row("Default expansion depth:") {
                    intTextField(1..10)
                        .bindIntText(::depthValue)
                        .comment("Number of levels to expand in the dependency graph by default")
                }

                row {
                    checkBox("Hide vendor/framework services by default")
                        .bindSelected(::hideVendorValue)
                        .comment("Filter out non-application services when loading the container")
                }

                row {
                    checkBox("Case sensitive search")
                        .bindSelected(::caseSensitiveValue)
                        .comment("Enable case-sensitive matching for service searches")
                }
            }

            group("Filters") {
                row("Namespace filter:") {
                    textField()
                        .bindText(::namespaceFilterValue)
                        .comment("Filter services by namespace (e.g., App\\)")
                        .columns(COLUMNS_LARGE)
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = SymfonyContainerSettings.getInstance(project)
        return depthValue != settings.defaultExpansionDepth ||
                hideVendorValue != settings.hideVendorServicesByDefault ||
                namespaceFilterValue != settings.namespaceFilter ||
                caseSensitiveValue != settings.caseSensitiveSearch
    }

    override fun apply() {
        val settings = SymfonyContainerSettings.getInstance(project)
        settings.defaultExpansionDepth = depthValue
        settings.hideVendorServicesByDefault = hideVendorValue
        settings.namespaceFilter = namespaceFilterValue
        settings.caseSensitiveSearch = caseSensitiveValue
    }

    override fun reset() {
        val settings = SymfonyContainerSettings.getInstance(project)
        depthValue = settings.defaultExpansionDepth
        hideVendorValue = settings.hideVendorServicesByDefault
        namespaceFilterValue = settings.namespaceFilter
        caseSensitiveValue = settings.caseSensitiveSearch
    }
}
