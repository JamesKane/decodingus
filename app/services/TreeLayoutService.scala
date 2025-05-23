package services

import models.api.*
import models.view.{TreeLinkViewModel, TreeNodeViewModel, TreeViewModel}

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit // Required for minusYears

object TreeLayoutService {

  // Configuration for layout
  private val NODE_WIDTH = 150.0
  private val NODE_HEIGHT = 24.0
  private val HORIZONTAL_SPACING = 200.0 // Distance between levels (depths)
  private val VERTICAL_NODE_SPACING = 30.0 // Minimum vertical space between sibling nodes
  private val MARGIN_TOP = 50.0
  private val MARGIN_LEFT = 120.0 // Left margin for the root node

  // Helper to store mutable state during layout traversal (unchanged)
  private var currentYPosition: Double = MARGIN_TOP

  /**
   * Transforms a TreeDTO into a TreeViewModel with calculated coordinates, link paths, and node colors.
   * Collapses non-backbone branches only when the absolute top-level root (e.g., "Y") is displayed.
   * When re-rooting to any other node, its full subtree is displayed.
   *
   * @param treeDto           The TreeDTO representing the tree to be laid out. treeDto.subclade is the root of the currently displayed tree.
   * @param isAbsoluteTopRoot True ONLY if the current display root (treeDto.subclade) is the actual top-most root of the entire system (e.g., "Y").
   * @return An Option containing the TreeViewModel if a subclade exists.
   */
  def layoutTree(treeDto: TreeDTO, isAbsoluteTopRoot: Boolean): Option[TreeViewModel] = {
    val oneYearAgo = ZonedDateTime.now().minus(1, ChronoUnit.YEARS)

    treeDto.subclade.map { currentDisplayRootDTO =>
      currentYPosition = MARGIN_TOP

      val allNodes = collection.mutable.ListBuffer[TreeNodeViewModel]()
      val allLinks = collection.mutable.ListBuffer[TreeLinkViewModel]()

      def calculateNodePositions(nodeDTO: TreeNodeDTO, depth: Int, isCurrentDisplayRoot: Boolean): TreeNodeViewModel = {
        val y = depth * HORIZONTAL_SPACING + MARGIN_LEFT

        val fillColor = if (nodeDTO.isBackbone) {
          "#90EE90"
        } else if (nodeDTO.updated.isAfter(oneYearAgo)) {
          "#fff0e0"
        } else {
          "#f8f8f8"
        }

        val childrenToProcess = if (isCurrentDisplayRoot) {
          nodeDTO.children
        } else if (isAbsoluteTopRoot && !nodeDTO.isBackbone) {
          List.empty[TreeNodeDTO] // Collapse
        } else {
          nodeDTO.children
        }

        val childViewModels = childrenToProcess.sortBy(_.weight).map { childDTO =>
          calculateNodePositions(childDTO, depth + 1, false)
        }

        // Determine X position (vertical in SVG):
        val x = if (childViewModels.isEmpty) {
          val assignedX = currentYPosition
          currentYPosition += VERTICAL_NODE_SPACING
          assignedX
        } else {
          val firstChildX = childViewModels.head.x
          val lastChildX = childViewModels.last.x
          (firstChildX + lastChildX) / 2
        }

        val nodeViewModel = TreeNodeViewModel(
          name = nodeDTO.name,
          variants = nodeDTO.variants,
          children = childViewModels,
          fillColor = fillColor,
          isBackbone = nodeDTO.isBackbone,
          x = x,
          y = y
        )
        allNodes += nodeViewModel

        childViewModels.foreach { child =>
          val sourceX = nodeViewModel.x
          val sourceY = nodeViewModel.y + NODE_WIDTH / 2

          val targetX = child.x
          val targetY = child.y - NODE_WIDTH / 2

          // Generate stepped path data
          val pathData = s"M $sourceY $sourceX " +
            s"H ${(sourceY + targetY) / 2} " +
            s"V $targetX " +
            s"H $targetY"

          allLinks += TreeLinkViewModel(nodeViewModel.name, child.name, pathData)
        }

        nodeViewModel
      }

      val rootViewModel = calculateNodePositions(currentDisplayRootDTO, 0, true)

      val maxX = allNodes.map(_.x).maxOption.getOrElse(0.0)
      val maxY = allNodes.map(_.y).maxOption.getOrElse(0.0)

      val svgWidth = maxY + NODE_WIDTH + MARGIN_LEFT * 2
      val svgHeight = maxX + NODE_HEIGHT + MARGIN_TOP * 2

      TreeViewModel(rootViewModel, allNodes.toList, allLinks.toList, svgWidth, svgHeight)
    }
  }
}