package codes.konstantin.containerinspector.layout

import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.NullElkProgressMonitor
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * Adapter for Eclipse Layout Kernel (ELK) to compute graph layouts
 */
class ElkLayoutAdapter {

    data class NodeLayout(
        val id: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )

    data class GroupLayout(
        val groupId: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )

    data class EdgeLayout(
        val fromId: String,
        val toId: String,
        val points: List<Point>
    )

    data class Point(val x: Double, val y: Double)

    data class LayoutResult(
        val nodes: List<NodeLayout>,
        val edges: List<EdgeLayout>,
        val groups: List<GroupLayout>,
        val totalWidth: Double,
        val totalHeight: Double
    )

    data class GroupInput(
        val id: String,
        val nodeIds: List<String>
    )

    /**
     * Compute layout using ELK's layered algorithm
     */
    fun computeLayout(
        nodes: List<NodeInput>,
        edges: List<EdgeInput>,
        groups: List<GroupInput> = emptyList()
    ): LayoutResult {
        // Create ELK graph
        val graph = ElkGraphUtil.createGraph()

        // ===== Core Layout Configuration =====
        graph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        graph.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
        graph.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)

        // Spacing configuration for clean appearance - prevent edge overlaps
        graph.setProperty(CoreOptions.SPACING_NODE_NODE, 60.0) // Horizontal spacing between nodes
        graph.setProperty(CoreOptions.SPACING_EDGE_NODE, 40.0) // Space between edges and nodes
        graph.setProperty(CoreOptions.SPACING_EDGE_EDGE, 20.0) // Space between parallel edges (increased to prevent overlap)
        graph.setProperty(CoreOptions.SPACING_EDGE_LABEL, 5.0)

        // ===== Layered Algorithm Options =====
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 120.0)
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 30.0)

        // Crossing minimization - most important for clean diagrams
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CROSSING_MINIMIZATION_STRATEGY,
            org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy.LAYER_SWEEP)
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.THOROUGHNESS, 127) // Maximum quality
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CROSSING_MINIMIZATION_SEMI_INTERACTIVE, false)

        // Node placement strategy for better distribution
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.NODE_PLACEMENT_STRATEGY,
            org.eclipse.elk.alg.layered.options.NodePlacementStrategy.NETWORK_SIMPLEX)

        // Layer assignment - important for hierarchical clarity
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_STRATEGY,
            org.eclipse.elk.alg.layered.options.LayeringStrategy.NETWORK_SIMPLEX)

        // Cycle breaking for graphs with cycles
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CYCLE_BREAKING_STRATEGY,
            org.eclipse.elk.alg.layered.options.CycleBreakingStrategy.GREEDY)

        // Edge routing optimizations
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.EDGE_ROUTING_SELF_LOOP_DISTRIBUTION,
            org.eclipse.elk.alg.layered.options.SelfLoopDistributionStrategy.EQUALLY)
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.EDGE_ROUTING_SELF_LOOP_ORDERING,
            org.eclipse.elk.alg.layered.options.SelfLoopOrderingStrategy.STACKED)

        // Improve edge spacing and bendpoints (ELK 0.11+) - prevent overlapping edges
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, 25.0) // Increased spacing

        // Node layering and ordering
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY,
            org.eclipse.elk.alg.layered.options.OrderingStrategy.PREFER_EDGES)

        // High-level settings for quality
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PRIORITY_STRAIGHTNESS, 10) // Prefer straight edges

        // Disable compaction to prevent "Invalid hitboxes" errors
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.COMPACTION_POST_COMPACTION_STRATEGY,
            org.eclipse.elk.alg.layered.options.GraphCompactionStrategy.NONE)

        // Interactive and incremental hints (for stability)
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.MERGE_EDGES, false) // Keep edges separate
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.MERGE_HIERARCHY_EDGES, false)

        // Additional ELK 0.11 improvements
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.WRAPPING_STRATEGY,
            org.eclipse.elk.alg.layered.options.WrappingStrategy.OFF) // No wrapping for clarity

        // Improved node distribution
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT,
            org.eclipse.elk.alg.layered.options.FixedAlignment.BALANCED)

        // Hierarchical layout improvements for groups
        graph.setProperty(CoreOptions.HIERARCHY_HANDLING, org.eclipse.elk.core.options.HierarchyHandling.INCLUDE_CHILDREN)
        graph.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.INTERACTIVE_REFERENCE_POINT,
            org.eclipse.elk.alg.layered.options.InteractiveReferencePoint.TOP_LEFT)

        // Create group containers first (as parent ElkNodes)
        val groupContainers = mutableMapOf<String, ElkNode>()
        val nodeToGroup = mutableMapOf<String, String>()

        groups.forEach { groupInput ->
            val groupNode = ElkGraphUtil.createNode(graph)
            groupNode.identifier = groupInput.id

            // Configure as container
            groupNode.setProperty(CoreOptions.NODE_LABELS_PADDING, org.eclipse.elk.core.math.ElkPadding(10.0, 10.0, 10.0, 10.0))
            groupNode.setProperty(CoreOptions.PADDING, org.eclipse.elk.core.math.ElkPadding(20.0, 20.0, 20.0, 20.0))

            // Apply full layered algorithm configuration to group contents (same as root)
            groupNode.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
            groupNode.setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            groupNode.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)

            // Spacing configuration
            groupNode.setProperty(CoreOptions.SPACING_NODE_NODE, 60.0)
            groupNode.setProperty(CoreOptions.SPACING_EDGE_NODE, 40.0)
            groupNode.setProperty(CoreOptions.SPACING_EDGE_EDGE, 20.0)
            groupNode.setProperty(CoreOptions.SPACING_EDGE_LABEL, 5.0)

            // Layered algorithm options
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 120.0)
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 30.0)
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, 25.0)

            // Crossing minimization for clean layout
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CROSSING_MINIMIZATION_STRATEGY,
                org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy.LAYER_SWEEP)
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.THOROUGHNESS, 127)

            // Node placement and layering strategies
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.NODE_PLACEMENT_STRATEGY,
                org.eclipse.elk.alg.layered.options.NodePlacementStrategy.NETWORK_SIMPLEX)
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_STRATEGY,
                org.eclipse.elk.alg.layered.options.LayeringStrategy.NETWORK_SIMPLEX)

            // Cycle breaking
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CYCLE_BREAKING_STRATEGY,
                org.eclipse.elk.alg.layered.options.CycleBreakingStrategy.GREEDY)

            // Edge routing
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PRIORITY_STRAIGHTNESS, 10)
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.MERGE_EDGES, false)
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.MERGE_HIERARCHY_EDGES, false)

            // Fixed alignment
            groupNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT,
                org.eclipse.elk.alg.layered.options.FixedAlignment.BALANCED)

            groupContainers[groupInput.id] = groupNode

            // Track which nodes belong to which group
            groupInput.nodeIds.forEach { nodeId ->
                nodeToGroup[nodeId] = groupInput.id
            }
        }

        // Create ELK nodes with port constraints
        val elkNodes = mutableMapOf<String, ElkNode>()
        nodes.forEach { nodeInput ->
            // Determine parent: either a group container or the root graph
            val parent = nodeToGroup[nodeInput.id]?.let { groupContainers[it] } ?: graph

            val elkNode = ElkGraphUtil.createNode(parent)
            elkNode.identifier = nodeInput.id
            elkNode.width = nodeInput.width
            elkNode.height = nodeInput.height

            // Configure node-specific options for better layout
            elkNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PORT_CONSTRAINTS,
                org.eclipse.elk.core.options.PortConstraints.FIXED_SIDE)
            elkNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PORT_ALIGNMENT_DEFAULT,
                org.eclipse.elk.core.options.PortAlignment.CENTER)

            // Create ports for cleaner edge attachment
            // South port for outgoing edges
            val southPort = ElkGraphUtil.createPort(elkNode)
            southPort.setProperty(CoreOptions.PORT_SIDE, org.eclipse.elk.core.options.PortSide.SOUTH)
            southPort.setProperty(CoreOptions.PORT_BORDER_OFFSET, -5.0)
            southPort.x = nodeInput.width / 2 - 2.5
            southPort.y = nodeInput.height
            southPort.width = 5.0
            southPort.height = 5.0

            // North port for incoming edges
            val northPort = ElkGraphUtil.createPort(elkNode)
            northPort.setProperty(CoreOptions.PORT_SIDE, org.eclipse.elk.core.options.PortSide.NORTH)
            northPort.setProperty(CoreOptions.PORT_BORDER_OFFSET, -5.0)
            northPort.x = nodeInput.width / 2 - 2.5
            northPort.y = 0.0
            northPort.width = 5.0
            northPort.height = 5.0

            elkNodes[nodeInput.id] = elkNode
        }

        // Create ELK edges with port connections
        val elkEdges = mutableListOf<Pair<ElkEdge, EdgeInput>>()
        edges.forEach { edgeInput ->
            val source = elkNodes[edgeInput.fromId]
            val target = elkNodes[edgeInput.toId]

            if (source != null && target != null) {
                // Determine the correct container for the edge
                val sourceParent = source.parent
                val targetParent = target.parent

                // If both nodes are in the same group, create edge in that group
                // Otherwise create edge in the root graph
                val edgeContainer = if (sourceParent == targetParent && sourceParent != graph) {
                    sourceParent
                } else {
                    graph
                }

                val elkEdge = ElkGraphUtil.createEdge(edgeContainer)

                // Connect to south port of source (outgoing)
                val sourcePorts = source.ports.filter {
                    it.getProperty(CoreOptions.PORT_SIDE) == org.eclipse.elk.core.options.PortSide.SOUTH
                }
                if (sourcePorts.isNotEmpty()) {
                    elkEdge.sources.add(sourcePorts.first())
                } else {
                    elkEdge.sources.add(source)
                }

                // Connect to north port of target (incoming)
                val targetPorts = target.ports.filter {
                    it.getProperty(CoreOptions.PORT_SIDE) == org.eclipse.elk.core.options.PortSide.NORTH
                }
                if (targetPorts.isNotEmpty()) {
                    elkEdge.targets.add(targetPorts.first())
                } else {
                    elkEdge.targets.add(target)
                }

                elkEdges.add(elkEdge to edgeInput)
            }
        }

        // Run layout algorithm
        val layoutEngine = RecursiveGraphLayoutEngine()
        layoutEngine.layout(graph, NullElkProgressMonitor())

        // Helper function to get absolute position (including parent offset)
        fun getAbsolutePosition(node: ElkNode): Pair<Double, Double> {
            var x = node.x
            var y = node.y
            var parent = node.parent
            while (parent != null && parent != graph) {
                x += parent.x
                y += parent.y
                parent = parent.parent
            }
            return x to y
        }

        // Extract node results with absolute positions
        val nodeLayouts = elkNodes.map { (id, elkNode) ->
            val (absX, absY) = getAbsolutePosition(elkNode)
            NodeLayout(
                id = id,
                x = absX,
                y = absY,
                width = elkNode.width,
                height = elkNode.height
            )
        }

        // Extract group results
        val groupLayouts = groupContainers.map { (id, groupNode) ->
            GroupLayout(
                groupId = id,
                x = groupNode.x,
                y = groupNode.y,
                width = groupNode.width,
                height = groupNode.height
            )
        }

        val edgeLayouts = elkEdges.map { (elkEdge, edgeInput) ->
            val points = mutableListOf<Point>()

            // Get the container offset for edge bendpoints
            val edgeContainer = elkEdge.containingNode
            var containerOffsetX = 0.0
            var containerOffsetY = 0.0
            if (edgeContainer != graph) {
                var parent = edgeContainer
                while (parent != null && parent != graph) {
                    containerOffsetX += parent.x
                    containerOffsetY += parent.y
                    parent = parent.parent
                }
            }

            // Add source point with absolute position
            val sourceNode = elkNodes[edgeInput.fromId]
            if (sourceNode != null) {
                val (absX, absY) = getAbsolutePosition(sourceNode)
                points.add(Point(
                    absX + sourceNode.width / 2,
                    absY + sourceNode.height
                ))
            }

            // Add bend points from ELK with container offset applied
            elkEdge.sections.forEach { section ->
                section.bendPoints.forEach { bendPoint ->
                    points.add(Point(
                        bendPoint.x + containerOffsetX,
                        bendPoint.y + containerOffsetY
                    ))
                }
            }

            // Add target point with absolute position
            val targetNode = elkNodes[edgeInput.toId]
            if (targetNode != null) {
                val (absX, absY) = getAbsolutePosition(targetNode)
                points.add(Point(
                    absX + targetNode.width / 2,
                    absY
                ))
            }

            EdgeLayout(
                fromId = edgeInput.fromId,
                toId = edgeInput.toId,
                points = points
            )
        }

        return LayoutResult(
            nodes = nodeLayouts,
            edges = edgeLayouts,
            groups = groupLayouts,
            totalWidth = graph.width,
            totalHeight = graph.height
        )
    }

    data class NodeInput(
        val id: String,
        val width: Double,
        val height: Double
    )

    data class EdgeInput(
        val fromId: String,
        val toId: String
    )
}
