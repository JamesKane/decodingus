package services.tree

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.*
import models.domain.haplogroups.{ExistingTree, ExistingTreeNode, Haplogroup, HaplogroupProvenance, MergeAccumulator, MergeCase, MergeContext, VariantCache, VariantIndex}
import play.api.Logging
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, VariantV2Repository}
import services.{TreeMergeStagingHelper, TreeVersioningService}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Callback trait for change set tracking.
 *
 * The orchestrator (HaplogroupTreeMergeService) provides implementations
 * of these callbacks to track changes without the algorithm service
 * needing direct access to TreeVersioningService.
 */
trait ChangeSetCallbacks {
  /** Record creation of a new haplogroup */
  def recordCreate(haplogroupJson: String, parentId: Option[Int]): Unit

  /** Record reparenting of an existing haplogroup */
  def recordReparent(haplogroupId: Int, oldParentId: Option[Int], newParentId: Int): Unit
}

/**
 * Service implementing the core Identify-Match-Graft tree merge algorithm.
 *
 * This service contains the algorithmic logic for merging external haplogroup trees
 * into the DecodingUs baseline tree. It accepts callbacks from the orchestrator for
 * change set tracking, keeping concerns separated.
 *
 * == Algorithm Overview ==
 *
 * The Identify-Match-Graft algorithm operates in four phases:
 *
 * Phase 1 (Normalization): Both trees converted to standardized format with:
 *   - U(N): Unique SNP set - variants defined at this node only
 *   - C(N): Cumulative SNP set - all variants from root to N
 *
 * Phase 2 (Alignment): Parallel tree traversal using SNP set intersection.
 *
 * Phase 3 (Conflict Resolution): Four-way classification:
 *   - Case A (FULL_MATCH): U(T₁) = U(T₀)
 *   - Case B (SOURCE_IS_ANCESTOR): U(T₁) ⊂ U(T₀)
 *   - Case C (SOURCE_IS_DESCENDANT): U(T₁) ⊃ U(T₀)
 *   - Case D (DISJOINT_BRANCH): partial or no overlap
 *
 * Phase 4 (Grafting): Node contraction when T₁ provides finer resolution.
 */
@Singleton
class TreeMergeAlgorithmService @Inject()(
  haplogroupRepository: HaplogroupCoreRepository,
  haplogroupVariantRepository: HaplogroupVariantRepository,
  variantV2Repository: VariantV2Repository,
  stagingHelper: TreeMergeStagingHelper,
  provenanceService: TreeMergeProvenanceService,
  variantMatchingService: VariantMatchingService
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

  /** Recursively collect all variant names from a PhyloNodeInput tree */
  private def collectAllVariantNames(node: PhyloNodeInput): List[String] = {
    val nodeVariants = allVariantNames(node.variants)
    val childVariants = node.children.flatMap(collectAllVariantNames)
    nodeVariants ++ childVariants
  }

  /** Count total nodes in a tree */
  private def countNodes(node: PhyloNodeInput): Int = {
    1 + node.children.map(countNodes).sum
  }

  /**
   * Recursively fetch all descendants of a haplogroup.
   */
  def getDescendantsRecursive(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    haplogroupRepository.getDescendants(haplogroupId)
  }

  // ============================================================================
  // Core Algorithm Entry Point
  // ============================================================================

  /**
   * Perform the actual merge operation using parallel tree traversal.
   *
   * Key insight: We traverse both trees (source and existing) in parallel,
   * only matching source children against existing children of already-matched parents.
   * This prevents cross-branch mismatches that would cause incorrect reparenting.
   *
   * @param haplogroupType Y or mtDNA
   * @param anchorId Optional parent ID for subtree merges
   * @param sourceTree The incoming tree structure
   * @param sourceName Name of the source (e.g., "ISOGG")
   * @param priorityConfig Source priority configuration
   * @param conflictStrategy How to resolve conflicts
   * @param preloadedIndex Optional pre-built variant index (for subtree merges)
   * @param context Merge context with change set info
   * @param callbacks Optional callbacks for change set tracking
   * @return TreeMergeResponse with statistics and any conflicts/ambiguities
   */
  def performMerge(
    haplogroupType: HaplogroupType,
    anchorId: Option[Int],
    sourceTree: PhyloNodeInput,
    sourceName: String,
    priorityConfig: SourcePriorityConfig,
    conflictStrategy: ConflictStrategy,
    preloadedIndex: Option[VariantIndex],
    context: MergeContext,
    callbacks: Option[ChangeSetCallbacks]
  ): Future[TreeMergeResponse] = {
    val now = context.timestamp
    val nodeCount = countNodes(sourceTree)

    // Reset placeholder counter for this merge operation
    stagingHelper.resetPlaceholderCounter()

    logger.info(s"Starting merge for source '$sourceName' with $nodeCount nodes (stagingMode: ${context.stagingMode})")

    for {
      // Phase 1a: Build in-memory tree of existing haplogroups with indexes
      existingTreeOpt <- variantMatchingService.buildExistingTree(haplogroupType).map(_.map(ExistingTree.fromRoot))
      _ = logger.info(s"Existing tree built with indexes: ${existingTreeOpt.map(t => s"${t.byName.size} nodes").getOrElse("no root found")}")

      // Phase 1b: Preload all variants from the source tree
      allVariantNamesInTree = collectAllVariantNames(sourceTree).distinct
      _ = logger.info(s"Preloading ${allVariantNamesInTree.size} distinct variant names...")
      variantLookup <- {
        val startTime = System.currentTimeMillis()
        variantV2Repository.searchByNames(allVariantNamesInTree).map { result =>
          val elapsed = System.currentTimeMillis() - startTime
          logger.info(s"Variant lookup completed in ${elapsed}ms, found ${result.size} unique variants")
          result
        }
      }
      variantCache = VariantCache(
        nameToVariantId = variantLookup.flatMap { case (name, variants) =>
          variants.flatMap(_.variantId).headOption.map(id => name -> id)
        }
      )
      _ = logger.info(s"Variant cache built with ${variantCache.nameToVariantId.size} entries")

      // Phase 2: Compute root's cumulative variants and find matching node
      // For ROOT node: try variant match first, then fall back to name match
      // (Root nodes often have no variants, just a name like "Y")
      rootSourceVariants = sourceTree.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet
      matchedExistingNode = existingTreeOpt.flatMap { tree =>
        tree.findMatchByVariants(rootSourceVariants).orElse {
          // Fallback: match root by name if no variants
          if (rootSourceVariants.isEmpty) tree.findByName(sourceTree.name) else None
        }
      }
      _ = logger.info(s"Source root '${sourceTree.name}' (variants: ${rootSourceVariants.take(5).mkString(",")}) matched to existing: ${matchedExistingNode.map(_.haplogroup.name).getOrElse("none (will create)")}")

      // Phase 3: Perform merge with cumulative variant tracking
      result <- mergeWithIndexedTree(
        sourceNode = sourceTree,
        sourceCumulativeVariants = rootSourceVariants,
        existingNode = matchedExistingNode,
        existingTree = existingTreeOpt,
        parentId = anchorId,
        context = context,
        variantCache = variantCache,
        accumulator = MergeAccumulator.empty,
        callbacks = callbacks
      )
      _ = logger.info(s"Merge completed: ${result.statistics}")

      // Write ambiguity report if needed and capture the path
      ambiguityReportPath = if (result.ambiguities.nonEmpty) {
        logger.warn(s"AMBIGUITIES DETECTED: ${result.ambiguities.size} placement(s) require curator review")
        val path = provenanceService.writeAmbiguityReport(result.ambiguities, result.statistics, sourceName, haplogroupType, now)
        path match {
          case Some(p) => logger.info(s"Ambiguity report written to: $p")
          case None => logger.warn("Failed to write ambiguity report")
        }
        path
      } else None
    } yield TreeMergeResponse(
      success = result.errors.isEmpty,
      message = if (result.errors.isEmpty) {
        if (result.ambiguities.nonEmpty)
          s"Merge completed with ${result.ambiguities.size} ambiguous placement(s) requiring review"
        else
          "Merge completed successfully"
      } else "Merge completed with errors",
      statistics = result.statistics,
      conflicts = result.conflicts,
      splits = result.splits,
      ambiguities = result.ambiguities,
      errors = result.errors,
      ambiguityReportPath = ambiguityReportPath
    )
  }

  // ============================================================================
  // Recursive Merge Implementation
  // ============================================================================

  /**
   * Core recursive merge function implementing the Identify-Match-Graft algorithm.
   *
   * == Formal Role: Recursive Descent with Parallel Tree Traversal ==
   *
   * This method implements Phase 2 (BFS Alignment) and Phase 3 (Conflict Resolution)
   * of the formal algorithm. It processes source tree T₁ recursively while maintaining
   * alignment with existing tree T₀ via the existingNode parameter.
   *
   * == Traverser State ==
   *
   * The method maintains two conceptual traversers (D0/D1 pointers):
   *   - D₁ = sourceNode + sourceCumulativeVariants (position in incoming tree T₁)
   *   - D₀ = existingNode (position in baseline tree T₀, if aligned)
   *
   * == Case Dispatch ==
   *
   * Based on MergeCase classification:
   *   - FULL_MATCH / DESCENDANT: Update existing node, continue to children
   *   - NO_EXISTING_MATCH: Create new node, may trigger grafting
   *   - SOURCE_IS_ANCESTOR: Node contraction (handled via grafting in createNodeWithIndexedLookup)
   *
   * @param sourceNode Current node in T₁ being processed
   * @param sourceCumulativeVariants C(T₁) - cumulative variants from source root to this node
   * @param existingNode D₀ - matched node in T₀ (if any)
   * @param existingTree Full T₀ tree for global lookups
   * @param parentId Database ID of parent in T₀ (for new node creation)
   * @param context Merge configuration (source name, priorities, conflict strategy)
   * @param variantCache Pre-loaded variant name → ID mapping
   * @param accumulator Statistics and results collector
   * @param callbacks Optional callbacks for change set tracking
   */
  private def mergeWithIndexedTree(
    sourceNode: PhyloNodeInput,
    sourceCumulativeVariants: Set[String],
    existingNode: Option[ExistingTreeNode],
    existingTree: Option[ExistingTree],
    parentId: Option[Int],
    context: MergeContext,
    variantCache: VariantCache,
    accumulator: MergeAccumulator,
    callbacks: Option[ChangeSetCallbacks]
  ): Future[MergeAccumulator] = {
    val processed = accumulator.statistics.nodesProcessed
    val sourceNodeVariants = sourceNode.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet

    // Classify merge case using formal algorithm
    val mergeCase = MergeCase.classify(sourceNodeVariants, existingNode)

    if (processed == 0) {
      logger.info(s"mergeWithIndexedTree called for root: ${sourceNode.name}, cumulative variants: ${sourceCumulativeVariants.size}, case=${mergeCase.description}")
    }

    // Dispatch based on case classification, detecting ambiguities
    mergeCase match {
      case MergeCase.FullMatch(_, _) | MergeCase.SourceIsDescendant(_, _) =>
        // Case A/C: Source matches or extends existing - update and continue
        updateExistingNode(sourceNode, sourceCumulativeVariants, existingNode.get, existingTree, parentId, context, variantCache, accumulator, callbacks)

      case MergeCase.SourceIsAncestor(_, _, existing) =>
        // Case B: Source is ancestor - this typically triggers node contraction
        // The existing node will be grafted under the newly created source node
        // This is handled by createNodeWithIndexedLookup's findPhylogeneticMatch
        updateExistingNode(sourceNode, sourceCumulativeVariants, existing, existingTree, parentId, context, variantCache, accumulator, callbacks)

      case MergeCase.DisjointBranch(srcVariants, sharedVariants) =>
        // Case D: Disjoint branch - existing node matched but variants differ
        // This is a PARTIAL MATCH that may indicate data quality issues
        existingNode match {
          case Some(existing) =>
            // Record ambiguity for curator review
            val conflictingVariants = (srcVariants -- sharedVariants) ++ (existing.nodeVariants -- sharedVariants)
            val confidence = if (sharedVariants.nonEmpty) sharedVariants.size.toDouble / math.max(srcVariants.size, existing.nodeVariants.size) else 0.0

            val ambiguity = PlacementAmbiguity(
              nodeName = sourceNode.name,
              ambiguityType = PlacementAmbiguity.PARTIAL_MATCH,
              description = s"Partial variant overlap with ${existing.haplogroup.name}: " +
                s"${sharedVariants.size} shared, ${conflictingVariants.size} conflicting. " +
                s"May indicate sequencing errors, recurrent mutations, or nomenclature mismatch.",
              sharedVariants = sharedVariants.toList,
              conflictingVariants = conflictingVariants.toList,
              candidateMatches = List(existing.haplogroup.name),
              resolution = s"Proceeded with match to ${existing.haplogroup.name} (best available)",
              confidence = confidence
            )

            if (conflictingVariants.nonEmpty) {
              logger.warn(s"AMBIGUITY: Partial match for ${sourceNode.name} -> ${existing.haplogroup.name} " +
                s"(${sharedVariants.size} shared, ${conflictingVariants.size} conflicting, confidence=${f"$confidence%.2f"})")
            }

            val accWithAmbiguity = accumulator.copy(ambiguities = ambiguity :: accumulator.ambiguities)
            updateExistingNode(sourceNode, sourceCumulativeVariants, existing, existingTree, parentId, context, variantCache, accWithAmbiguity, callbacks)

          case None =>
            createNodeWithIndexedLookup(sourceNode, sourceCumulativeVariants, existingTree, parentId, context, variantCache, accumulator, callbacks)
        }

      case MergeCase.NoExistingMatch(_) =>
        // No match in T₀ - create new node
        // This may trigger Phase 4 (Grafting) if descendants exist
        createNodeWithIndexedLookup(sourceNode, sourceCumulativeVariants, existingTree, parentId, context, variantCache, accumulator, callbacks)
    }
  }

  /**
   * Update an existing node and process its children.
   *
   * == Formal Role: Case A/C Handler - Full Match or Source Is Descendant ==
   *
   * This method handles the cases where source node T₁ matches or extends
   * existing node T₀:
   *
   *   - Case A (FULL_MATCH): U(T₁) = U(T₀) - merge metadata, continue
   *   - Case C (SOURCE_IS_DESCENDANT): U(T₁) ⊃ U(T₀) - update and extend
   *
   * For children, we implement parallel tree traversal:
   *   1. First check direct children of T₀ for matches (no reparenting needed)
   *   2. Then search within depth for granularity mismatches (may trigger grafting)
   *   3. If no match, create new node (may trigger Node Contraction)
   *
   * == Adjacency List Update ==
   *
   * When grafting occurs (existing node moved to new position), this performs
   * an Adjacency List update - changing the parent_id in the database.
   */
  private def updateExistingNode(
    sourceNode: PhyloNodeInput,
    sourceCumulativeVariants: Set[String],
    existingNode: ExistingTreeNode,
    existingTree: Option[ExistingTree],
    parentId: Option[Int],
    context: MergeContext,
    variantCache: VariantCache,
    accumulator: MergeAccumulator,
    callbacks: Option[ChangeSetCallbacks]
  ): Future[MergeAccumulator] = {
    val existing = existingNode.haplogroup
    val processed = accumulator.statistics.nodesProcessed
    val nodeVariantCount = existingNode.nodeVariants.size
    val sourceVariantCount = sourceNode.variants.size

    // Log large bottlenecks (30+ SNPs) - more informative than stride logging
    if (nodeVariantCount >= 30 || sourceVariantCount >= 30) {
      logger.info(s"BOTTLENECK MATCH: ${sourceNode.name} -> ${existing.name} (source: $sourceVariantCount SNPs, existing: $nodeVariantCount SNPs)")
    } else if (processed % 500 == 0) {
      logger.info(s"Processing node $processed: ${existing.name}")
    }

    val variantIds = sourceNode.variants.flatMap { vi =>
      allVariantNames(vi).flatMap(name => variantCache.nameToVariantId.get(name.toUpperCase))
    }.distinct

    for {
      existingHaplogroupVariantIds <- haplogroupVariantRepository.getHaplogroupVariantIds(existing.id.get)

      // Add variants - routes to WIP table in staging mode, production otherwise
      newlyAssociatedIds <- stagingHelper.addVariantsStaged(existing.id.get, variantIds, context)

      addedVariantIds = newlyAssociatedIds.diff(existingHaplogroupVariantIds)

      // Update provenance (only in non-staging mode - we don't modify production nodes in staging)
      _ <- if (!context.stagingMode) provenanceService.updateProvenance(existing, sourceNode.variants, context)
           else Future.successful(())

      updatedStats = accumulator.statistics.copy(
        nodesProcessed = accumulator.statistics.nodesProcessed + 1,
        nodesUnchanged = accumulator.statistics.nodesUnchanged + 1,
        variantsAdded = accumulator.statistics.variantsAdded + addedVariantIds.size
      )

      // Process children with grafting/repositioning approach
      // When we find a match deeper in the tree, we REPARENT it to be under current node
      childrenResult <- sourceNode.children.foldLeft(Future.successful(accumulator.copy(statistics = updatedStats))) { (accFuture, child) =>
        accFuture.flatMap { acc =>
          val childNodeVariants = child.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet
          val childCumulativeVariants = sourceCumulativeVariants ++ childNodeVariants

          // First check direct children (no reparenting needed)
          val directMatch = existingNode.children.find { c =>
            c.haplogroup.name.equalsIgnoreCase(child.name) ||
              (childNodeVariants.nonEmpty && c.nodeVariants.intersect(childNodeVariants).nonEmpty)
          }

          directMatch match {
            case Some(matched) =>
              // Direct child match - no reparenting needed
              if (childNodeVariants.size >= 30 || matched.nodeVariants.size >= 30) {
                logger.info(s"BOTTLENECK DIRECT: ${child.name} matched to ${matched.haplogroup.name}")
              }
              mergeWithIndexedTree(child, childCumulativeVariants, Some(matched), existingTree, existing.id, context, variantCache, acc, callbacks)

            case None =>
              // Check deeper - if found, will need reparenting (depth-limited search for granularity mismatch)
              val deepMatch = existingNode.findMatchWithinDepth(child.name, childNodeVariants)

              deepMatch match {
                case Some(matched) =>
                  // ============================================================
                  // DEPTH GRAFT: Found node deeper in tree - reparent to current position
                  // ============================================================
                  // This handles granularity mismatches where T₀ has finer resolution
                  // than T₁ at this point, but T₁ matches a deeper node.
                  logger.info(s"DEPTH_GRAFT: Repositioning ${matched.haplogroup.name} from depth to be under ${existing.name}")

                  // Record as split operation
                  val splitOp = SplitOperation(
                    parentName = existing.name,
                    newIntermediateName = child.name,
                    variantsRedistributed = childNodeVariants.toList,
                    childrenReassigned = List(matched.haplogroup.name),
                    source = context.sourceName
                  )

                  for {
                    // Perform Adjacency List update - routes to WIP table in staging mode
                    _ <- stagingHelper.reparentStaged(matched.haplogroup.id.get, None, existing.id.get, context)

                    // Record REPARENT change via callback (only in non-staging mode)
                    _ = if (!context.stagingMode) {
                      callbacks.foreach(_.recordReparent(matched.haplogroup.id.get, None, existing.id.get))
                    }

                    reparentedStats = acc.statistics.copy(
                      relationshipsUpdated = acc.statistics.relationshipsUpdated + 1,
                      splitOperations = acc.statistics.splitOperations + 1
                    )
                    updatedAcc = acc.copy(
                      statistics = reparentedStats,
                      splits = splitOp :: acc.splits
                    )
                    result <- mergeWithIndexedTree(child, childCumulativeVariants, Some(matched), existingTree, existing.id, context, variantCache, updatedAcc, callbacks)
                  } yield result

                case None =>
                  // No match anywhere - create new node (Case D: DISJOINT_BRANCH)
                  if (childNodeVariants.size >= 30) {
                    logger.info(s"DISJOINT_BRANCH: Creating ${child.name} (${childNodeVariants.size} SNPs) under ${existing.name}")
                  }
                  mergeWithIndexedTree(child, childCumulativeVariants, None, existingTree, existing.id, context, variantCache, acc, callbacks)
              }
          }
        }
      }
    } yield childrenResult
  }

  /**
   * Create a new node and process its children.
   *
   * == Formal Role: Phase 4 - Node Contraction and Grafting ==
   *
   * This method handles Case D (NO_EXISTING_MATCH) and implements the critical
   * Node Contraction operation when T₁ provides finer resolution than T₀.
   *
   * == Node Contraction (Injecting Median Nodes) ==
   *
   * When T₀ has path A→C but T₁ has A→B→C:
   *   1. B is created as a new node under A (this method)
   *   2. findPhylogeneticMatch finds C as a descendant candidate
   *   3. C is reparented under B (Adjacency List update)
   *   4. A SplitOperation is recorded for audit
   *
   * This "contracts" the virtual edge A→C by injecting B as an intermediate node.
   *
   * == Phylogenetic Compatibility Check ==
   *
   * Before grafting, we validate the Set Inclusion Property:
   *   C(ancestral) ⊆ C(candidate)
   *
   * This prevents cross-branch mismatches from recurrent SNPs.
   *
   * == SplitOperation Recording ==
   *
   * When grafting occurs, we record:
   *   - parentName: The parent of the newly created intermediate node
   *   - newIntermediateName: The node being injected (source node)
   *   - childrenReassigned: Nodes reparented under the new intermediate
   *   - variantsRedistributed: Variants now associated with the new split
   */
  private def createNodeWithIndexedLookup(
    sourceNode: PhyloNodeInput,
    sourceCumulativeVariants: Set[String],
    existingTree: Option[ExistingTree],
    parentId: Option[Int],
    context: MergeContext,
    variantCache: VariantCache,
    accumulator: MergeAccumulator,
    callbacks: Option[ChangeSetCallbacks]
  ): Future[MergeAccumulator] = {
    // SAFETY CHECK: Before creating, verify no node with this name already exists
    // This handles cases where variant matching failed but names match (e.g., nomenclature differences)
    val existingByName = existingTree.flatMap(_.findByName(sourceNode.name))
    if (existingByName.isDefined) {
      val existing = existingByName.get
      val sourceNodeVariants = sourceNode.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet

      // Record NAME_VARIANT_MISMATCH ambiguity - only for SIGNIFICANT mismatches
      // Minor variant differences are common and expected in phylogenetics due to:
      // - Different sources using different naming conventions
      // - Intermediate nodes often having few/no variants defined
      // - Terminal nodes accumulating more specific variants
      //
      // We only flag when there's a SIGNIFICANT concern:
      // 1. Both have variants defined AND there's NO overlap (complete mismatch)
      // 2. The confidence drops below 0.2 (less than 20% overlap when both have variants)
      val variantOverlap = sourceNodeVariants.intersect(existing.nodeVariants)
      val variantDifference = (sourceNodeVariants -- existing.nodeVariants) ++ (existing.nodeVariants -- sourceNodeVariants)

      val ambiguity = if (sourceNodeVariants.nonEmpty && existing.nodeVariants.nonEmpty) {
        val confidence = variantOverlap.size.toDouble / math.max(sourceNodeVariants.size, existing.nodeVariants.size)

        // Only flag if there's NO overlap OR very low confidence (< 20%)
        // This filters out normal nomenclature differences while catching true data quality issues
        if (variantOverlap.isEmpty || confidence < 0.2) {
          Some(PlacementAmbiguity(
            nodeName = sourceNode.name,
            ambiguityType = PlacementAmbiguity.NAME_VARIANT_MISMATCH,
            description = s"Name '${sourceNode.name}' matches existing node '${existing.haplogroup.name}' " +
              s"but variants have ${if (variantOverlap.isEmpty) "NO" else "minimal"} overlap. " +
              s"Source: ${sourceNodeVariants.size} variants, existing: ${existing.nodeVariants.size}. " +
              s"May indicate nomenclature collision or data error.",
            sharedVariants = variantOverlap.toList,
            conflictingVariants = variantDifference.toList,
            candidateMatches = List(existing.haplogroup.name),
            resolution = s"Using existing node ${existing.haplogroup.name} (matched by name)",
            confidence = confidence
          ))
        } else None
      } else None // Don't flag when either side has no variants - that's expected

      val accWithAmbiguity = ambiguity match {
        case Some(amb) =>
          logger.warn(s"AMBIGUITY: Name-variant mismatch for ${sourceNode.name} -> ${existing.haplogroup.name} " +
            s"(${variantOverlap.size} shared, ${variantDifference.size} differ)")
          accumulator.copy(ambiguities = amb :: accumulator.ambiguities)
        case None =>
          logger.info(s"Node ${sourceNode.name} already exists (found by name), updating instead of creating")
          accumulator
      }

      return updateExistingNode(sourceNode, sourceCumulativeVariants, existing, existingTree, parentId, context, variantCache, accWithAmbiguity, callbacks)
    }

    // ========================================================================
    // GLOBAL VARIANT MATCH: Look-ahead to prevent duplicate tree creation
    // ========================================================================
    //
    // Before creating a new node, check if ANY existing node matches by variants,
    // regardless of tree position. This prevents the "premature branch creation"
    // bug where intermediate ISOGG nodes (R1b1, R1b1a, etc.) create a parallel
    // tree structure because they don't match existing nodes, causing their
    // descendants (like R1b-L21) to never find their true matches (R1b1a1b1a1a2c1).
    //
    // The phylogenetic check is intentionally RELAXED here because:
    // 1. The source tree may have different intermediate structure than existing tree
    // 2. We want to find the REAL match even if tree topology differs
    // 3. If we find a match, we merge there and let the parent-child relationship
    //    be established by the source tree structure (which may be more detailed)
    //
    // RECURRENT SNP GUARD:
    // To prevent cross-lineage false matches (e.g., R1b-L21 matching I-L21),
    // we require SOME cumulative variant overlap. The source's cumulative variants
    // include its ancestry (R1b's M343, etc.), so a valid match in the same lineage
    // will share those ancestral markers. A different lineage (I) won't.
    val sourceNodeVariants = sourceNode.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet
    val globalVariantMatch = if (sourceNodeVariants.nonEmpty) {
      existingTree.flatMap(_.findGlobalVariantMatch(sourceNodeVariants, sourceCumulativeVariants))
    } else None

    globalVariantMatch match {
      case Some(matchedNode) =>
        val overlap = matchedNode.nodeVariants.intersect(sourceNodeVariants).size
        logger.info(s"GLOBAL_VARIANT_MATCH: ${sourceNode.name} matches existing ${matchedNode.haplogroup.name} " +
          s"by $overlap shared node variants (with lineage verification)")
        return updateExistingNode(sourceNode, sourceCumulativeVariants, matchedNode, existingTree, parentId, context, variantCache, accumulator, callbacks)
      case None =>
        // No global match - proceed with creating new node
    }

    val processed = accumulator.statistics.nodesProcessed
    if (processed % 100 == 0) {
      logger.info(s"Processing node $processed: ${sourceNode.name} (new, ${sourceCumulativeVariants.size} cumulative variants)")
    }

    val variantNames = primaryVariantNames(sourceNode.variants)
    val provenance = HaplogroupProvenance.forNewNode(context.sourceName, variantNames)

    val newHaplogroup = Haplogroup(
      id = None,
      name = sourceNode.name,
      lineage = None,
      description = None,
      haplogroupType = context.haplogroupType,
      revisionId = 1,
      source = context.sourceName,
      confidenceLevel = "medium",
      validFrom = context.timestamp,
      validUntil = None,
      formedYbp = sourceNode.formedYbp,
      formedYbpLower = sourceNode.formedYbpLower,
      formedYbpUpper = sourceNode.formedYbpUpper,
      tmrcaYbp = sourceNode.tmrcaYbp,
      tmrcaYbpLower = sourceNode.tmrcaYbpLower,
      tmrcaYbpUpper = sourceNode.tmrcaYbpUpper,
      ageEstimateSource = Some(context.sourceName),
      provenance = Some(provenance)
    )

    val variantIds = sourceNode.variants.flatMap { vi =>
      allVariantNames(vi).flatMap(name => variantCache.nameToVariantId.get(name.toUpperCase))
    }.distinct

    for {
      // Create haplogroup - routes to WIP table in staging mode
      (newId, _) <- stagingHelper.createHaplogroupStaged(newHaplogroup, parentId, context)

      // Record CREATE change via callback (only in non-staging mode)
      _ = if (!context.stagingMode) {
        callbacks.foreach { cb =>
          val haplogroupJson = Json.obj(
            "name" -> sourceNode.name,
            "haplogroupType" -> context.haplogroupType.toString,
            "source" -> context.sourceName,
            "variants" -> sourceNode.variants.map(_.name)
          ).toString()
          cb.recordCreate(haplogroupJson, parentId)
        }
      }

      // Add variants - routes to WIP table in staging mode
      haplogroupVariantIds <- stagingHelper.addVariantsStaged(newId, variantIds, context)

      // ========================================================================
      // VARIANT DOWNFLOW: Move variants from parent to new intermediate
      // ========================================================================
      //
      // When a higher-resolution source (ISOGG) provides finer tree structure,
      // we may create intermediate nodes (e.g., A00-T between Y and its children).
      // The existing parent (Y) may have variants that actually belong to the
      // new intermediate (A00-T).
      //
      // Example:
      //   - DecodingUs: Y has variants V60, V168 (human-Neanderthal split markers)
      //   - ISOGG: Y → A00-T (defines V60, V168) → ...
      //   - After creating A00-T, we need to MOVE V60, V168 from Y to A00-T
      //
      // This "downflow" ensures variants are associated with their most specific
      // defining haplogroup, not ancestors that happened to have them before
      // finer structure was added.
      //
      // NOTE: In staging mode, we skip variant downflow because we can't modify
      // production variant associations. This will be handled during Apply phase.
      variantsRedistributed <- if (!context.stagingMode && parentId.isDefined && variantIds.nonEmpty && !stagingHelper.isPlaceholder(parentId.get)) {
        for {
          parentVariantIds <- haplogroupVariantRepository.getVariantIdsForHaplogroup(parentId.get)
          variantIdsSet = variantIds.toSet
          overlappingVariants = parentVariantIds.filter(variantIdsSet.contains)
          removed <- if (overlappingVariants.nonEmpty) {
            logger.info(s"VARIANT_DOWNFLOW: Moving ${overlappingVariants.size} variants from parent(id=${parentId.get}) to new node ${sourceNode.name}(id=$newId)")
            haplogroupVariantRepository.bulkRemoveVariantsFromHaplogroup(parentId.get, overlappingVariants)
          } else Future.successful(0)
        } yield removed
      } else Future.successful(0)

      // ========================================================================
      // SUBTREE LOOK-AHEAD: Reparent existing siblings that belong in this subtree
      // ========================================================================
      //
      // When creating a new intermediate node (e.g., A0000 under Y), we need to
      // check if any of the parent's existing children should be moved under
      // this new intermediate. This happens when ISOGG provides finer structure
      // than the existing tree.
      //
      // Strategy: Collect ALL variants from the source subtree (this node and all
      // descendants). If an existing sibling's nodeVariants overlap with any
      // variant in the subtree, that sibling belongs somewhere in this subtree
      // and should be reparented under this new node.
      //
      // Example:
      //   - Existing: Y → A0
      //   - ISOGG: Y → A0000 → A000-T → A000 → ... → A0
      //   - A0's variants match something in the A0000 subtree
      //   - So A0 should be reparented under A0000
      //   - Later, when processing A000-T, A000, etc., A0 may move further down
      // NOTE: In staging mode with placeholder parent, we can't look up existing siblings from
      // the in-memory tree because the parent is a newly created WIP node. The reparenting will
      // be handled during the Apply phase when placeholders are resolved to real IDs.
      subtreeLookAheadReparents <- parentId.flatMap(pid => if (stagingHelper.isPlaceholder(pid)) None else existingTree.flatMap(_.findById(pid))) match {
        case Some(parentNode) =>
          val subtreeVariants = collectAllVariantNames(sourceNode).map(_.toUpperCase).toSet
          val siblingsToReparent = parentNode.children.filter { sibling =>
            // Don't reparent the node we just matched/created
            sibling.haplogroup.id != Some(newId) &&
            // Check if sibling's nodeVariants overlap with ANY variant in the source subtree
            sibling.nodeVariants.intersect(subtreeVariants).nonEmpty
          }

          if (siblingsToReparent.nonEmpty) {
            val siblingNames = siblingsToReparent.map(_.haplogroup.name)
            logger.info(s"SUBTREE_LOOK_AHEAD: Reparenting ${siblingNames.mkString(", ")} under ${sourceNode.name} (subtree has ${subtreeVariants.size} variants)")

            Future.sequence(siblingsToReparent.map { sibling =>
              for {
                // Use staged reparent - routes to WIP table in staging mode
                _ <- stagingHelper.reparentStaged(sibling.haplogroup.id.get, parentId, newId, context)
                // Record REPARENT change via callback (only in non-staging mode)
                _ = if (!context.stagingMode) {
                  callbacks.foreach(_.recordReparent(sibling.haplogroup.id.get, parentId, newId))
                }
              } yield sibling.haplogroup.name
            }).map(_.size)
          } else {
            Future.successful(0)
          }
        case None =>
          Future.successful(0)
      }

      updatedStats = accumulator.statistics.copy(
        nodesProcessed = accumulator.statistics.nodesProcessed + 1,
        nodesCreated = accumulator.statistics.nodesCreated + 1,
        variantsAdded = accumulator.statistics.variantsAdded + haplogroupVariantIds.size,
        relationshipsCreated = if (parentId.isDefined) accumulator.statistics.relationshipsCreated + 1 else accumulator.statistics.relationshipsCreated,
        relationshipsUpdated = accumulator.statistics.relationshipsUpdated + subtreeLookAheadReparents
      )

      // ========================================================================
      // Phase 4: Process Children with Node Contraction / Grafting
      // ========================================================================
      //
      // For each child in T₁, we use phylogenetically-aware search to find
      // existing nodes in T₀ that should be reparented under the newly created node.
      //
      // KEY: Since we just CREATED this node (sourceNode), existing nodes won't have its
      // variants in their cumulative. We need to check against the ANCESTRAL lineage
      // (the path up to but NOT including this new node).
      //
      // This implements the Set Inclusion Property check:
      //   C(ancestral) ⊆ C(candidate)
      currentNodeVariants = sourceNode.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet
      ancestralCumulativeVariants = sourceCumulativeVariants -- currentNodeVariants

      childrenResult <- sourceNode.children.foldLeft(Future.successful(accumulator.copy(statistics = updatedStats))) { (accFuture, child) =>
        accFuture.flatMap { acc =>
          val childNodeVariants = child.variants.flatMap(v => v.name :: v.aliases).map(_.toUpperCase).toSet
          val childCumulativeVariants = sourceCumulativeVariants ++ childNodeVariants

          // ============================================================
          // STAR CLUSTER SIBLING SWEEP
          // ============================================================
          // Find ALL phylogenetically compatible siblings, not just the best match.
          // This handles star clusters where T₀ has multiple siblings that T₁ groups
          // under a new intermediate node.
          //
          // Example: T₀ has A → {B1, B2, B3, C1}
          //          T₁ has A → B → {B1, B2, B3}
          // We need to reparent ALL of B1, B2, B3 under B (not just B1)
          val allMatches = existingTree.map(_.findAllPhylogeneticMatches(ancestralCumulativeVariants, childNodeVariants)).getOrElse(Seq.empty)

          if (allMatches.nonEmpty) {
            // ============================================================
            // NODE CONTRACTION with SIBLING SWEEP
            // ============================================================
            // Sort by overlap to get the "primary" match for recursive merge
            val sortedMatches = allMatches.map { node =>
              val overlap = node.nodeVariants.intersect(childNodeVariants).size
              (node, overlap)
            }.sortBy(-_._2)

            val (primaryMatch, primaryOverlap) = sortedMatches.head
            val siblingMatches = sortedMatches.tail.map(_._1)

            val allMatchNames = allMatches.map(_.haplogroup.name)

            // Record MULTIPLE_CANDIDATES ambiguity when star cluster has competing matches
            // This alerts curators that the algorithm chose one primary match among several options
            val starClusterAmbiguity = if (siblingMatches.nonEmpty) {
              logger.info(s"STAR_CLUSTER_SWEEP: Reparenting ${allMatchNames.mkString(", ")} under ${sourceNode.name} (primary: ${primaryMatch.haplogroup.name}, ${primaryOverlap} overlapping variants)")

              // Calculate confidence: higher when primary has significantly more overlap than others
              val secondBestOverlap = sortedMatches.lift(1).map(_._2).getOrElse(0)
              val confidence = if (primaryOverlap > 0) {
                val overlapDiff = (primaryOverlap - secondBestOverlap).toDouble / primaryOverlap
                0.5 + (overlapDiff * 0.5) // Range: 0.5 (equal) to 1.0 (dominant primary)
              } else 0.3

              Some(PlacementAmbiguity(
                nodeName = child.name,
                ambiguityType = PlacementAmbiguity.MULTIPLE_CANDIDATES,
                description = s"Star cluster resolution: ${allMatches.size} existing nodes match child '${child.name}'. " +
                  s"Primary match '${primaryMatch.haplogroup.name}' has $primaryOverlap overlapping variants. " +
                  s"All ${allMatchNames.mkString(", ")} will be reparented under '${sourceNode.name}'.",
                sharedVariants = primaryMatch.nodeVariants.intersect(childNodeVariants).toList,
                conflictingVariants = List.empty, // No conflict - just multiple valid options
                candidateMatches = allMatchNames.toList,
                resolution = s"Chose ${primaryMatch.haplogroup.name} as primary (highest overlap), reparented all siblings",
                confidence = confidence
              ))
            } else {
              logger.info(s"NODE_CONTRACTION: Reparenting ${primaryMatch.haplogroup.name} under ${sourceNode.name} (${primaryOverlap} overlapping variants)")
              None
            }

            // Record the split operation for audit trail (includes all siblings)
            val splitOp = SplitOperation(
              parentName = sourceNode.name,
              newIntermediateName = sourceNode.name,
              variantsRedistributed = currentNodeVariants.toList,
              childrenReassigned = allMatchNames.toList,
              source = context.sourceName
            )

            for {
              // Perform Adjacency List updates for ALL matching siblings - routes to WIP in staging mode
              _ <- Future.sequence(allMatches.map { node =>
                stagingHelper.reparentStaged(node.haplogroup.id.get, None, newId, context)
              })

              // Record REPARENT changes via callbacks (only in non-staging mode)
              _ = if (!context.stagingMode) {
                callbacks.foreach { cb =>
                  allMatches.foreach { node =>
                    cb.recordReparent(node.haplogroup.id.get, None, newId)
                  }
                }
              }

              reparentedStats = acc.statistics.copy(
                relationshipsUpdated = acc.statistics.relationshipsUpdated + allMatches.size,
                splitOperations = acc.statistics.splitOperations + 1
              )
              // Add ambiguity if present
              accWithAmbiguity = starClusterAmbiguity match {
                case Some(amb) => acc.copy(ambiguities = amb :: acc.ambiguities)
                case None => acc
              }
              updatedAcc = accWithAmbiguity.copy(
                statistics = reparentedStats,
                splits = splitOp :: accWithAmbiguity.splits
              )
              // Continue recursive merge with the primary (best) match only
              // Siblings are reparented but not recursively merged (they keep their subtrees)
              result <- mergeWithIndexedTree(child, childCumulativeVariants, Some(primaryMatch), existingTree, Some(newId), context, variantCache, updatedAcc, callbacks)
            } yield result

          } else {
            // No phylogenetically compatible node found - create new branch (Case D: DISJOINT_BRANCH)
            mergeWithIndexedTree(child, childCumulativeVariants, None, existingTree, Some(newId), context, variantCache, acc, callbacks)
          }
        }
      }
    } yield childrenResult
  }
}
