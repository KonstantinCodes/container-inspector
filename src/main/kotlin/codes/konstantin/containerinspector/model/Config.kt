package codes.konstantin.containerinspector.model

/**
 * Configuration profile for the Symfony Container Inspector
 */
data class Config(
    val name: String,
    val compiledContainerXmlPath: String = "", // Path to compiled Symfony container XML (relative to project root)
    val appClassNameRegex: String = "^App\\\\.*",
    val appServiceIdRegex: String = "^app\\..*",
    val minifyNonApp: Boolean = false,
    val minifyClassNameRegex: List<String> = emptyList(),
    val minifyClassNames: List<String> = emptyList(),
    val minifyServiceIdRegex: List<String> = listOf("^\\..*"), // Minify hidden services (starting with .) by default
    val minifyServiceIds: List<String> = emptyList(),
    val neverMinifyClassNames: List<String> = emptyList(),
    val neverMinifyServiceIds: List<String> = emptyList(),
    val groups: List<ServiceGroup> = emptyList(),
    val persistToFile: Boolean = false // Whether to persist this config to project file
)
