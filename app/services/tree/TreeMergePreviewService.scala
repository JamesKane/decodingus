package services.tree

import jakarta.inject.{Inject, Singleton}
import models.api.haplogroups.*
import models.domain.haplogroups.VariantIndex
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for preview/simulation of tree merge operations.
 *
 * Provides dry-run analysis that:
 * - Identifies which nodes would be created, updated, or unchanged
 * - Detects conflicts (e.g., conflicting age estimates)
 * - Collects statistics without modifying the database
 */
@Singleton
class TreeMergePreviewService @Inject()(
  variantMatchingService: VariantMatchingService,
  provenanceService: TreeMergeProvenanceService
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Simulate merge without applying changes.
   *
   * @param sourceTree The incoming tree to merge
   * @param sourceName Name of the source (e.g., "ISOGG", "ytree.net")
   * @param existingIndex Variant index of existing haplogroups
   * @param priorityConfig Source priority configuration
   * @return Preview response with statistics and identified changes
   */
  def simulateMerge(
    sourceTree: PhyloNodeInput,
    sourceName: String,
    existingIndex: VariantIndex,
    priorityConfig: SourcePriorityConfig
  ): Future[MergePreviewResponse] = {
    // Recursively analyze the tree
    val (stats, conflicts, splits, ambiguities, newNodes, updatedNodes, unchangedNodes) =
      analyzeTree(sourceTree, existingIndex, sourceName, priorityConfig)

    Future.successful(MergePreviewResponse(
      statistics = stats,
      conflicts = conflicts,
      splits = splits,
      ambiguities = ambiguities,
      newNodes = newNodes,
      updatedNodes = updatedNodes,
      unchangedNodes = unchangedNodes
    ))
  }

  /**
   * Analyze tree structure for preview without making changes.
   *
   * Recursively traverses the source tree, matching against the existing index
   * and collecting statistics about what would happen during a real merge.
   *
   * @param node Current source node being analyzed
   * @param index Variant index for matching
   * @param sourceName Name of the source
   * @param priorityConfig Priority configuration for conflict resolution
   * @return Tuple of (statistics, conflicts, splits, ambiguities, newNodes, updatedNodes, unchangedNodes)
   */
  private def analyzeTree(
    node: PhyloNodeInput,
    index: VariantIndex,
    sourceName: String,
    priorityConfig: SourcePriorityConfig
  ): (MergeStatistics, List[MergeConflict], List[SplitOperation], List[PlacementAmbiguity], List[String], List[String], List[String]) = {

    val existingMatch = variantMatchingService.findExistingMatch(node, index)
    val conflicts = scala.collection.mutable.ListBuffer.empty[MergeConflict]
    val splits = scala.collection.mutable.ListBuffer.empty[SplitOperation]
    val ambiguities = scala.collection.mutable.ListBuffer.empty[PlacementAmbiguity]
    val newNodes = scala.collection.mutable.ListBuffer.empty[String]
    val updatedNodes = scala.collection.mutable.ListBuffer.empty[String]
    val unchangedNodes = scala.collection.mutable.ListBuffer.empty[String]

    var stats = existingMatch match {
      case Some(existing) =>
        val existingSource = existing.provenance.map(_.primaryCredit).getOrElse(existing.source)
        val shouldUpdate = provenanceService.getPriority(sourceName, priorityConfig) < provenanceService.getPriority(existingSource, priorityConfig)

        // Check for conflicts
        if (node.formedYbp.isDefined && existing.formedYbp.isDefined && node.formedYbp != existing.formedYbp) {
          conflicts += MergeConflict(
            haplogroupName = existing.name,
            field = "formedYbp",
            existingValue = existing.formedYbp.get.toString,
            newValue = node.formedYbp.get.toString,
            resolution = if (shouldUpdate) "will_update" else "will_keep_existing",
            existingSource = existingSource,
            newSource = sourceName
          )
        }

        if (shouldUpdate && conflicts.nonEmpty) {
          updatedNodes += existing.name
          MergeStatistics(1, 0, 1, 0, 0, 0, 0, 0, 0)
        } else {
          unchangedNodes += existing.name
          MergeStatistics(1, 0, 0, 1, 0, 0, 0, 0, 0)
        }

      case None =>
        newNodes += node.name
        MergeStatistics(1, 1, 0, 0, node.variants.size, 0, 1, 0, 0)
    }

    // Process children
    node.children.foreach { child =>
      val (childStats, childConflicts, childSplits, childAmbiguities, childNew, childUpdated, childUnchanged) =
        analyzeTree(child, index, sourceName, priorityConfig)
      stats = MergeStatistics.combine(stats, childStats)
      conflicts ++= childConflicts
      splits ++= childSplits
      ambiguities ++= childAmbiguities
      newNodes ++= childNew
      updatedNodes ++= childUpdated
      unchangedNodes ++= childUnchanged
    }

    (stats, conflicts.toList, splits.toList, ambiguities.toList, newNodes.toList, updatedNodes.toList, unchangedNodes.toList)
  }
}
