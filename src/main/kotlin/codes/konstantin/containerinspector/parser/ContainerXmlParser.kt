package codes.konstantin.containerinspector.parser

import codes.konstantin.containerinspector.model.ServiceGraph
import java.io.File

/**
 * Main parser for Symfony container XML files
 * Delegates to CompiledContainerXmlParser
 */
class ContainerXmlParser {

    private val compiledParser = CompiledContainerXmlParser()

    /**
     * Parse container XML file and return a ServiceGraph
     * @param compiledContainerFile The compiled Symfony container XML file (var/cache/dev/App_KernelDevDebugContainer.xml)
     */
    fun parse(compiledContainerFile: File): ServiceGraph {
        return compiledParser.parse(compiledContainerFile)
    }
}
