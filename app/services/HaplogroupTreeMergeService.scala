package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.*
import models.domain.haplogroups.{MergeContext, VariantIndex}
import play.api.Logging
import services.tree.{ChangeSetCallbacks, TreeMergeAlgorithmService, TreeMergePreviewService, TreeMergeProvenanceService, VariantMatchingService}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for merging external haplogroup trees into the DecodingUs baseline tree.
 *
 * This is the orchestrator that coordinates the merge workflow:
 * - Creates and finalizes change sets for tracking
 * - Delegates to TreeMergeAlgorithmService for the core algorithm
 * - Delegates to TreeMergePreviewService for dry-run simulations
 * - Provides callbacks to the algorithm service for change tracking
 *
 * == Public API ==
 *
 * Three entry points for tree merging:
 * - mergeFullTree: Replace/extend the full tree for a haplogroup type
 * - mergeSubtree: Merge a subtree under a specific anchor haplogroup
 * - previewMerge: Simulate merge without applying changes
 *
 * == Change Set Lifecycle ==
 *
 * For non-dry-run operations:
 * 1. Create change set (Draft status)
 * 2. Execute algorithm with callbacks to record changes
 * 3. Finalize change set (Ready for Review status)
 *
 * The callback pattern decouples change tracking from the algorithm,
 * allowing the algorithm service to focus purely on tree merging logic.
 */
@Singleton
class HaplogroupTreeMergeService @Inject()(
  treeVersioningService: TreeVersioningService,
  algorithmService: TreeMergeAlgorithmService,
  provenanceService: TreeMergeProvenanceService,
  variantMatchingService: VariantMatchingService,
  previewService: TreeMergePreviewService
)(implicit ec: ExecutionContext) extends Logging {

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
        splits = preview.splits,
        ambiguities = preview.ambiguities
      ))
    } else {
      performMergeWithChangeSet(
        haplogroupType = request.haplogroupType,
        anchorId = None,
        sourceTree = request.sourceTree,
        sourceName = request.sourceName,
        priorityConfig = request.priorityConfig.getOrElse(SourcePriorityConfig(List.empty)),
        conflictStrategy = request.conflictStrategy.getOrElse(ConflictStrategy.HigherPriorityWins),
        preloadedIndex = None,
        stagingMode = request.stagingMode
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
        splits = preview.splits,
        ambiguities = preview.ambiguities
      ))
    } else {
      for {
        // Find the anchor haplogroup using variant index
        index <- variantMatchingService.buildVariantIndex(request.haplogroupType)
        anchorOpt = index.haplogroupByName.get(request.anchorHaplogroupName.toUpperCase)
        anchor = anchorOpt.getOrElse(
          throw new IllegalArgumentException(s"Anchor haplogroup '${request.anchorHaplogroupName}' not found")
        )

        // Load context: Get all descendants of the anchor
        descendants <- algorithmService.getDescendantsRecursive(anchor.id.get)

        // Build scoped index for the anchor and its descendants
        subtreeScope = anchor +: descendants
        subtreeIndex <- variantMatchingService.buildVariantIndexForScope(subtreeScope)

        // Check if the source tree root is the anchor itself
        rootMatch = variantMatchingService.findExistingMatch(request.sourceTree, subtreeIndex)
        rootIsAnchor = rootMatch.exists(_.id == anchor.id)
        effectiveAnchorId = if (rootIsAnchor) None else anchor.id

        result <- performMergeWithChangeSet(
          haplogroupType = request.haplogroupType,
          anchorId = effectiveAnchorId,
          sourceTree = request.sourceTree,
          sourceName = request.sourceName,
          priorityConfig = request.priorityConfig.getOrElse(SourcePriorityConfig(List.empty)),
          conflictStrategy = request.conflictStrategy.getOrElse(ConflictStrategy.HigherPriorityWins),
          preloadedIndex = Some(subtreeIndex),
          stagingMode = request.stagingMode
        )
      } yield result
    }
  }

  /**
   * Preview merge without applying changes.
   */
  def previewMerge(request: MergePreviewRequest): Future[MergePreviewResponse] = {
    for {
      existingIndex <- variantMatchingService.buildVariantIndex(request.haplogroupType)
      preview <- previewService.simulateMerge(
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
   * Perform merge with change set tracking.
   *
   * This method:
   * 1. Creates a change set for tracking
   * 2. Builds callbacks that record changes to the change set
   * 3. Delegates to the algorithm service
   * 4. Finalizes the change set
   */
  private def performMergeWithChangeSet(
    haplogroupType: HaplogroupType,
    anchorId: Option[Int],
    sourceTree: PhyloNodeInput,
    sourceName: String,
    priorityConfig: SourcePriorityConfig,
    conflictStrategy: ConflictStrategy,
    preloadedIndex: Option[VariantIndex],
    stagingMode: Boolean
  ): Future[TreeMergeResponse] = {
    val now = LocalDateTime.now()
    val nodeCount = countNodes(sourceTree)
    val enableChangeTracking = true

    logger.info(s"Starting merge for source '$sourceName' with $nodeCount nodes (stagingMode: $stagingMode)")

    for {
      // Phase 0: Create change set for tracking (if enabled)
      changeSetOpt <- if (enableChangeTracking) {
        treeVersioningService.createChangeSet(
          haplogroupType = haplogroupType,
          sourceName = sourceName,
          description = Some(s"Tree merge from $sourceName with $nodeCount nodes"),
          createdBy = "system"
        ).map(Some(_)).recover {
          case e: IllegalStateException =>
            logger.warn(s"Could not create change set (one may already be active): ${e.getMessage}")
            None
        }
      } else Future.successful(None)
      changeSetId = changeSetOpt.flatMap(_.id)
      _ = changeSetOpt.foreach(cs => logger.info(s"Created change set ${cs.id.get}: ${cs.name}"))

      // Staging mode requires a change set ID for WIP table operations
      effectiveStagingMode = stagingMode && changeSetId.isDefined
      _ = if (stagingMode && !effectiveStagingMode) {
        logger.warn("Staging mode disabled - change set creation failed, falling back to production mode")
      }

      // Build merge context
      context = MergeContext(
        haplogroupType = haplogroupType,
        sourceName = sourceName,
        priorityConfig = priorityConfig,
        conflictStrategy = conflictStrategy,
        timestamp = now,
        changeSetId = changeSetId,
        stagingMode = effectiveStagingMode
      )

      // Create callbacks for change tracking (fire-and-forget pattern)
      callbacks = createCallbacks(changeSetId)

      // Delegate to algorithm service
      result <- algorithmService.performMerge(
        haplogroupType = haplogroupType,
        anchorId = anchorId,
        sourceTree = sourceTree,
        sourceName = sourceName,
        priorityConfig = priorityConfig,
        conflictStrategy = conflictStrategy,
        preloadedIndex = preloadedIndex,
        context = context,
        callbacks = callbacks
      )

      // Finalize change set (if one was created)
      _ <- changeSetId match {
        case Some(csId) =>
          treeVersioningService.finalizeChangeSet(csId, result.statistics, result.ambiguityReportPath).map { success =>
            if (success) logger.info(s"Change set $csId finalized and ready for review")
            else logger.warn(s"Failed to finalize change set $csId")
          }.recover {
            case e: Exception =>
              logger.error(s"Error finalizing change set $csId: ${e.getMessage}")
          }
        case None =>
          Future.successful(())
      }
    } yield result
  }

  /**
   * Create callbacks for change set tracking.
   *
   * The callbacks record changes in a fire-and-forget pattern to avoid
   * slowing down the merge operation. Failures are logged but don't
   * interrupt the merge.
   */
  private def createCallbacks(changeSetId: Option[Int]): Option[ChangeSetCallbacks] = {
    changeSetId.map { csId =>
      new ChangeSetCallbacks {
        def recordCreate(haplogroupJson: String, parentId: Option[Int]): Unit = {
          treeVersioningService.recordCreate(csId, haplogroupJson, parentId).recover {
            case e: Exception => logger.warn(s"Failed to record CREATE change: ${e.getMessage}")
          }
        }

        def recordReparent(haplogroupId: Int, oldParentId: Option[Int], newParentId: Int): Unit = {
          treeVersioningService.recordReparent(csId, haplogroupId, oldParentId, newParentId).recover {
            case e: Exception => logger.warn(s"Failed to record REPARENT change: ${e.getMessage}")
          }
        }
      }
    }
  }

  /** Count total nodes in a tree */
  private def countNodes(node: PhyloNodeInput): Int = {
    1 + node.children.map(countNodes).sum
  }
}
