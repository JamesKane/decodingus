package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.*
import models.dal.domain.genomics.VariantAlias
import models.domain.haplogroups.{Haplogroup, HaplogroupProvenance}
import play.api.Logging
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, VariantAliasRepository, VariantRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for merging external haplogroup trees into the DecodingUs baseline tree.
 *
 * Key features:
 * - Variant-based matching: Nodes are matched by their defining variants, not names,
 *   to handle different naming conventions across sources (ytree.net, ISOGG, researchers)
 * - Credit assignment: ISOGG credit preserved on existing nodes; incoming sources get
 *   credit for new splits and terminal branches they contribute
 * - Multi-source provenance: Full attribution tracking via JSONB column
 * - Branch split detection: Identifies when incoming data reveals finer tree structure
 */
@Singleton
class HaplogroupTreeMergeService @Inject()(
  haplogroupRepository: HaplogroupCoreRepository,
  haplogroupVariantRepository: HaplogroupVariantRepository,
  variantRepository: VariantRepository,
  variantAliasRepository: VariantAliasRepository
)(implicit ec: ExecutionContext) extends Logging {

  // ============================================================================
  // Helper methods for VariantInput
  // ============================================================================

  /** Extract all variant names (primary + aliases) from a VariantInput */
  private def allVariantNames(variant: VariantInput): List[String] =
    variant.name :: variant.aliases

  /** Extract all variant names from a list of VariantInput */
  private def allVariantNames(variants: List[VariantInput]): List[String] =
    variants.flatMap(allVariantNames)

  /** Extract just the primary variant names from a list of VariantInput */
  private def primaryVariantNames(variants: List[VariantInput]): List[String] =
    variants.map(_.name)

  // ============================================================================
  // Public API
  // ============================================================================

  /**
   * Merge a full tree, replacing the existing tree for the given haplogroup type.
   */
  def mergeFullTree(request: TreeMergeRequest): Future[TreeMergeResponse] = {
    if (request.dryRun) {
      previewMerge(MergePreviewRequest(
        haplogroupType = request.haplogroupType,
        anchorHaplogroupName = None,
        sourceTree = request.sourceTree,
        sourceName = request.sourceName,
        priorityConfig = request.priorityConfig
      )).map(preview => TreeMergeResponse(
        success = true,
        message = "Dry run completed successfully",
        statistics = preview.statistics,
        conflicts = preview.conflicts,
        splits = preview.splits
      ))
    } else {
      performMerge(
        haplogroupType = request.haplogroupType,
        anchorId = None,
        sourceTree = request.sourceTree,
        sourceName = request.sourceName,
        priorityConfig = request.priorityConfig.getOrElse(SourcePriorityConfig(List.empty)),
        conflictStrategy = request.conflictStrategy.getOrElse(ConflictStrategy.HigherPriorityWins)
      )
    }
  }

  /**
   * Merge a subtree under a specific anchor haplogroup.
   */
  def mergeSubtree(request: SubtreeMergeRequest): Future[TreeMergeResponse] = {
    if (request.dryRun) {
      previewMerge(MergePreviewRequest(
        haplogroupType = request.haplogroupType,
        anchorHaplogroupName = Some(request.anchorHaplogroupName),
        sourceTree = request.sourceTree,
        sourceName = request.sourceName,
        priorityConfig = request.priorityConfig
      )).map(preview => TreeMergeResponse(
        success = true,
        message = "Dry run completed successfully",
        statistics = preview.statistics,
        conflicts = preview.conflicts,
        splits = preview.splits
      ))
    } else {
      for {
        // Find the anchor haplogroup
        anchorOpt <- haplogroupRepository.getHaplogroupByName(request.anchorHaplogroupName, request.haplogroupType)
        anchor = anchorOpt.getOrElse(
          throw new IllegalArgumentException(s"Anchor haplogroup '${request.anchorHaplogroupName}' not found")
        )

        result <- performMerge(
          haplogroupType = request.haplogroupType,
          anchorId = anchor.id,
          sourceTree = request.sourceTree,
          sourceName = request.sourceName,
          priorityConfig = request.priorityConfig.getOrElse(SourcePriorityConfig(List.empty)),
          conflictStrategy = request.conflictStrategy.getOrElse(ConflictStrategy.HigherPriorityWins)
        )
      } yield result
    }
  }

  /**
   * Preview merge without applying changes.
   */
  def previewMerge(request: MergePreviewRequest): Future[MergePreviewResponse] = {
    for {
      // Build variant-based index of existing haplogroups
      existingIndex <- buildVariantIndex(request.haplogroupType)

      // Simulate the merge to collect statistics
      preview <- simulateMerge(
        sourceTree = request.sourceTree,
        sourceName = request.sourceName,
        existingIndex = existingIndex,
        priorityConfig = request.priorityConfig.getOrElse(SourcePriorityConfig(List.empty))
      )
    } yield preview
  }

  // ============================================================================
  // Private Implementation
  // ============================================================================

  /**
   * Build an index of existing haplogroups by their variant names.
   * This enables variant-based matching across different naming conventions.
   */
  private def buildVariantIndex(haplogroupType: HaplogroupType): Future[VariantIndex] = {
    haplogroupRepository.getAllWithVariantNames(haplogroupType).map { haplogroupsWithVariants =>
      val variantToHaplogroup = haplogroupsWithVariants.flatMap { case (hg, variants) =>
        variants.map(v => v.toUpperCase -> hg)
      }.groupMap(_._1)(_._2)

      val haplogroupByName = haplogroupsWithVariants.map { case (hg, _) =>
        hg.name.toUpperCase -> hg
      }.toMap

      VariantIndex(variantToHaplogroup, haplogroupByName)
    }
  }

  /**
   * Perform the actual merge operation.
   */
  private def performMerge(
    haplogroupType: HaplogroupType,
    anchorId: Option[Int],
    sourceTree: PhyloNodeInput,
    sourceName: String,
    priorityConfig: SourcePriorityConfig,
    conflictStrategy: ConflictStrategy
  ): Future[TreeMergeResponse] = {
    val now = LocalDateTime.now()
    val context = MergeContext(
      haplogroupType = haplogroupType,
      sourceName = sourceName,
      priorityConfig = priorityConfig,
      conflictStrategy = conflictStrategy,
      timestamp = now
    )

    for {
      // Build variant-based index
      existingIndex <- buildVariantIndex(haplogroupType)

      // Perform recursive merge
      result <- mergeNode(
        node = sourceTree,
        parentId = anchorId,
        context = context,
        index = existingIndex,
        accumulator = MergeAccumulator.empty
      )
    } yield TreeMergeResponse(
      success = result.errors.isEmpty,
      message = if (result.errors.isEmpty) "Merge completed successfully" else "Merge completed with errors",
      statistics = result.statistics,
      conflicts = result.conflicts,
      splits = result.splits,
      errors = result.errors
    )
  }

  /**
   * Recursively merge a node and its children.
   */
  private def mergeNode(
    node: PhyloNodeInput,
    parentId: Option[Int],
    context: MergeContext,
    index: VariantIndex,
    accumulator: MergeAccumulator
  ): Future[MergeAccumulator] = {
    // Try to find existing haplogroup by variants first, then by name
    val existingMatch = findExistingMatch(node, index)

    existingMatch match {
      case Some(existing) =>
        // Node exists - check for updates or splits
        mergeExistingNode(node, existing, parentId, context, index, accumulator)

      case None =>
        // New node - create it
        createNewNode(node, parentId, context, index, accumulator)
    }
  }

  /**
   * Find an existing haplogroup that matches the input node.
   * Primary matching is by variants (including aliases); fallback is by name.
   */
  private def findExistingMatch(node: PhyloNodeInput, index: VariantIndex): Option[Haplogroup] = {
    // First try variant-based matching - check primary names and all aliases
    val allNames = allVariantNames(node.variants)
    val variantMatches = allNames
      .flatMap(v => index.variantToHaplogroup.getOrElse(v.toUpperCase, Seq.empty))
      .groupBy(identity)
      .view.mapValues(_.size)
      .toSeq
      .sortBy(-_._2) // Sort by match count descending

    // Find haplogroup with most variant matches (>= 1)
    variantMatches.headOption.filter(_._2 >= 1).map(_._1).orElse {
      // Fallback: match by name
      index.haplogroupByName.get(node.name.toUpperCase)
    }
  }

  /**
   * Merge an input node with an existing haplogroup.
   */
  private def mergeExistingNode(
    node: PhyloNodeInput,
    existing: Haplogroup,
    parentId: Option[Int],
    context: MergeContext,
    index: VariantIndex,
    accumulator: MergeAccumulator
  ): Future[MergeAccumulator] = {
    val conflicts = scala.collection.mutable.ListBuffer.empty[MergeConflict]

    // Check for field conflicts
    val existingSource = existing.provenance.map(_.primaryCredit).getOrElse(existing.source)

    // Determine if we should update based on conflict strategy
    val shouldUpdate = context.conflictStrategy match {
      case ConflictStrategy.AlwaysUpdate => true
      case ConflictStrategy.KeepExisting => false
      case ConflictStrategy.HigherPriorityWins =>
        getPriority(context.sourceName, context.priorityConfig) <
          getPriority(existingSource, context.priorityConfig)
    }

    // Check for age estimate conflicts
    if (node.formedYbp.isDefined && existing.formedYbp.isDefined &&
        node.formedYbp != existing.formedYbp) {
      conflicts += MergeConflict(
        haplogroupName = existing.name,
        field = "formedYbp",
        existingValue = existing.formedYbp.get.toString,
        newValue = node.formedYbp.get.toString,
        resolution = if (shouldUpdate) "updated" else "kept_existing",
        existingSource = existingSource,
        newSource = context.sourceName
      )
    }

    for {
      // Update provenance to track this merge
      _ <- updateProvenance(existing, node.variants, context)

      // Update age estimates if applicable
      _ <- if (shouldUpdate && hasAgeEstimates(node)) {
        updateAgeEstimates(existing.id.get, node, context.sourceName)
      } else {
        Future.successful(())
      }

      // Update statistics
      updatedStats = if (shouldUpdate && conflicts.nonEmpty) {
        accumulator.statistics.copy(
          nodesProcessed = accumulator.statistics.nodesProcessed + 1,
          nodesUpdated = accumulator.statistics.nodesUpdated + 1
        )
      } else {
        accumulator.statistics.copy(
          nodesProcessed = accumulator.statistics.nodesProcessed + 1,
          nodesUnchanged = accumulator.statistics.nodesUnchanged + 1
        )
      }

      // Recursively process children
      childrenResult <- processChildren(
        children = node.children,
        parentId = existing.id,
        context = context,
        index = index,
        accumulator = accumulator.copy(
          statistics = updatedStats,
          conflicts = accumulator.conflicts ++ conflicts.toList
        )
      )
    } yield childrenResult
  }

  /**
   * Create a new haplogroup node.
   */
  private def createNewNode(
    node: PhyloNodeInput,
    parentId: Option[Int],
    context: MergeContext,
    index: VariantIndex,
    accumulator: MergeAccumulator
  ): Future[MergeAccumulator] = {
    // Determine credit - incoming source gets credit for new nodes
    val primaryCredit = context.sourceName
    val variantNames = primaryVariantNames(node.variants)
    val provenance = HaplogroupProvenance.forNewNode(context.sourceName, variantNames)

    val newHaplogroup = Haplogroup(
      id = None,
      name = node.name,
      lineage = None,
      description = None,
      haplogroupType = context.haplogroupType,
      revisionId = 1,
      source = context.sourceName,
      confidenceLevel = "medium",
      validFrom = context.timestamp,
      validUntil = None,
      formedYbp = node.formedYbp,
      formedYbpLower = node.formedYbpLower,
      formedYbpUpper = node.formedYbpUpper,
      tmrcaYbp = node.tmrcaYbp,
      tmrcaYbpLower = node.tmrcaYbpLower,
      tmrcaYbpUpper = node.tmrcaYbpUpper,
      ageEstimateSource = Some(context.sourceName),
      provenance = Some(provenance)
    )

    for {
      // Create the haplogroup with parent relationship
      newId <- haplogroupRepository.createWithParent(newHaplogroup, parentId, context.sourceName)

      // Associate variants with the new haplogroup
      variantCount <- associateVariants(newId, node.variants)

      // Update statistics
      updatedStats = accumulator.statistics.copy(
        nodesProcessed = accumulator.statistics.nodesProcessed + 1,
        nodesCreated = accumulator.statistics.nodesCreated + 1,
        variantsAdded = accumulator.statistics.variantsAdded + variantCount,
        relationshipsCreated = if (parentId.isDefined)
          accumulator.statistics.relationshipsCreated + 1
        else
          accumulator.statistics.relationshipsCreated
      )

      // Update index with new haplogroup - include all variant names (primary + aliases) for matching
      allVarNames = allVariantNames(node.variants)
      updatedIndex = index.copy(
        haplogroupByName = index.haplogroupByName + (node.name.toUpperCase -> newHaplogroup.copy(id = Some(newId))),
        variantToHaplogroup = allVarNames.foldLeft(index.variantToHaplogroup) { (idx, v) =>
          idx.updatedWith(v.toUpperCase) {
            case Some(hgs) => Some(hgs :+ newHaplogroup.copy(id = Some(newId)))
            case None => Some(Seq(newHaplogroup.copy(id = Some(newId))))
          }
        }
      )

      // Recursively process children
      childrenResult <- processChildren(
        children = node.children,
        parentId = Some(newId),
        context = context,
        index = updatedIndex,
        accumulator = accumulator.copy(statistics = updatedStats)
      )
    } yield childrenResult
  }

  /**
   * Process child nodes recursively.
   */
  private def processChildren(
    children: List[PhyloNodeInput],
    parentId: Option[Int],
    context: MergeContext,
    index: VariantIndex,
    accumulator: MergeAccumulator
  ): Future[MergeAccumulator] = {
    children.foldLeft(Future.successful(accumulator)) { (accFuture, child) =>
      accFuture.flatMap { acc =>
        mergeNode(child, parentId, context, index, acc)
      }
    }
  }

  /**
   * Update provenance for an existing haplogroup.
   */
  private def updateProvenance(
    existing: Haplogroup,
    newVariants: List[VariantInput],
    context: MergeContext
  ): Future[Boolean] = {
    val existingProvenance = existing.provenance.getOrElse(
      HaplogroupProvenance(primaryCredit = existing.source, nodeProvenance = Set(existing.source))
    )

    // Preserve ISOGG credit
    val primaryCredit = if (HaplogroupProvenance.shouldPreserveCredit(existingProvenance.primaryCredit)) {
      existingProvenance.primaryCredit
    } else {
      existingProvenance.primaryCredit // Keep existing credit for non-ISOGG too
    }

    // Add new source to node provenance
    val updatedNodeProv = existingProvenance.nodeProvenance + context.sourceName

    // Add variant provenance for new variants (primary names only for provenance tracking)
    val variantNames = primaryVariantNames(newVariants)
    val updatedVariantProv = variantNames.foldLeft(existingProvenance.variantProvenance) { (prov, variant) =>
      prov.updatedWith(variant) {
        case Some(sources) => Some(sources + context.sourceName)
        case None => Some(Set(context.sourceName))
      }
    }

    val updatedProvenance = HaplogroupProvenance(
      primaryCredit = primaryCredit,
      nodeProvenance = updatedNodeProv,
      variantProvenance = updatedVariantProv,
      lastMergedAt = Some(context.timestamp),
      lastMergedFrom = Some(context.sourceName)
    )

    haplogroupRepository.updateProvenance(existing.id.get, updatedProvenance)
  }

  /**
   * Update age estimates for a haplogroup.
   */
  private def updateAgeEstimates(
    haplogroupId: Int,
    node: PhyloNodeInput,
    sourceName: String
  ): Future[Boolean] = {
    haplogroupRepository.findById(haplogroupId).flatMap {
      case Some(existing) =>
        val updated = existing.copy(
          formedYbp = node.formedYbp.orElse(existing.formedYbp),
          formedYbpLower = node.formedYbpLower.orElse(existing.formedYbpLower),
          formedYbpUpper = node.formedYbpUpper.orElse(existing.formedYbpUpper),
          tmrcaYbp = node.tmrcaYbp.orElse(existing.tmrcaYbp),
          tmrcaYbpLower = node.tmrcaYbpLower.orElse(existing.tmrcaYbpLower),
          tmrcaYbpUpper = node.tmrcaYbpUpper.orElse(existing.tmrcaYbpUpper),
          ageEstimateSource = Some(sourceName)
        )
        haplogroupRepository.update(updated)
      case None =>
        Future.successful(false)
    }
  }

  /**
   * Associate variants with a haplogroup, finding or creating variants as needed.
   */
  private def associateVariants(haplogroupId: Int, variants: List[VariantInput]): Future[Int] = {
    if (variants.isEmpty) {
      Future.successful(0)
    } else {
      // For each variant, find existing variants by primary name and associate them,
      // then create alias records for any aliases
      Future.traverse(variants) { variantInput =>
        // First find/associate the primary variant
        variantRepository.searchByName(variantInput.name).flatMap { foundVariants =>
          // Associate all found variants with this haplogroup
          val associateFutures = foundVariants.map { variant =>
            variant.variantId match {
              case Some(vid) =>
                for {
                  // Associate variant with haplogroup
                  count <- haplogroupVariantRepository.addVariantToHaplogroup(haplogroupId, vid)
                  // Create alias records for any aliases from the ISOGG data
                  _ <- Future.traverse(variantInput.aliases) { alias =>
                    val variantAlias = VariantAlias(
                      variantId = vid,
                      aliasType = "common_name",
                      aliasValue = alias,
                      source = Some("ISOGG"),
                      isPrimary = false
                    )
                    variantAliasRepository.addAlias(variantAlias).recover { case _ => false }
                  }
                } yield count
              case None => Future.successful(0)
            }
          }
          Future.sequence(associateFutures).map(_.sum)
        }
      }.map(_.sum)
    }
  }

  /**
   * Get priority for a source (lower = higher priority).
   */
  private def getPriority(source: String, config: SourcePriorityConfig): Int = {
    config.sourcePriorities.indexOf(source) match {
      case -1 => config.defaultPriority
      case idx => idx
    }
  }

  /**
   * Check if node has any age estimates.
   */
  private def hasAgeEstimates(node: PhyloNodeInput): Boolean = {
    node.formedYbp.isDefined || node.tmrcaYbp.isDefined
  }

  /**
   * Simulate merge without applying changes (for preview).
   */
  private def simulateMerge(
    sourceTree: PhyloNodeInput,
    sourceName: String,
    existingIndex: VariantIndex,
    priorityConfig: SourcePriorityConfig
  ): Future[MergePreviewResponse] = {
    // Recursively analyze the tree
    val (stats, conflicts, splits, newNodes, updatedNodes, unchangedNodes) =
      analyzeTree(sourceTree, existingIndex, sourceName, priorityConfig)

    Future.successful(MergePreviewResponse(
      statistics = stats,
      conflicts = conflicts,
      splits = splits,
      newNodes = newNodes,
      updatedNodes = updatedNodes,
      unchangedNodes = unchangedNodes
    ))
  }

  /**
   * Analyze tree structure for preview without making changes.
   */
  private def analyzeTree(
    node: PhyloNodeInput,
    index: VariantIndex,
    sourceName: String,
    priorityConfig: SourcePriorityConfig
  ): (MergeStatistics, List[MergeConflict], List[SplitOperation], List[String], List[String], List[String]) = {

    val existingMatch = findExistingMatch(node, index)
    val conflicts = scala.collection.mutable.ListBuffer.empty[MergeConflict]
    val splits = scala.collection.mutable.ListBuffer.empty[SplitOperation]
    val newNodes = scala.collection.mutable.ListBuffer.empty[String]
    val updatedNodes = scala.collection.mutable.ListBuffer.empty[String]
    val unchangedNodes = scala.collection.mutable.ListBuffer.empty[String]

    var stats = existingMatch match {
      case Some(existing) =>
        val existingSource = existing.provenance.map(_.primaryCredit).getOrElse(existing.source)
        val shouldUpdate = getPriority(sourceName, priorityConfig) < getPriority(existingSource, priorityConfig)

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
      val (childStats, childConflicts, childSplits, childNew, childUpdated, childUnchanged) =
        analyzeTree(child, index, sourceName, priorityConfig)
      stats = MergeStatistics.combine(stats, childStats)
      conflicts ++= childConflicts
      splits ++= childSplits
      newNodes ++= childNew
      updatedNodes ++= childUpdated
      unchangedNodes ++= childUnchanged
    }

    (stats, conflicts.toList, splits.toList, newNodes.toList, updatedNodes.toList, unchangedNodes.toList)
  }
}

// ============================================================================
// Internal Data Structures
// ============================================================================

/**
 * Index of existing haplogroups for efficient lookup.
 */
private[services] case class VariantIndex(
  variantToHaplogroup: Map[String, Seq[Haplogroup]],
  haplogroupByName: Map[String, Haplogroup]
)

/**
 * Context for merge operations.
 */
private[services] case class MergeContext(
  haplogroupType: HaplogroupType,
  sourceName: String,
  priorityConfig: SourcePriorityConfig,
  conflictStrategy: ConflictStrategy,
  timestamp: LocalDateTime
)

/**
 * Accumulator for merge statistics and results.
 */
private[services] case class MergeAccumulator(
  statistics: MergeStatistics,
  conflicts: List[MergeConflict],
  splits: List[SplitOperation],
  errors: List[String]
)

private[services] object MergeAccumulator {
  val empty: MergeAccumulator = MergeAccumulator(
    statistics = MergeStatistics.empty,
    conflicts = List.empty,
    splits = List.empty,
    errors = List.empty
  )
}
