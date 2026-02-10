package codes.konstantin.containerinspector.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import codes.konstantin.containerinspector.model.Config
import codes.konstantin.containerinspector.model.ServiceGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent settings for the Symfony Container Inspector
 */
@State(
    name = "SymfonyContainerSettings",
    storages = [Storage("container-inspector.xml")]
)
@Service(Service.Level.PROJECT)
class SymfonyContainerSettings() : PersistentStateComponent<SymfonyContainerSettings> {

    @Transient
    private var project: Project? = null

    constructor(project: Project) : this() {
        this.project = project
    }

    var defaultExpansionDepth: Int = 2
    var hideVendorServicesByDefault: Boolean = false
    var namespaceFilter: String = ""
    var caseSensitiveSearch: Boolean = false

    // Graph panel toggle states
    var showVendor: Boolean = true
    var showSingles: Boolean = false
    var fullTrace: Boolean = true
    var focusMode: Boolean = false
    var linkWithEditor: Boolean = false

    // Config system
    var configs: String = "[]" // JSON array of Config objects
    var activeConfigName: String = ""

    // Container loading configuration
    var debugContainerPath: String = ""
    var cachedContainerPath: String = ""
    var consoleCommand: String = ""

    override fun getState(): SymfonyContainerSettings {
        return this
    }

    override fun loadState(state: SymfonyContainerSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * Get all configs (includes IDE configs and project file configs)
     */
    fun getAllConfigs(): List<Config> {
        val result = mutableListOf<Config>()

        // Load from IDE settings
        try {
            val jsonArray = JSONArray(configs)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(configFromJson(obj))
            }
        } catch (e: Exception) {
            // Invalid JSON or empty
        }

        // Load from project file (these will override IDE configs with same name)
        val fileConfigs = project?.let { ConfigFileManager.loadConfigsFromFile(it) } ?: emptyList()
        fileConfigs.forEach { fileConfig ->
            val existingIndex = result.indexOfFirst { it.name == fileConfig.name }
            if (existingIndex >= 0) {
                // Replace IDE config with file config
                result[existingIndex] = fileConfig
            } else {
                // Add new config from file
                result.add(fileConfig)
            }
        }

        return result
    }

    /**
     * Get the active config
     */
    fun getActiveConfig(): Config? {
        return getAllConfigs().find { it.name == activeConfigName }
    }

    /**
     * Save configs
     * Configs with persistToFile=true are saved to project file
     * Configs with persistToFile=false are saved to IDE settings
     */
    fun saveConfigs(configsList: List<Config>) {
        println("DEBUG saveConfigs: Total configs received: ${configsList.size}")
        configsList.forEach { println("DEBUG saveConfigs: ${it.name}, persistToFile=${it.persistToFile}") }

        // Save configs NOT marked for file persistence to IDE settings
        val jsonArray = JSONArray()
        val ideConfigs = configsList.filter { !it.persistToFile }
        println("DEBUG saveConfigs: IDE configs to save: ${ideConfigs.size}")
        ideConfigs.forEach { config ->
            jsonArray.put(configToJson(config))
        }
        configs = jsonArray.toString()
        println("DEBUG saveConfigs: Saved to IDE settings: $configs")

        // Save configs marked for file persistence to project file
        project?.let { ConfigFileManager.saveConfigsToFile(it, configsList) }
    }

    private fun configToJson(config: Config): JSONObject {
        return JSONObject().apply {
            put("name", config.name)
            put("compiledContainerXmlPath", config.compiledContainerXmlPath)
            put("appClassNameRegex", config.appClassNameRegex)
            put("appServiceIdRegex", config.appServiceIdRegex)
            put("minifyNonApp", config.minifyNonApp)
            put("minifyClassNameRegex", JSONArray(config.minifyClassNameRegex))
            put("minifyClassNames", JSONArray(config.minifyClassNames))
            put("minifyServiceIdRegex", JSONArray(config.minifyServiceIdRegex))
            put("minifyServiceIds", JSONArray(config.minifyServiceIds))
            put("neverMinifyClassNames", JSONArray(config.neverMinifyClassNames))
            put("neverMinifyServiceIds", JSONArray(config.neverMinifyServiceIds))

            // Convert groups to JSON array
            val groupsArray = JSONArray()
            config.groups.forEach { group ->
                val groupObj = JSONObject().apply {
                    put("name", group.name)
                    put("serviceIds", JSONArray(group.serviceIds))
                    put("classNames", JSONArray(group.classNames))
                    put("serviceIdRegex", JSONArray(group.serviceIdRegex))
                    put("classNameRegex", JSONArray(group.classNameRegex))
                }
                groupsArray.put(groupObj)
            }
            put("groups", groupsArray)
            put("persistToFile", config.persistToFile)
        }
    }

    private fun configFromJson(obj: JSONObject): Config {
        // Parse groups from JSON array
        val groups: List<ServiceGroup> = try {
            val groupsArray = obj.optJSONArray("groups") ?: JSONArray()
            (0 until groupsArray.length()).map { i ->
                val groupObj = groupsArray.getJSONObject(i)
                val serviceIds: List<String> = groupObj.optJSONArray("serviceIds")?.let { arr ->
                    (0 until arr.length()).map { j -> arr.getString(j) }
                } ?: emptyList()
                val classNames: List<String> = groupObj.optJSONArray("classNames")?.let { arr ->
                    (0 until arr.length()).map { j -> arr.getString(j) }
                } ?: emptyList()
                val serviceIdRegex: List<String> = groupObj.optJSONArray("serviceIdRegex")?.let { arr ->
                    (0 until arr.length()).map { j -> arr.getString(j) }
                } ?: emptyList()
                val classNameRegex: List<String> = groupObj.optJSONArray("classNameRegex")?.let { arr ->
                    (0 until arr.length()).map { j -> arr.getString(j) }
                } ?: emptyList()

                ServiceGroup(
                    name = groupObj.optString("name", ""),
                    serviceIds = serviceIds,
                    classNames = classNames,
                    serviceIdRegex = serviceIdRegex,
                    classNameRegex = classNameRegex
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        return Config(
            name = obj.getString("name"),
            compiledContainerXmlPath = obj.optString("compiledContainerXmlPath", ""),
            appClassNameRegex = obj.optString("appClassNameRegex", "^App\\\\.*"),
            appServiceIdRegex = obj.optString("appServiceIdRegex", "^app\\..*"),
            minifyNonApp = obj.optBoolean("minifyNonApp", false),
            minifyClassNameRegex = obj.optJSONArray("minifyClassNameRegex")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getString(i) }
            } ?: emptyList(),
            minifyClassNames = obj.optJSONArray("minifyClassNames")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getString(i) }
            } ?: emptyList(),
            minifyServiceIdRegex = obj.optJSONArray("minifyServiceIdRegex")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getString(i) }
            } ?: emptyList(),
            minifyServiceIds = obj.optJSONArray("minifyServiceIds")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getString(i) }
            } ?: emptyList(),
            neverMinifyClassNames = obj.optJSONArray("neverMinifyClassNames")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getString(i) }
            } ?: emptyList(),
            neverMinifyServiceIds = obj.optJSONArray("neverMinifyServiceIds")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getString(i) }
            } ?: emptyList(),
            groups = groups,
            persistToFile = obj.optBoolean("persistToFile", false)
        )
    }

    companion object {
        fun getInstance(project: Project): SymfonyContainerSettings {
            return project.getService(SymfonyContainerSettings::class.java)
        }
    }
}
