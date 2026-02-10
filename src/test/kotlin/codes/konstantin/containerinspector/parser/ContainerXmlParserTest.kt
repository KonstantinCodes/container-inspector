package codes.konstantin.containerinspector.parser

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ContainerXmlParserTest {

    @Test
    fun testParseExampleContainer() {
        val parser = ContainerXmlParser()
        val file = File("container_example.xml")

        if (!file.exists()) {
            println("Skipping test - container_example.xml not found")
            return
        }

        val graph = parser.parse(file)
        assertNotNull(graph)

        val stats = graph.getStatistics()
        assertTrue(stats.totalServices > 0, "Should have parsed services")

        println("Parsed ${stats.totalServices} services")
        println("App services: ${stats.appServices}")
        println("Vendor services: ${stats.vendorServices}")
        println("Public services: ${stats.publicServices}")
    }
}
