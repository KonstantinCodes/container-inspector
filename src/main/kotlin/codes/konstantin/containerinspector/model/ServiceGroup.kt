package codes.konstantin.containerinspector.model

/**
 * Represents a group of services that should be visually grouped together
 */
data class ServiceGroup(
    val name: String,
    val serviceIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    val serviceIdRegex: List<String> = emptyList(),
    val classNameRegex: List<String> = emptyList()
) {
    /**
     * Check if a service matches this group's criteria
     */
    fun matches(service: ServiceDefinition): Boolean {
        // Check service ID exact match
        if (serviceIds.contains(service.id)) {
            return true
        }

        // Check class name exact match
        if (service.className != null && classNames.contains(service.className)) {
            return true
        }

        // Check service ID regex patterns
        for (pattern in serviceIdRegex) {
            if (pattern.isNotEmpty()) {
                try {
                    if (Regex(pattern).containsMatchIn(service.id)) {
                        return true
                    }
                } catch (e: Exception) {
                    // Invalid regex, skip
                }
            }
        }

        // Check class name regex patterns
        if (service.className != null) {
            for (pattern in classNameRegex) {
                if (pattern.isNotEmpty()) {
                    try {
                        if (Regex(pattern).containsMatchIn(service.className)) {
                            return true
                        }
                    } catch (e: Exception) {
                        // Invalid regex, skip
                    }
                }
            }
        }

        return false
    }
}
