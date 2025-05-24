package models.view

import models.api.*

/**
 * Represents a tree node after layout calculation, ready for SVG rendering in the view.
 * Contains original TreeNodeDTO data plus calculated x, y coordinates.
 */
case class TreeNodeViewModel(
                              name: String,
                              variantsCount: Option[Int],
                              children: List[TreeNodeViewModel],
                              fillColor: String,
                              isBackbone: Boolean,
                              x: Double, // Calculated vertical position for SVG
                              y: Double  // Calculated horizontal position (depth) for SVG
                            )

/**
 * Represents a link between two tree nodes, with pre-calculated SVG path data, ready for the view.
 */
case class TreeLinkViewModel(
                              sourceName: String, // Useful for debugging or associating with original nodes
                              targetName: String,
                              pathData: String // The 'd' attribute for SVG <path>
                            )

/**
 * The top-level View Model holding all data needed to render the entire SVG tree.
 *
 * @param rootNode The root of the tree hierarchy in view model format.
 * @param allNodes A flat list of all nodes in view model format for easier iteration.
 * @param allLinks A flat list of all links in view model format.
 * @param svgWidth Calculated width for the SVG viewport.
 * @param svgHeight Calculated height for the SVG viewport.
 */
case class TreeViewModel(
                          rootNode: TreeNodeViewModel,
                          allNodes: Seq[TreeNodeViewModel],
                          allLinks: Seq[TreeLinkViewModel],
                          svgWidth: Double,
                          svgHeight: Double
                        )