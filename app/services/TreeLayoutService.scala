package services

import models.api.*
import models.view.{TreeLinkViewModel, TreeNodeViewModel, TreeViewModel}

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum TreeOrientation:
  case Horizontal, Vertical

/**
 * Provides services for laying out a tree structure for rendering, including calculating coordinates,
 * determining node connections, and styling nodes and links based on their properties.
 */
object TreeLayoutService {

  // Configuration for layout
  private val NODE_WIDTH = 150.0
  private val NODE_HEIGHT = 80.0
  private val MARGIN_TOP = 50.0
  private val MARGIN_LEFT = 120.0 

  // Helper to store mutable state during layout traversal (breadth tracking)
  private var currentBreadthPosition: Double = 0.0

  /**
   * Transforms a TreeDTO into a TreeViewModel with calculated coordinates, link paths, and node colors.
   * Collapses non-backbone branches only when the absolute top-level root (e.g., "Y") is displayed.
   * When re-rooting to any other node, its full subtree is displayed.
   *
   * @param treeDto           The TreeDTO representing the tree to be laid out. treeDto.subclade is the root of the currently displayed tree.
   * @param isAbsoluteTopRoot True ONLY if the current display root (treeDto.subclade) is the actual top-most root of the entire system (e.g., "Y").
   * @param orientation       The orientation of the tree (Horizontal or Vertical). Defaults to Horizontal.
   * @return An Option containing the TreeViewModel if a subclade exists.
   */
  def layoutTree(treeDto: TreeDTO, isAbsoluteTopRoot: Boolean, orientation: TreeOrientation = TreeOrientation.Horizontal): Option[TreeViewModel] = {
    val oneYearAgo = ZonedDateTime.now().minus(1, ChronoUnit.YEARS)
    
    // Determine spacing based on orientation
    // Horizontal: Depth is X (Level), Breadth is Y (Stack). Nodes are 80px high.
    // Vertical: Depth is Y (Level), Breadth is X (Row). Nodes are 150px wide.
    val (depthSpacing, breadthSpacing) = orientation match {
      case TreeOrientation.Horizontal => (200.0, 90.0)
      case TreeOrientation.Vertical => (130.0, 180.0)
    }

    treeDto.subclade.map { currentDisplayRootDTO =>
      // Reset breadth tracker
      currentBreadthPosition = orientation match {
        case TreeOrientation.Horizontal => MARGIN_TOP
        case TreeOrientation.Vertical => MARGIN_LEFT
      }

      val allNodes = collection.mutable.ListBuffer[TreeNodeViewModel]()
      val allLinks = collection.mutable.ListBuffer[TreeLinkViewModel]()

      def calculateNodePositions(nodeDTO: TreeNodeDTO, depth: Int, isCurrentDisplayRoot: Boolean): TreeNodeViewModel = {
        // Depth Position (Level)
        // Horizontal: Left-to-Right axis (svg x).
        // Vertical: Top-to-Bottom axis (svg y).
        val depthPos = depth * depthSpacing + (if (orientation == TreeOrientation.Horizontal) MARGIN_LEFT else MARGIN_TOP)

        val isRecentlyUpdated = nodeDTO.updated.isAfter(oneYearAgo)

        val fillColor = if (nodeDTO.isBackbone) {
          "#d4edda"  // Soft sage green (established)
        } else if (isRecentlyUpdated) {
          "#ffeeba"  // Warm amber/tan (recently edited)
        } else {
          "#f8f9fa"  // Light gray (default)
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

        // Breadth Position (Stack/Row)
        // Horizontal: Top-to-Bottom axis (svg y).
        // Vertical: Left-to-Right axis (svg x).
        val breadthPos = if (childViewModels.isEmpty) {
          val assigned = currentBreadthPosition
          currentBreadthPosition += breadthSpacing
          assigned
        } else {
          val firstChild = childViewModels.head
          val lastChild = childViewModels.last
          // Children store: x = breadth, y = depth.
          (firstChild.x + lastChild.x) / 2
        }

        // Store in ViewModel:
        // x = Breadth (Vertical pos in Horizontal layout; Horizontal pos in Vertical layout)
        // y = Depth (Horizontal pos in Horizontal layout; Vertical pos in Vertical layout)
        // This naming is confusing but preserved for backward compatibility with haplogroup.scala.html
        // haplogroup.scala.html expects: x="@(node.y...)" (Depth->SVG X), y="@(node.x...)" (Breadth->SVG Y)
        val nodeViewModel = TreeNodeViewModel(
          name = nodeDTO.name,
          variantsCount = nodeDTO.variantCount,
          children = childViewModels,
          fillColor = fillColor,
          isBackbone = nodeDTO.isBackbone,
          isRecentlyUpdated = isRecentlyUpdated,
          formedYbp = nodeDTO.formedYbp,
          tmrcaYbp = nodeDTO.tmrcaYbp,
          x = breadthPos, // Breadth
          y = depthPos    // Depth
        )
        allNodes += nodeViewModel

        childViewModels.foreach { child =>
          // Generate path data based on orientation
          val pathData = orientation match {
            case TreeOrientation.Horizontal =>
              // Standard Layout (Left-to-Right)
              // SVG X = Depth (y), SVG Y = Breadth (x)
              // Source: (node.y, node.x)
              // Target: (child.y, child.x)
              // Exit Right side of Source: (sourceY + WIDTH/2, sourceX) ?? 
              // Wait, previous code used (sourceY + WIDTH/2). 
              // Actually haplogroup.scala.html: rect x = node.y - 75. Center = node.y.
              // So right edge = node.y + 75. 
              // Let's assume the previous logic: s"M $sourceY $sourceX" was using Center coordinates.
              // If node.y is Center X, node.x is Center Y.
              
              val sourceDepth = nodeViewModel.y + NODE_WIDTH / 2
              val sourceBreadth = nodeViewModel.x
              
              val targetDepth = child.y - NODE_WIDTH / 2
              val targetBreadth = child.x
              
              // M (Depth) (Breadth) -> M x y
              s"M $sourceDepth $sourceBreadth " +
                s"H ${(sourceDepth + targetDepth) / 2} " +
                s"V $targetBreadth " +
                s"H $targetDepth"
                
            case TreeOrientation.Vertical =>
              // Block Layout (Top-to-Bottom)
              // SVG X = Breadth (x), SVG Y = Depth (y)
              // Source: (node.x, node.y)
              // Target: (child.x, child.y)
              // Exit Bottom of Source: (sourceX, sourceY + HEIGHT/2)
              // haplogroup.scala.html: rect y = node.x - 40. Center = node.x ?? NO.
              // In Vertical, node.x is Breadth (SVG X). Rect x = node.x - 75. Center = node.x.
              // node.y is Depth (SVG Y). Rect y = node.y - 40. Center = node.y.
              // Height is 80. Bottom = node.y + 40.
              
              val sourceBreadth = nodeViewModel.x
              val sourceDepth = nodeViewModel.y + NODE_HEIGHT / 2
              
              val targetBreadth = child.x
              val targetDepth = child.y - NODE_HEIGHT / 2
              
              // M (Breadth) (Depth) -> M x y
              s"M $sourceBreadth $sourceDepth " +
                s"V ${(sourceDepth + targetDepth) / 2} " +
                s"H $targetBreadth " +
                s"V $targetDepth"
          }

          allLinks += TreeLinkViewModel(nodeViewModel.name, child.name, pathData)
        }

        nodeViewModel
      }

      val rootViewModel = calculateNodePositions(currentDisplayRootDTO, 0, true)

      // Calculate SVG dimensions
      // x = Breadth, y = Depth
      val maxBreadth = allNodes.map(_.x).maxOption.getOrElse(0.0)
      val maxDepth = allNodes.map(_.y).maxOption.getOrElse(0.0)

      val (svgWidth, svgHeight) = orientation match {
        case TreeOrientation.Horizontal =>
          // Width = Depth, Height = Breadth
          (maxDepth + NODE_WIDTH + MARGIN_LEFT, maxBreadth + NODE_HEIGHT + MARGIN_TOP)
        case TreeOrientation.Vertical =>
          // Width = Breadth, Height = Depth
          (maxBreadth + NODE_WIDTH + MARGIN_LEFT, maxDepth + NODE_HEIGHT + MARGIN_TOP)
      }

      TreeViewModel(rootViewModel, allNodes.toList, allLinks.toList, svgWidth, svgHeight)
    }
  }
}