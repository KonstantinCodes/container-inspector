package codes.konstantin.containerinspector.settings

import codes.konstantin.containerinspector.model.Config
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Manages reading and writing configs to/from project file
 *
 * File format example (.container-inspector.yaml):
 * ```yaml
 * configs:
 *   - name: "Production"
 *     appClassNameRegex: "^App\\\\.*"
 *     appServiceIdRegex: "^app\\..*"
 *     minifyNonApp: true
 *     minifyClassNameRegex: ""
 *     minifyClassNames: ""
 *     minifyServiceIdRegex: ""
 *     minifyServiceIds: ""
 *     neverMinifyClassNames: ""
 *     neverMinifyServiceIds: ""
 *     groups: "[]"
 * ```
 */
object ConfigFileManager {
    private const val CONFIG_FILE_NAME = ".container-inspector.yaml"

    /**
     * Get the config file path for the project
     */
    private fun getConfigFile(project: Project): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, CONFIG_FILE_NAME)
    }

    /**
     * Load configs from project file
     */
    fun loadConfigsFromFile(project: Project): List<Config> {
        val configFile = getConfigFile(project) ?: return emptyList()
        if (!configFile.exists()) return emptyList()

        try {
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(configFile.inputStream()) ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val configsList = data["configs"] as? List<Map<String, Any>> ?: return emptyList()

            return configsList.mapNotNull { configMap ->
                try {
                    // Parse groups from YAML structure to ServiceGroup objects
                    val groups = try {
                        @Suppress("UNCHECKED_CAST")
                        val groupsList = configMap["groups"] as? List<Map<String, Any>>
                        groupsList?.map { groupMap ->
                            @Suppress("UNCHECKED_CAST")
                            codes.konstantin.containerinspector.model.ServiceGroup(
                                name = groupMap["name"] as? String ?: "",
                                serviceIds = (groupMap["serviceIds"] as? List<String>) ?: emptyList(),
                                classNames = (groupMap["classNames"] as? List<String>) ?: emptyList(),
                                serviceIdRegex = (groupMap["serviceIdRegex"] as? List<String>) ?: emptyList(),
                                classNameRegex = (groupMap["classNameRegex"] as? List<String>) ?: emptyList()
                            )
                        } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }

                    Config(
                        name = configMap["name"] as? String ?: return@mapNotNull null,
                        compiledContainerXmlPath = configMap["compiledContainerXmlPath"] as? String ?: "",
                        appClassNameRegex = configMap["appClassNameRegex"] as? String ?: "^App\\\\.*",
                        appServiceIdRegex = configMap["appServiceIdRegex"] as? String ?: "^app\\..*",
                        minifyNonApp = configMap["minifyNonApp"] as? Boolean ?: false,
                        minifyClassNameRegex = (configMap["minifyClassNameRegex"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        minifyClassNames = (configMap["minifyClassNames"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        minifyServiceIdRegex = (configMap["minifyServiceIdRegex"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        minifyServiceIds = (configMap["minifyServiceIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        neverMinifyClassNames = (configMap["neverMinifyClassNames"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        neverMinifyServiceIds = (configMap["neverMinifyServiceIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        groups = groups,
                        persistToFile = true // Configs from file are always marked as persisted
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * Save configs to project file
     */
    fun saveConfigsToFile(project: Project, configs: List<Config>) {
        val configFile = getConfigFile(project)
        println("DEBUG ConfigFileManager: configFile path: ${configFile?.absolutePath}")
        if (configFile == null) return

        // Filter only configs marked to persist
        val configsToPersist = configs.filter { it.persistToFile }
        println("DEBUG ConfigFileManager: Total configs: ${configs.size}, configs to persist: ${configsToPersist.size}")
        configsToPersist.forEach { println("DEBUG ConfigFileManager: Will persist: ${it.name}, persistToFile=${it.persistToFile}") }

        if (configsToPersist.isEmpty()) {
            // Delete file if no configs to persist
            if (configFile.exists()) {
                println("DEBUG ConfigFileManager: No configs to persist, deleting file")
                configFile.delete()
            }
            return
        }

        try {
            // Configure YAML dumper for pretty output
            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
                indicatorIndent = 0
                width = 120
            }
            val yaml = Yaml(options)

            val configsList = configsToPersist.map { config ->
                // Convert ServiceGroup objects to maps for YAML
                val groupsList = config.groups.map { group ->
                    mapOf(
                        "name" to group.name,
                        "serviceIds" to group.serviceIds,
                        "classNames" to group.classNames,
                        "serviceIdRegex" to group.serviceIdRegex,
                        "classNameRegex" to group.classNameRegex
                    )
                }

                mapOf(
                    "name" to config.name,
                    "compiledContainerXmlPath" to config.compiledContainerXmlPath,
                    "appClassNameRegex" to config.appClassNameRegex,
                    "appServiceIdRegex" to config.appServiceIdRegex,
                    "minifyNonApp" to config.minifyNonApp,
                    "minifyClassNameRegex" to config.minifyClassNameRegex,
                    "minifyClassNames" to config.minifyClassNames,
                    "minifyServiceIdRegex" to config.minifyServiceIdRegex,
                    "minifyServiceIds" to config.minifyServiceIds,
                    "neverMinifyClassNames" to config.neverMinifyClassNames,
                    "neverMinifyServiceIds" to config.neverMinifyServiceIds,
                    "groups" to groupsList
                )
            }

            val data = mapOf("configs" to configsList)
            val yamlContent = yaml.dump(data)
            println("DEBUG ConfigFileManager: Writing YAML to ${configFile.absolutePath}")
            println("DEBUG ConfigFileManager: YAML content:\n$yamlContent")
            configFile.writeText(yamlContent)
            println("DEBUG ConfigFileManager: File written successfully")
        } catch (e: Exception) {
            println("DEBUG ConfigFileManager: Error writing file: ${e.message}")
            e.printStackTrace()
        }
    }
}
