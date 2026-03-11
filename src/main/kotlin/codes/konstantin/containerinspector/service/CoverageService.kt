package codes.konstantin.containerinspector.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Service for accessing code coverage data via reflection
 * This allows the plugin to work with or without coverage support
 */
@Service(Service.Level.PROJECT)
class CoverageService(private val project: Project) {

    private val logger = Logger.getInstance(CoverageService::class.java)

    companion object {
        fun getInstance(project: Project): CoverageService {
            return project.getService(CoverageService::class.java)
        }
    }

    data class CoverageData(
        val percentage: Double,
        val lineCoverage: List<Boolean> // true = covered, false = uncovered, in order
    )

    /**
     * Get coverage percentage for a PHP class file
     * Returns null if coverage is not available
     */
    fun getCoveragePercentage(className: String): Double? {
        return getCoverageData(className)?.percentage
    }

    /**
     * Get detailed coverage data including line-level information
     */
    fun getCoverageData(className: String): CoverageData? {
        return try {
            val coverageDataManager = com.intellij.coverage.CoverageDataManager.getInstance(project)
            val currentSuite = coverageDataManager.currentSuitesBundle ?: return null

            val virtualFile = findVirtualFileForClass(className) ?: return null

            calculateFileCoverageData(virtualFile, currentSuite)
        } catch (e: Exception) {
            logger.warn("Error getting coverage data for $className", e)
            null
        }
    }

    /**
     * Check if coverage data is currently available
     */
    fun isCoverageAvailable(): Boolean {
        return try {
            val coverageDataManager = com.intellij.coverage.CoverageDataManager.getInstance(project)
            coverageDataManager.currentSuitesBundle != null
        } catch (e: Exception) {
            false
        }
    }

    private fun findVirtualFileForClass(className: String): VirtualFile? {
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            try {
                // Use PhpIndex to find the class directly by its fully qualified name
                val phpIndex = com.jetbrains.php.PhpIndex.getInstance(project)
                val phpClasses = phpIndex.getAnyByFQN(className)

                // Return the virtual file of the first matching class
                phpClasses.firstOrNull()?.containingFile?.virtualFile
            } catch (e: Exception) {
                logger.warn("Error finding file for class $className using PhpIndex: ${e.message}")
                null
            }
        }
    }

    private fun calculateFileCoverageData(virtualFile: VirtualFile, suiteBundle: com.intellij.coverage.CoverageSuitesBundle): CoverageData? {
        return try {
            val coverageData = suiteBundle.coverageData ?: return null

            // Get class data for the file
            val classData = coverageData.getClassData(virtualFile.path) ?: return null

            // Get line coverage data - the array type is not directly accessible, so we need reflection for the elements
            val lines = classData.lines ?: return null

            var totalLines = 0
            var coveredLines = 0
            val lineCoverageList = mutableListOf<Boolean>()

            for (lineData in lines) {
                if (lineData != null) {
                    totalLines++
                    // Use reflection to access the hits count since LineData is not directly accessible
                    val hits = try {
                        val getHitsMethod = lineData.javaClass.getMethod("getHits")
                        getHitsMethod.invoke(lineData) as Int
                    } catch (e: Exception) {
                        0
                    }
                    val isCovered = hits > 0
                    if (isCovered) {
                        coveredLines++
                    }
                    lineCoverageList.add(isCovered)
                }
            }

            if (totalLines > 0) {
                val percentage = (coveredLines.toDouble() / totalLines.toDouble()) * 100.0
                CoverageData(percentage, lineCoverageList)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Error calculating coverage for ${virtualFile.path}", e)
            null
        }
    }
}
