package codes.konstantin.containerinspector.model

/**
 * Represents a service definition from the Symfony container
 */
data class ServiceDefinition(
    val id: String,
    val className: String?,
    val isPublic: Boolean,
    val isSynthetic: Boolean,
    val isLazy: Boolean,
    val isShared: Boolean,
    val isAbstract: Boolean,
    val isAutowired: Boolean,
    val isAutoconfigured: Boolean,
    val isDeprecated: Boolean,
    val file: String,
    val arguments: List<Argument> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val calls: List<MethodCall> = emptyList(),
    val aliases: List<String> = emptyList()
) {
    /**
     * Get all service dependencies (arguments that reference other services)
     * For tagged iterators, this will be resolved when building the graph
     */
    fun getServiceDependencies(): List<String> {
        return arguments.filterIsInstance<Argument.ServiceArgument>().map { it.serviceId }
    }

    /**
     * Get tagged iterator arguments
     */
    fun getTaggedIteratorArguments(): List<Argument.TaggedIteratorArgument> {
        return arguments.filterIsInstance<Argument.TaggedIteratorArgument>()
    }

    /**
     * Check if this is likely a vendor/framework service
     */
    fun isVendorService(): Boolean {
        return !id.startsWith("App\\") && !id.startsWith("app.")
    }

    /**
     * Get display name for UI
     */
    fun getDisplayName(): String {
        return className ?: id
    }

    /**
     * Get short name (last component)
     */
    fun getShortName(): String {
        val displayName = getDisplayName()
        return displayName.substringAfterLast('\\').substringAfterLast('.')
    }
}

/**
 * Represents a service argument
 */
sealed class Argument {
    data class ServiceArgument(val serviceId: String) : Argument()
    data class StringArgument(val value: String) : Argument()
    data class NullArgument(val value: String = "null") : Argument()
    data class TaggedIteratorArgument(val tag: String) : Argument()
}

/**
 * Represents a service tag
 */
data class Tag(
    val name: String,
    val parameters: Map<String, String> = emptyMap()
)

/**
 * Represents a method call on the service
 */
data class MethodCall(
    val method: String,
    val arguments: List<Argument> = emptyList()
)
