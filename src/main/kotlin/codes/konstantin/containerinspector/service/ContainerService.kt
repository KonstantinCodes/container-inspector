package codes.konstantin.containerinspector.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import codes.konstantin.containerinspector.model.ServiceGraph
import codes.konstantin.containerinspector.parser.ContainerXmlParser
import java.io.File

/**
 * Project-level service for managing the Symfony container
 */
@Service(Service.Level.PROJECT)
class ContainerService(private val project: Project) {

    private var graph: ServiceGraph? = null
    private var containerFile: File? = null
    private val parser = ContainerXmlParser()
    private val listeners = mutableListOf<ContainerLoadListener>()
    private var fileWatcherConnection: com.intellij.util.messages.MessageBusConnection? = null

    /**
     * Load a container XML file
     * @param compiledContainerFile The compiled Symfony container XML file (var/cache/dev/App_KernelDevDebugContainer.xml)
     */
    fun loadContainer(compiledContainerFile: File) {
        try {
            // Store the file reference
            this.containerFile = compiledContainerFile

            // Parse the container
            graph = parser.parse(compiledContainerFile)

            // Set up file watchers
            setupFileWatchers()

            notifyContainerLoaded()
        } catch (e: Exception) {
            notifyContainerLoadFailed(e)
            throw e
        }
    }

    /**
     * Reload the container from the stored file reference
     */
    private fun reloadContainer() {
        val file = containerFile ?: return

        try {
            graph = parser.parse(file)
            notifyContainerLoaded()
        } catch (e: Exception) {
            notifyContainerLoadFailed(e)
        }
    }

    /**
     * Setup file watchers for the container XML file
     */
    private fun setupFileWatchers() {
        // Disconnect existing watcher if any
        fileWatcherConnection?.disconnect()

        // Create new connection to the message bus
        fileWatcherConnection = project.messageBus.connect()

        fileWatcherConnection?.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val path = containerFile?.absolutePath

                // Check if our watched file changed
                val shouldReload = events.any { event ->
                    event.path == path
                }

                if (shouldReload) {
                    reloadContainer()
                }
            }
        })
    }

    /**
     * Get the current service graph
     */
    fun getGraph(): ServiceGraph? = graph

    /**
     * Check if a container is loaded
     */
    fun isContainerLoaded(): Boolean = graph != null

    /**
     * Get the currently loaded container file
     */
    fun getContainerFile(): File? = containerFile

    /**
     * Clear the loaded container
     */
    fun clearContainer() {
        graph?.clear()
        graph = null
        containerFile = null
        fileWatcherConnection?.disconnect()
        fileWatcherConnection = null
        notifyContainerCleared()
    }

    /**
     * Add a listener for container events
     */
    fun addListener(listener: ContainerLoadListener) {
        listeners.add(listener)
    }

    /**
     * Remove a listener
     */
    fun removeListener(listener: ContainerLoadListener) {
        listeners.remove(listener)
    }

    private fun notifyContainerLoaded() {
        listeners.forEach { it.onContainerLoaded(graph!!) }
    }

    private fun notifyContainerLoadFailed(exception: Exception) {
        listeners.forEach { it.onContainerLoadFailed(exception) }
    }

    private fun notifyContainerCleared() {
        listeners.forEach { it.onContainerCleared() }
    }

    companion object {
        fun getInstance(project: Project): ContainerService {
            return project.getService(ContainerService::class.java)
        }
    }
}

/**
 * Listener interface for container events
 */
interface ContainerLoadListener {
    fun onContainerLoaded(graph: ServiceGraph)
    fun onContainerLoadFailed(exception: Exception)
    fun onContainerCleared()
}
