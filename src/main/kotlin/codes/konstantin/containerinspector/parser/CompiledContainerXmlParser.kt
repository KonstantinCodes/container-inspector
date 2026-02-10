package codes.konstantin.containerinspector.parser

import codes.konstantin.containerinspector.model.*
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for compiled Symfony container XML files (var/cache/dev/App_KernelDevDebugContainer.xml)
 *
 * Format:
 * - Uses <service> elements
 * - Tags are direct children: <tag name="..." attribute="value"/>
 * - Calls are direct children: <call method="..."><argument>...</argument></call>
 */
class CompiledContainerXmlParser {

    /**
     * Parse a compiled container XML file and return a ServiceGraph
     */
    fun parse(file: File): ServiceGraph {
        val graph = ServiceGraph()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isValidating = false
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val builder = factory.newDocumentBuilder()

            // Clean the XML content to remove trailing content
            val cleanedContent = cleanXmlContent(file.readText())
            val document = builder.parse(cleanedContent.byteInputStream())
            document.documentElement.normalize()

            // Parse <service> elements
            // In compiled container, aliases are stored as attributes on service elements
            // Format: <service id="alias_name" alias="real_service_id"/>
            val aliasMap = mutableMapOf<String, MutableList<String>>()
            val serviceElements = document.getElementsByTagName("service")

            // First pass: collect all aliases
            for (i in 0 until serviceElements.length) {
                val node = serviceElements.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val element = node as Element
                    val serviceId = element.getAttribute("id")
                    val aliasAttribute = element.getAttribute("alias")

                    // If this service element has an alias attribute, it's an alias definition
                    if (aliasAttribute.isNotEmpty() && !serviceId.startsWith(".")) {
                        // This service ID is an alias for the service specified in the alias attribute
                        aliasMap.getOrPut(aliasAttribute) { mutableListOf() }.add(serviceId)
                    }
                }
            }

            // Register all aliases in the graph first
            graph.resolveAliases(aliasMap)

            // Second pass: parse actual service definitions
            for (i in 0 until serviceElements.length) {
                val node = serviceElements.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val element = node as Element
                    val aliasAttribute = element.getAttribute("alias")

                    // Skip alias definitions
                    if (aliasAttribute.isNotEmpty()) {
                        continue
                    }

                    // Check if service has container.excluded tag
                    if (hasContainerExcludedTag(element)) {
                        continue
                    }

                    // Regular service definition
                    val service = parseServiceDefinition(element, aliasMap)
                    graph.addService(service)
                }
            }

            // After all services are added, resolve tagged iterator dependencies
            graph.resolveTaggedIterators()
        } catch (e: Exception) {
            throw ContainerParseException("Failed to parse compiled container XML: ${e.message}", e)
        }

        return graph
    }

    /**
     * Parse a single service definition element
     */
    private fun parseServiceDefinition(
        element: Element,
        aliasMap: Map<String, List<String>>
    ): ServiceDefinition {
        val id = element.getAttribute("id")
        val className = element.getAttribute("class").ifEmpty { null }
        val isPublic = element.getAttribute("public").toBoolean()
        val isSynthetic = element.getAttribute("synthetic").toBoolean()
        val isLazy = element.getAttribute("lazy").toBoolean()
        val isShared = element.getAttribute("shared").toBoolean()
        val isAbstract = element.getAttribute("abstract").toBoolean()
        val isAutowired = element.getAttribute("autowire").toBoolean()
        val isAutoconfigured = element.getAttribute("autoconfigure").toBoolean()
        val isDeprecated = element.getAttribute("deprecated").toBoolean()
        val file = element.getAttribute("file")

        val arguments = parseArguments(element)
        val tags = parseTags(element)
        val calls = parseMethodCalls(element)
        val aliases = aliasMap[id] ?: emptyList()

        return ServiceDefinition(
            id = id,
            className = className,
            isPublic = isPublic,
            isSynthetic = isSynthetic,
            isLazy = isLazy,
            isShared = isShared,
            isAbstract = isAbstract,
            isAutowired = isAutowired,
            isAutoconfigured = isAutoconfigured,
            isDeprecated = isDeprecated,
            file = file,
            arguments = arguments,
            tags = tags,
            calls = calls,
            aliases = aliases
        )
    }

    /**
     * Parse constructor arguments (direct children)
     */
    private fun parseArguments(element: Element): List<Argument> {
        val arguments = mutableListOf<Argument>()
        val childNodes = element.childNodes

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val argElement = node as Element
                if (argElement.tagName == "argument") {
                    arguments.add(parseArgument(argElement))
                }
            }
        }

        return arguments
    }

    /**
     * Parse a single argument element
     */
    private fun parseArgument(element: Element): Argument {
        val type = element.getAttribute("type")
        val id = element.getAttribute("id")
        val tag = element.getAttribute("tag")

        return when {
            type == "tagged_iterator" && tag.isNotEmpty() -> Argument.TaggedIteratorArgument(tag)
            type == "service" && id.isNotEmpty() -> Argument.ServiceArgument(id)
            element.textContent.isNotEmpty() -> Argument.StringArgument(element.textContent)
            else -> Argument.NullArgument()
        }
    }

    /**
     * Parse service tags (direct children)
     * Format: <tag name="..." attribute="value"/>
     */
    private fun parseTags(element: Element): List<Tag> {
        val tags = mutableListOf<Tag>()
        val childNodes = element.childNodes

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val childElement = node as Element
                if (childElement.tagName == "tag") {
                    val tagName = childElement.getAttribute("name")
                    val parameters = mutableMapOf<String, String>()

                    // All attributes except "name" are parameters
                    val attributes = childElement.attributes
                    for (j in 0 until attributes.length) {
                        val attr = attributes.item(j)
                        if (attr.nodeName != "name") {
                            parameters[attr.nodeName] = attr.nodeValue
                        }
                    }

                    tags.add(Tag(tagName, parameters))
                }
            }
        }

        return tags
    }

    /**
     * Parse method calls (direct children)
     * Format: <call method="..."><argument>...</argument></call>
     */
    private fun parseMethodCalls(element: Element): List<MethodCall> {
        val calls = mutableListOf<MethodCall>()
        val childNodes = element.childNodes

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val childElement = node as Element
                if (childElement.tagName == "call") {
                    val method = childElement.getAttribute("method")
                    val arguments = mutableListOf<Argument>()

                    // Parse call arguments
                    val argNodes = childElement.getElementsByTagName("argument")
                    for (j in 0 until argNodes.length) {
                        val argElement = argNodes.item(j) as Element
                        arguments.add(parseArgument(argElement))
                    }

                    calls.add(MethodCall(method, arguments))
                }
            }
        }

        return calls
    }

    /**
     * Check if a service has the container.excluded tag
     */
    private fun hasContainerExcludedTag(element: Element): Boolean {
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val childElement = node as Element
                if (childElement.tagName == "tag" && childElement.getAttribute("name") == "container.excluded") {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Clean XML content by removing anything after the closing root tag
     */
    private fun cleanXmlContent(content: String): String {
        val rootClosingPattern = Regex("""</\w+>\s*$""", RegexOption.MULTILINE)
        val match = rootClosingPattern.findAll(content).lastOrNull()

        return if (match != null) {
            content.substring(0, match.range.last + 1)
        } else {
            content
        }
    }

    /**
     * Convert string to boolean, treating empty string as false
     */
    private fun String.toBoolean(): Boolean {
        return this == "true" || this == "1"
    }
}
