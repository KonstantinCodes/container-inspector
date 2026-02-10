package codes.konstantin.containerinspector.model

/**
 * Graph representation of service dependencies
 * Supports both outbound (depends on) and inbound (used by) lookups
 */
class ServiceGraph {
    private val services = mutableMapOf<String, ServiceDefinition>()
    private val outboundEdges = mutableMapOf<String, MutableSet<String>>() // service -> dependencies
    private val inboundEdges = mutableMapOf<String, MutableSet<String>>()  // service -> dependents
    private val aliasToMaster = mutableMapOf<String, String>() // alias -> master service id

    /**
     * Resolve alias mappings
     */
    fun resolveAliases(aliasMap: Map<String, List<String>>) {
        aliasMap.forEach { (masterId, aliases) ->
            aliases.forEach { alias ->
                aliasToMaster[alias] = masterId
            }
        }
    }

    /**
     * Resolve a service ID, following aliases to the master service
     */
    private fun resolveMaster(serviceId: String): String {
        return aliasToMaster[serviceId] ?: serviceId
    }

    /**
     * Add a service to the graph
     */
    fun addService(service: ServiceDefinition) {
        services[service.id] = service

        // Initialize edge sets if not present
        outboundEdges.putIfAbsent(service.id, mutableSetOf())
        inboundEdges.putIfAbsent(service.id, mutableSetOf())

        // Add direct service dependencies - resolve aliases to master services
        service.getServiceDependencies().forEach { depId ->
            val masterDepId = resolveMaster(depId)
            outboundEdges[service.id]?.add(masterDepId)
            inboundEdges.getOrPut(masterDepId) { mutableSetOf() }.add(service.id)
        }

        // Note: Tagged iterator dependencies are resolved later by calling resolveTaggedIterators()
        // after all services have been added to the graph
    }

    /**
     * Resolve all tagged iterator dependencies
     * Called after all services have been added to properly resolve tags
     */
    fun resolveTaggedIterators() {
        services.values.forEach { service ->
            service.getTaggedIteratorArguments().forEach { taggedArg ->
                // Find all services with this tag
                val taggedServices = services.values.filter { s ->
                    s.tags.any { tag -> tag.name == taggedArg.tag }
                }

                // Add dependencies to all tagged services
                taggedServices.forEach { taggedService ->
                    outboundEdges[service.id]?.add(taggedService.id)
                    inboundEdges.getOrPut(taggedService.id) { mutableSetOf() }.add(service.id)
                }
            }
        }
    }

    /**
     * Get a service by ID
     */
    fun getService(serviceId: String): ServiceDefinition? {
        return services[serviceId]
    }

    /**
     * Get all services
     */
    fun getAllServices(): List<ServiceDefinition> {
        return services.values.toList()
    }

    /**
     * Search services by ID or class name
     */
    fun searchServices(query: String, caseSensitive: Boolean = false): List<ServiceDefinition> {
        if (query.isEmpty()) return emptyList()

        val searchQuery = if (caseSensitive) query else query.lowercase()

        return services.values.filter { service ->
            val id = if (caseSensitive) service.id else service.id.lowercase()
            val className = if (caseSensitive) service.className ?: "" else (service.className ?: "").lowercase()

            id.contains(searchQuery) || className.contains(searchQuery)
        }.sortedBy { it.id }
    }

    /**
     * Get direct dependencies of a service (what it depends on)
     */
    fun getDirectDependencies(serviceId: String): List<ServiceDefinition> {
        return outboundEdges[serviceId]?.mapNotNull { services[it] } ?: emptyList()
    }

    /**
     * Get direct dependents of a service (what depends on it)
     */
    fun getDirectDependents(serviceId: String): List<ServiceDefinition> {
        return inboundEdges[serviceId]?.mapNotNull { services[it] } ?: emptyList()
    }

    /**
     * Get dependencies recursively up to a certain depth
     */
    fun getDependenciesRecursive(serviceId: String, maxDepth: Int = 2): Map<Int, List<ServiceDefinition>> {
        return traverseGraph(serviceId, maxDepth, ::getDirectDependencies)
    }

    /**
     * Get dependents recursively up to a certain depth
     */
    fun getDependentsRecursive(serviceId: String, maxDepth: Int = 2): Map<Int, List<ServiceDefinition>> {
        return traverseGraph(serviceId, maxDepth, ::getDirectDependents)
    }

    /**
     * Generic BFS traversal
     */
    private fun traverseGraph(
        startId: String,
        maxDepth: Int,
        getNeighbors: (String) -> List<ServiceDefinition>
    ): Map<Int, List<ServiceDefinition>> {
        val result = mutableMapOf<Int, MutableList<ServiceDefinition>>()
        val visited = mutableSetOf(startId)
        var currentLevel = setOf(startId)

        for (depth in 1..maxDepth) {
            val nextLevel = mutableSetOf<String>()
            val servicesAtDepth = mutableListOf<ServiceDefinition>()

            currentLevel.forEach { serviceId ->
                getNeighbors(serviceId).forEach { neighbor ->
                    if (neighbor.id !in visited) {
                        visited.add(neighbor.id)
                        nextLevel.add(neighbor.id)
                        servicesAtDepth.add(neighbor)
                    }
                }
            }

            if (servicesAtDepth.isNotEmpty()) {
                result[depth] = servicesAtDepth
            }

            if (nextLevel.isEmpty()) break
            currentLevel = nextLevel
        }

        return result
    }

    /**
     * Get statistics about the container
     */
    fun getStatistics(): ContainerStatistics {
        val totalServices = services.size
        val appServices = services.values.count { !it.isVendorService() }
        val vendorServices = totalServices - appServices
        val publicServices = services.values.count { it.isPublic }

        return ContainerStatistics(
            totalServices = totalServices,
            appServices = appServices,
            vendorServices = vendorServices,
            publicServices = publicServices
        )
    }

    /**
     * Clear all data
     */
    fun clear() {
        services.clear()
        outboundEdges.clear()
        inboundEdges.clear()
    }
}

data class ContainerStatistics(
    val totalServices: Int,
    val appServices: Int,
    val vendorServices: Int,
    val publicServices: Int
)
