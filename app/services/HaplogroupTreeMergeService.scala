package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.*
import models.domain.genomics.VariantV2
import models.domain.haplogroups.{Haplogroup, HaplogroupProvenance, HaplogroupRelationship, RelationshipRevisionMetadata, HaplogroupVariantMetadata, TreeChangeType}
import play.api.Logging
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, VariantV2Repository, HaplogroupRevisionMetadataRepository, HaplogroupVariantMetadataRepository}

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Using}

/**
 * Service for merging external haplogroup trees into the DecodingUs baseline tree.
 *
 * == Formal Algorithm: "Identify-Match-Graft" Tree Merge ==
 *
 * This implementation is based on formal phylogenetic tree grafting algorithms,
 * specifically a Recursive Descent Tree Merging approach using Set Similarity.
 *
 * === Phase 1: Normalization ===
 * Both trees are converted to a standardized format where each node N is defined by:
 *   - U(N): Unique SNP set - variants defined at this node only (nodeVariants)
 *   - C(N): Cumulative SNP set - all variants from root to N (cumulativeVariants)
 *
 * === Phase 2: Parallel Tree Traversal ===
 * Starting at the root of the incoming tree (T₁), we traverse in parallel with
 * the existing tree (T₀), using SNP set intersection to identify matches.
 *
 * === Phase 3: Four-Way Conflict Resolution ===
 * For each node comparison, we classify into one of four cases:
 *
 *   Case A - FULL_MATCH: U(T₁) = U(T₀)
 *     The nodes are equivalent. Merge metadata and continue to children.
 *
 *   Case B - SOURCE_IS_ANCESTOR: U(T₁) ⊂ U(T₀)
 *     T₁'s node should be ABOVE T₀'s node. This triggers Node Contraction:
 *     insert T₁ as an intermediate ancestor, pushing T₀ down.
 *
 *   Case C - SOURCE_IS_DESCENDANT: U(T₁) ⊃ U(T₀)
 *     T₁'s node should be BELOW T₀'s node. Insert T₁ as a child.
 *
 *   Case D - DISJOINT_BRANCH: U(T₁) ∩ U(T₀) = ∅ or partial overlap
 *     Create a new branch (bifurcation point) at the last common ancestor.
 *
 * === Phase 4: Grafting / Node Contraction ===
 * When T₁ provides finer granularity (e.g., T₀ has A→C, T₁ has A→B→C),
 * we perform Node Contraction: inject B as an intermediate node and
 * reparent C under B (Adjacency List update with Path Enumeration).
 *
 * === Recurrent SNP Handling ===
 * The algorithm guards against back-mutations and recurrent SNPs by validating
 * phylogenetic compatibility: a node can only be grafted if its cumulative
 * variants CONTAIN the ancestral path (Set Inclusion Property).
 *
 * === Key Data Structures ===
 *   - ExistingTree: In-memory tree with O(1) indexes by name and variant
 *   - ExistingTreeNode: Node with U(N), C(N), and children
 *   - VariantCache: Pre-loaded variant name → ID mapping
 *   - MergeCase: Explicit classification of each node comparison
 *
 * === References ===
 *   - Graph Grafting in phylogenetics
 *   - Maximum Agreement Subtree (MAST) algorithms
 *   - Maximum Parsimony for recurrent mutation handling
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
  variantV2Repository: VariantV2Repository,
  haplogroupRevisionMetadataRepository: HaplogroupRevisionMetadataRepository,
  haplogroupVariantMetadataRepository: HaplogroupVariantMetadataRepository,
  treeVersioningService: TreeVersioningService
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
        splits = preview.splits,
        ambiguities = preview.ambiguities
      ))
    } else {
      for {
        // Find the anchor haplogroup
        anchorOpt <- haplogroupRepository.getHaplogroupByName(request.anchorHaplogroupName, request.haplogroupType)
        anchor = anchorOpt.getOrElse(
          throw new IllegalArgumentException(s"Anchor haplogroup '${request.anchorHaplogroupName}' not found")
        )

        // Load context: Get all descendants of the anchor
        descendants <- getDescendantsRecursive(anchor.id.get)
        
        // Build scoped index for the anchor and its descendants
        subtreeScope = anchor +: descendants
        subtreeIndex <- buildVariantIndexForScope(subtreeScope)

        // Check if the source tree root is the anchor itself
        // If the source tree root matches the anchor, we should NOT pass the anchor ID as parent,
        // because that would imply the anchor is a child of itself (reparenting conflict).
        // Instead, passing None tells performMerge/mergeNode to treat it as a root update (no parent change).
        rootMatch = findExistingMatch(request.sourceTree, subtreeIndex)
        rootIsAnchor = rootMatch.exists(_.id == anchor.id)
        effectiveAnchorId = if (rootIsAnchor) None else anchor.id

        result <- performMerge(
          haplogroupType = request.haplogroupType,
          anchorId = effectiveAnchorId,
          sourceTree = request.sourceTree,
          sourceName = request.sourceName,
          priorityConfig = request.priorityConfig.getOrElse(SourcePriorityConfig(List.empty)),
          conflictStrategy = request.conflictStrategy.getOrElse(ConflictStrategy.HigherPriorityWins),
          preloadedIndex = Some(subtreeIndex)
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
      // If we are previewing a subtree, we should ideally scope this too, but for now maintaining global index behavior for preview
      // unless specifically requested to scope preview. 
      // Optimization: For subtree preview, we could also scope, but let's stick to the requested changes for mergeSubtree first.
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
   * Recursively fetch all descendants of a haplogroup.
   */
  private def getDescendantsRecursive(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    haplogroupRepository.getDescendants(haplogroupId)
  }

  /**
   * Build an index of existing haplogroups scoped to a specific list.
   */
  private def buildVariantIndexForScope(haplogroups: Seq[Haplogroup]): Future[VariantIndex] = {
    val haplogroupIds = haplogroups.flatMap(_.id)
    
    // Bulk fetch variants for all haplogroups in the scope
    haplogroupVariantRepository.getVariantsForHaplogroups(haplogroupIds).map { variants =>
      // Group variants by haplogroup ID
      val variantsByHaplogroupId = variants.groupMap(_._1)(_._2)
      
      // Associate haplogroups with their variant names
      val hgsWithVariantNames = haplogroups.map { hg =>
        val associatedVariants = variantsByHaplogroupId.getOrElse(hg.id.get, Seq.empty)
        (hg, associatedVariants.flatMap(_.canonicalName))
      }

      val variantToHaplogroup = hgsWithVariantNames.flatMap { case (hg, variantNames) =>
        variantNames.map(v => v.toUpperCase -> hg)
      }.groupMap(_._1)(_._2)

      val haplogroupByName = hgsWithVariantNames.map { case (hg, _) =>
        hg.name.toUpperCase -> hg
      }.toMap

      VariantIndex(variantToHaplogroup, haplogroupByName)
    }
  }

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
   * Build an in-memory tree structure of existing haplogroups.
   *
   * == Formal Role: Phase 1 - Normalization of T₀ ==
   *
   * This method loads the existing tree and normalizes each node with:
   *   - U(N): nodeVariants - variants defined at this node
   *   - C(N): cumulativeVariants - all variants from root to N
   *
   * == Forest Support ==
   *
   * Y-DNA and mtDNA databases may contain multiple root trees:
   *   - The main tree (Y-Adam or mtEve)
   *   - Floating fragments from research papers
   *   - Orphan nodes not yet connected
   *
   * To handle this, we create a virtual "Super-Adam" root that parents all
   * actual roots. This unifies the forest into a single tree structure while:
   *   - Preserving all indexes for global node lookup
   *   - Allowing matches against nodes in any fragment
   *   - Tracking the "primary root" for merge traversal
   *
   * @param haplogroupType Y or MT DNA type
   * @return A unified tree with all roots as children of a virtual root
   */
  private def buildExistingTree(haplogroupType: HaplogroupType): Future[Option[ExistingTreeNode]] = {
    for {
      // Bulk fetch all haplogroups with their variants
      haplogroupsWithVariants <- haplogroupRepository.getAllWithVariantNames(haplogroupType)
      _ = logger.info(s"Loaded ${haplogroupsWithVariants.size} existing haplogroups")

      // Bulk fetch all relationships
      relationships <- haplogroupRepository.getAllRelationships(haplogroupType)
      _ = logger.info(s"Loaded ${relationships.size} relationships")

      // Build a map of haplogroup ID -> (haplogroup, variant names)
      hgMap = haplogroupsWithVariants.flatMap { case (hg, variants) =>
        hg.id.map(id => id -> (hg, variants.map(_.toUpperCase).toSet))
      }.toMap

      // Build parent -> children map from relationships
      parentToChildren = relationships.groupMap(_._2)(_._1) // parentId -> Seq[childId]

      // Find roots (haplogroups with no parent)
      childIds = relationships.map(_._1).toSet
      rootIds = hgMap.keys.filterNot(childIds.contains).toSeq
      rootNames = rootIds.flatMap(id => hgMap.get(id).map { case (hg, _) => s"${hg.name}(id=$id)" })
      _ = logger.info(s"Found ${rootIds.size} root haplogroup(s): ${rootNames.mkString(", ")}")
    } yield {
      // Recursively build tree nodes with cumulative variants
      def buildNode(hgId: Int, inheritedVariants: Set[String]): Option[ExistingTreeNode] = {
        hgMap.get(hgId).map { case (hg, nodeVariants) =>
          val cumulativeVariants = inheritedVariants ++ nodeVariants
          val nodeChildIds = parentToChildren.getOrElse(hgId, Seq.empty)
          val children = nodeChildIds.flatMap(childId => buildNode(childId, cumulativeVariants))
          ExistingTreeNode(hg, nodeVariants, cumulativeVariants, children)
        }
      }

      // Build ALL root trees (not just the largest)
      val allRootTrees = rootIds.flatMap { rootId =>
        buildNode(rootId, Set.empty).map(tree => (tree, countTreeNodes(tree)))
      }.sortBy(-_._2) // Sort by size descending

      if (allRootTrees.isEmpty) {
        logger.info(s"No existing tree found")
        None
      } else if (allRootTrees.size == 1) {
        // Single root - return directly
        val (primaryRoot, nodeCount) = allRootTrees.head
        logger.info(s"Selected root: ${primaryRoot.haplogroup.name} with $nodeCount nodes")
        Some(primaryRoot)
      } else {
        // Multiple roots - create virtual "Super-Adam" to unify the forest
        // This ensures all nodes from all fragments are indexed for matching
        val (primaryRoot, primaryCount) = allRootTrees.head
        val fragmentCount = allRootTrees.tail.map(_._2).sum
        val fragmentNames = allRootTrees.tail.map(_._1.haplogroup.name)

        logger.info(s"FOREST DETECTED: Primary root ${primaryRoot.haplogroup.name} ($primaryCount nodes), " +
          s"${allRootTrees.size - 1} fragment(s): ${fragmentNames.mkString(", ")} ($fragmentCount total nodes)")

        // Create virtual Super-Adam root with all actual roots as children
        // The virtual root has no variants (empty sets) and a synthetic haplogroup
        val virtualSuperAdam = Haplogroup(
          id = Some(-1), // Synthetic ID (never persisted)
          name = s"__SUPER_ADAM_${haplogroupType}__",
          lineage = None,
          description = Some("Virtual root unifying forest fragments"),
          haplogroupType = haplogroupType,
          revisionId = 0,
          source = "SYSTEM",
          confidenceLevel = "system",
          validFrom = java.time.LocalDateTime.now(),
          validUntil = None,
          formedYbp = None,
          formedYbpLower = None,
          formedYbpUpper = None,
          tmrcaYbp = None,
          tmrcaYbpLower = None,
          tmrcaYbpUpper = None,
          ageEstimateSource = None,
          provenance = None
        )

        val unifiedRoot = ExistingTreeNode(
          haplogroup = virtualSuperAdam,
          nodeVariants = Set.empty,
          cumulativeVariants = Set.empty,
          children = allRootTrees.map(_._1)
        )

        logger.info(s"Created unified forest with ${countTreeNodes(unifiedRoot)} total nodes")
        Some(unifiedRoot)
      }
    }
  }

  /** Count nodes in an ExistingTreeNode tree */
  private def countTreeNodes(node: ExistingTreeNode): Int = {
    1 + node.children.map(countTreeNodes).sum
  }

  /**
   * Perform the actual merge operation using parallel tree traversal.
   *
   * Key insight: We traverse both trees (source and existing) in parallel,
   * only matching source children against existing children of already-matched parents.
   * This prevents cross-branch mismatches that would cause incorrect reparenting.
   */
  private def performMerge(
    haplogroupType: HaplogroupType,
    anchorId: Option[Int],
    sourceTree: PhyloNodeInput,
    sourceName: String,
    priorityConfig: SourcePriorityConfig,
    conflictStrategy: ConflictStrategy,
    preloadedIndex: Option[VariantIndex] = None,
    enableChangeTracking: Boolean = true
  ): Future[TreeMergeResponse] = {
    val now = LocalDateTime.now()
    val nodeCount = countNodes(sourceTree)
    logger.info(s"Starting merge for source '$sourceName' with $nodeCount nodes (change tracking: $enableChangeTracking)")

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

      context = MergeContext(
        haplogroupType = haplogroupType,
        sourceName = sourceName,
        priorityConfig = priorityConfig,
        conflictStrategy = conflictStrategy,
        timestamp = now,
        changeSetId = changeSetId
      )

      // Phase 1a: Build in-memory tree of existing haplogroups with indexes
      existingTreeOpt <- buildExistingTree(haplogroupType).map(_.map(ExistingTree.fromRoot))
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
        accumulator = MergeAccumulator.empty
      )
      _ = logger.info(s"Merge completed: ${result.statistics}")

      // Write ambiguity report if needed and capture the path
      ambiguityReportPath = if (result.ambiguities.nonEmpty) {
        logger.warn(s"AMBIGUITIES DETECTED: ${result.ambiguities.size} placement(s) require curator review")
        val path = writeAmbiguityReport(result.ambiguities, result.statistics, sourceName, haplogroupType, now)
        path match {
          case Some(p) => logger.info(s"Ambiguity report written to: $p")
          case None => logger.warn("Failed to write ambiguity report")
        }
        path
      } else None

      // Finalize change set (if one was created)
      _ <- changeSetId match {
        case Some(csId) =>
          treeVersioningService.finalizeChangeSet(csId, result.statistics, ambiguityReportPath).map { success =>
            if (success) logger.info(s"Change set $csId finalized and ready for review")
            else logger.warn(s"Failed to finalize change set $csId")
          }.recover {
            case e: Exception =>
              logger.error(s"Error finalizing change set $csId: ${e.getMessage}")
          }
        case None =>
          Future.successful(())
      }
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
      errors = result.errors
    )
  }

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
   */
  private def mergeWithIndexedTree(
    sourceNode: PhyloNodeInput,
    sourceCumulativeVariants: Set[String],
    existingNode: Option[ExistingTreeNode],
    existingTree: Option[ExistingTree],
    parentId: Option[Int],
    context: MergeContext,
    variantCache: VariantCache,
    accumulator: MergeAccumulator
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
        updateExistingNode(sourceNode, sourceCumulativeVariants, existingNode.get, existingTree, parentId, context, variantCache, accumulator)

      case MergeCase.SourceIsAncestor(_, _, existing) =>
        // Case B: Source is ancestor - this typically triggers node contraction
        // The existing node will be grafted under the newly created source node
        // This is handled by createNodeWithIndexedLookup's findPhylogeneticMatch
        updateExistingNode(sourceNode, sourceCumulativeVariants, existing, existingTree, parentId, context, variantCache, accumulator)

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
            updateExistingNode(sourceNode, sourceCumulativeVariants, existing, existingTree, parentId, context, variantCache, accWithAmbiguity)

          case None =>
            createNodeWithIndexedLookup(sourceNode, sourceCumulativeVariants, existingTree, parentId, context, variantCache, accumulator)
        }

      case MergeCase.NoExistingMatch(_) =>
        // No match in T₀ - create new node
        // This may trigger Phase 4 (Grafting) if descendants exist
        createNodeWithIndexedLookup(sourceNode, sourceCumulativeVariants, existingTree, parentId, context, variantCache, accumulator)
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
    accumulator: MergeAccumulator
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

      newlyAssociatedIds <- if (variantIds.nonEmpty) {
        haplogroupVariantRepository.bulkAddVariantsToHaplogroups(
          variantIds.map(vid => (existing.id.get, vid))
        )
      } else Future.successful(Seq.empty)

      addedVariantIds = newlyAssociatedIds.diff(existingHaplogroupVariantIds)

      _ <- updateProvenance(existing, sourceNode.variants, context)

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
              mergeWithIndexedTree(child, childCumulativeVariants, Some(matched), existingTree, existing.id, context, variantCache, acc)

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
                    // Perform Adjacency List update - change parent_id
                    _ <- haplogroupRepository.updateParent(matched.haplogroup.id.get, existing.id.get, context.sourceName)

                    // Record REPARENT change for tracking (fire-and-forget)
                    _ = context.changeSetId.foreach { csId =>
                      treeVersioningService.recordReparent(
                        csId, matched.haplogroup.id.get, None, existing.id.get
                      ).recover {
                        case e: Exception => logger.warn(s"Failed to record REPARENT change: ${e.getMessage}")
                      }
                    }

                    reparentedStats = acc.statistics.copy(
                      relationshipsUpdated = acc.statistics.relationshipsUpdated + 1,
                      splitOperations = acc.statistics.splitOperations + 1
                    )
                    updatedAcc = acc.copy(
                      statistics = reparentedStats,
                      splits = splitOp :: acc.splits
                    )
                    result <- mergeWithIndexedTree(child, childCumulativeVariants, Some(matched), existingTree, existing.id, context, variantCache, updatedAcc)
                  } yield result

                case None =>
                  // No match anywhere - create new node (Case D: DISJOINT_BRANCH)
                  if (childNodeVariants.size >= 30) {
                    logger.info(s"DISJOINT_BRANCH: Creating ${child.name} (${childNodeVariants.size} SNPs) under ${existing.name}")
                  }
                  mergeWithIndexedTree(child, childCumulativeVariants, None, existingTree, existing.id, context, variantCache, acc)
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
    accumulator: MergeAccumulator
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

      return updateExistingNode(sourceNode, sourceCumulativeVariants, existing, existingTree, parentId, context, variantCache, accWithAmbiguity)
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
        return updateExistingNode(sourceNode, sourceCumulativeVariants, matchedNode, existingTree, parentId, context, variantCache, accumulator)
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
      (newId, _) <- haplogroupRepository.createWithParent(newHaplogroup, parentId, context.sourceName)

      // Record CREATE change for tracking (fire-and-forget to not slow down merge)
      _ = context.changeSetId.foreach { csId =>
        val haplogroupJson = Json.obj(
          "name" -> sourceNode.name,
          "haplogroupType" -> context.haplogroupType.toString,
          "source" -> context.sourceName,
          "variants" -> sourceNode.variants.map(_.name)
        ).toString()
        treeVersioningService.recordCreate(csId, haplogroupJson, parentId).recover {
          case e: Exception => logger.warn(s"Failed to record CREATE change: ${e.getMessage}")
        }
      }

      haplogroupVariantIds <- if (variantIds.nonEmpty) {
        haplogroupVariantRepository.bulkAddVariantsToHaplogroups(
          variantIds.map(vid => (newId, vid))
        )
      } else Future.successful(Seq.empty)

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
      variantsRedistributed <- if (parentId.isDefined && variantIds.nonEmpty) {
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

      updatedStats = accumulator.statistics.copy(
        nodesProcessed = accumulator.statistics.nodesProcessed + 1,
        nodesCreated = accumulator.statistics.nodesCreated + 1,
        variantsAdded = accumulator.statistics.variantsAdded + haplogroupVariantIds.size,
        relationshipsCreated = if (parentId.isDefined) accumulator.statistics.relationshipsCreated + 1 else accumulator.statistics.relationshipsCreated
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
              // Perform Adjacency List updates for ALL matching siblings
              _ <- Future.sequence(allMatches.map { node =>
                haplogroupRepository.updateParent(node.haplogroup.id.get, newId, context.sourceName)
              })

              // Record REPARENT changes for tracking (fire-and-forget for each node)
              _ = context.changeSetId.foreach { csId =>
                allMatches.foreach { node =>
                  treeVersioningService.recordReparent(
                    csId, node.haplogroup.id.get, None, newId
                  ).recover {
                    case e: Exception => logger.warn(s"Failed to record REPARENT change: ${e.getMessage}")
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
              result <- mergeWithIndexedTree(child, childCumulativeVariants, Some(primaryMatch), existingTree, Some(newId), context, variantCache, updatedAcc)
            } yield result

          } else {
            // No phylogenetically compatible node found - create new branch (Case D: DISJOINT_BRANCH)
            mergeWithIndexedTree(child, childCumulativeVariants, None, existingTree, Some(newId), context, variantCache, acc)
          }
        }
      }
    } yield childrenResult
  }


  /**
   * Find an existing haplogroup that matches the input node.
   *
   * Matching priority:
   * 1. Exact name match (most reliable)
   * 2. Variant-based match with name confirmation (variant match + similar name)
   * 3. Variant-based match with multiple shared variants
   *
   * This avoids false matches where downstream haplogroups inherit ancestral variants.
   */
  private def findExistingMatch(node: PhyloNodeInput, index: VariantIndex): Option[Haplogroup] = {
    // First: try exact name match (most reliable)
    val nameMatch = index.haplogroupByName.get(node.name.toUpperCase)
    if (nameMatch.isDefined) {
      return nameMatch
    }

    // Second: try variant-based matching
    val allNames = allVariantNames(node.variants)
    if (allNames.isEmpty) {
      return None
    }

    val variantMatches = allNames
      .flatMap(v => index.variantToHaplogroup.getOrElse(v.toUpperCase, Seq.empty))
      .groupBy(identity)
      .view.mapValues(_.size)
      .toSeq
      .sortBy(-_._2) // Sort by match count descending

    // Require at least 2 matching variants to avoid false positives from inherited variants
    // OR if there's only 1 variant in the input, require that the matched haplogroup name
    // starts with the same letter as the input node name (basic lineage check)
    variantMatches.headOption.flatMap { case (hg, matchCount) =>
      val inputNodePrefix = node.name.take(1).toUpperCase
      val matchedPrefix = hg.name.take(1).toUpperCase

      if (matchCount >= 2) {
        // Multiple variant matches - likely correct
        Some(hg)
      } else if (matchCount == 1 && inputNodePrefix == matchedPrefix) {
        // Single variant match but same haplogroup lineage (e.g., both start with "R")
        Some(hg)
      } else {
        // Single variant match with different lineage - likely false positive
        logger.debug(s"Rejecting weak variant match: ${node.name} -> ${hg.name} (only $matchCount shared variants, different lineage)")
        None
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

    // Determine primary credit based on priority and preservation rules
    val currentCredit = existingProvenance.primaryCredit
    val newSource = context.sourceName
    
    val primaryCredit = if (HaplogroupProvenance.shouldPreserveCredit(currentCredit)) {
      currentCredit // Always preserve ISOGG (or other protected sources)
    } else {
      // Check priority: lower index = higher priority
      val currentPriority = getPriority(currentCredit, context.priorityConfig)
      val newPriority = getPriority(newSource, context.priorityConfig)

      if (newPriority < currentPriority) {
        newSource // Update to higher priority source
      } else {
        currentCredit // Keep existing
      }
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
   */
  private def analyzeTree(
    node: PhyloNodeInput,
    index: VariantIndex,
    sourceName: String,
    priorityConfig: SourcePriorityConfig
  ): (MergeStatistics, List[MergeConflict], List[SplitOperation], List[PlacementAmbiguity], List[String], List[String], List[String]) = {

    val existingMatch = findExistingMatch(node, index)
    val conflicts = scala.collection.mutable.ListBuffer.empty[MergeConflict]
    val splits = scala.collection.mutable.ListBuffer.empty[SplitOperation]
    val ambiguities = scala.collection.mutable.ListBuffer.empty[PlacementAmbiguity]
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

  /**
   * Write ambiguity report to a file for curator review.
   *
   * Reports are written to logs/merge-reports/ with timestamped filenames.
   * The markdown format includes:
   *   - Summary statistics
   *   - Ambiguities grouped by type
   *   - Full details for each ambiguity
   *
   * @param ambiguities List of placement ambiguities detected during merge
   * @param statistics Merge statistics for context
   * @param sourceName Name of the source being merged (e.g., "ISOGG")
   * @param haplogroupType Y or MT
   * @param timestamp When the merge occurred
   * @return Path to the written report, or None if writing failed
   */
  private def writeAmbiguityReport(
    ambiguities: List[PlacementAmbiguity],
    statistics: MergeStatistics,
    sourceName: String,
    haplogroupType: HaplogroupType,
    timestamp: LocalDateTime
  ): Option[String] = {
    if (ambiguities.isEmpty) return None

    Try {
      // Create reports directory if it doesn't exist
      val reportsDir = new File("logs/merge-reports")
      if (!reportsDir.exists()) {
        reportsDir.mkdirs()
      }

      // Generate filename with timestamp
      val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
      val timestampStr = timestamp.format(formatter)
      val sanitizedSource = sourceName.replaceAll("[^a-zA-Z0-9_-]", "_")
      val filename = s"ambiguity-report_${haplogroupType}_${sanitizedSource}_$timestampStr.md"
      val reportFile = new File(reportsDir, filename)

      // Group ambiguities by type for organized reporting
      val byType = ambiguities.groupBy(_.ambiguityType)

      Using(new PrintWriter(reportFile)) { writer =>
        writer.println(s"# Merge Ambiguity Report")
        writer.println()
        writer.println(s"**Source:** $sourceName")
        writer.println(s"**Haplogroup Type:** $haplogroupType")
        writer.println(s"**Timestamp:** $timestamp")
        writer.println()

        // Summary statistics
        writer.println("## Merge Statistics")
        writer.println()
        writer.println(s"| Metric | Count |")
        writer.println(s"|--------|-------|")
        writer.println(s"| Nodes Processed | ${statistics.nodesProcessed} |")
        writer.println(s"| Nodes Created | ${statistics.nodesCreated} |")
        writer.println(s"| Nodes Updated | ${statistics.nodesUpdated} |")
        writer.println(s"| Nodes Unchanged | ${statistics.nodesUnchanged} |")
        writer.println(s"| Variants Added | ${statistics.variantsAdded} |")
        writer.println(s"| Relationships Created | ${statistics.relationshipsCreated} |")
        writer.println(s"| Relationships Updated | ${statistics.relationshipsUpdated} |")
        writer.println(s"| Split Operations | ${statistics.splitOperations} |")
        writer.println()

        // Ambiguity summary
        writer.println("## Ambiguity Summary")
        writer.println()
        writer.println(s"**Total Ambiguities:** ${ambiguities.size}")
        writer.println()
        writer.println("| Type | Count |")
        writer.println("|------|-------|")
        byType.toSeq.sortBy(-_._2.size).foreach { case (ambType, items) =>
          writer.println(s"| $ambType | ${items.size} |")
        }
        writer.println()

        // Detailed ambiguities by type
        byType.toSeq.sortBy(-_._2.size).foreach { case (ambType, items) =>
          writer.println(s"## $ambType (${items.size})")
          writer.println()

          // Sort by confidence (lowest first - most concerning)
          items.sortBy(_.confidence).foreach { amb =>
            writer.println(s"### ${amb.nodeName}")
            writer.println()
            writer.println(s"**Confidence:** ${f"${amb.confidence}%.2f"}")
            writer.println()
            writer.println(s"**Description:** ${amb.description}")
            writer.println()
            writer.println(s"**Resolution:** ${amb.resolution}")
            writer.println()

            if (amb.candidateMatches.nonEmpty) {
              writer.println(s"**Candidate Matches:** ${amb.candidateMatches.mkString(", ")}")
              writer.println()
            }

            if (amb.sharedVariants.nonEmpty) {
              writer.println(s"**Shared Variants (${amb.sharedVariants.size}):** ${amb.sharedVariants.take(20).mkString(", ")}${if (amb.sharedVariants.size > 20) " ..." else ""}")
              writer.println()
            }

            if (amb.conflictingVariants.nonEmpty) {
              writer.println(s"**Conflicting Variants (${amb.conflictingVariants.size}):** ${amb.conflictingVariants.take(20).mkString(", ")}${if (amb.conflictingVariants.size > 20) " ..." else ""}")
              writer.println()
            }

            writer.println("---")
            writer.println()
          }
        }

        writer.println()
        writer.println("*Report generated by DecodingUs HaplogroupTreeMergeService*")
      }.get

      reportFile.getAbsolutePath
    }.toOption
  }
}

// ============================================================================
// Internal Data Structures
// ============================================================================

// ============================================================================
// Formal Algorithm: Merge Case Classification
// ============================================================================

/**
 * Explicit classification of merge cases based on Set Inclusion relationships.
 *
 * This implements the "Three-Way Logic" from formal tree grafting algorithms,
 * extended to handle the disjoint case common in phylogenetic merges.
 *
 * Given:
 *   - S₁ = source node's variant set (from incoming tree T₁)
 *   - S₀ = existing node's variant set (from baseline tree T₀)
 *
 * We classify the relationship to determine the correct merge action.
 */
private[services] sealed trait MergeCase {
  def description: String
}

private[services] object MergeCase {
  /**
   * Case A: Full Match (S₁ = S₀)
   *
   * The source and existing nodes are equivalent - they define the same
   * phylogenetic branch point.
   *
   * Action: Merge metadata (age estimates, provenance); continue to children.
   */
  case class FullMatch(
    sourceVariants: Set[String],
    existingVariants: Set[String]
  ) extends MergeCase {
    override def description: String = "FULL_MATCH"
  }

  /**
   * Case B: Source Is Ancestor (S₁ ⊂ S₀)
   *
   * The source node represents an ANCESTOR of the existing node.
   * This indicates T₁ has finer resolution - it's splitting what T₀
   * considered a single branch into multiple segments.
   *
   * Action: Node Contraction - insert source as intermediate ancestor,
   * reparent existing node under source. This is "injecting a median node."
   *
   * Example: T₀ has A→C, T₁ has A→B→C
   * When processing B: S₁(B) ⊂ S₀(C), so B is ancestor of C
   * Result: Create B, reparent C under B
   */
  case class SourceIsAncestor(
    sourceVariants: Set[String],
    existingVariants: Set[String],
    existingNode: ExistingTreeNode
  ) extends MergeCase {
    override def description: String = "SOURCE_IS_ANCESTOR"
  }

  /**
   * Case C: Source Is Descendant (S₁ ⊃ S₀)
   *
   * The source node represents a DESCENDANT of the existing node.
   * T₁ is providing additional downstream resolution.
   *
   * Action: Insert source as a new child of existing node.
   */
  case class SourceIsDescendant(
    sourceVariants: Set[String],
    existingVariants: Set[String]
  ) extends MergeCase {
    override def description: String = "SOURCE_IS_DESCENDANT"
  }

  /**
   * Case D: Disjoint Branch (S₁ ∩ S₀ = ∅ or partial overlap without subset)
   *
   * The source and existing nodes represent different lineages that
   * diverged from a common ancestor.
   *
   * Action: Create new branch (bifurcation) at the last common ancestor.
   * This may involve finding the LCA by walking up cumulative variants.
   */
  case class DisjointBranch(
    sourceVariants: Set[String],
    sharedVariants: Set[String]
  ) extends MergeCase {
    override def description: String = "DISJOINT_BRANCH"
  }

  /**
   * Special case: No existing node at this position
   *
   * Action: Create new node. May trigger grafting if descendants exist.
   */
  case class NoExistingMatch(
    sourceVariants: Set[String]
  ) extends MergeCase {
    override def description: String = "NO_EXISTING_MATCH"
  }

  /**
   * Classify the relationship between source and existing variant sets.
   *
   * @param sourceNodeVariants U(T₁) - variants defined at the source node
   * @param existingNodeOpt The matched existing node (if any)
   * @return The classified MergeCase
   */
  def classify(
    sourceNodeVariants: Set[String],
    existingNodeOpt: Option[ExistingTreeNode]
  ): MergeCase = {
    existingNodeOpt match {
      case None =>
        NoExistingMatch(sourceNodeVariants)

      case Some(existing) =>
        val existingNodeVariants = existing.nodeVariants
        val intersection = sourceNodeVariants.intersect(existingNodeVariants)

        if (sourceNodeVariants == existingNodeVariants) {
          // S₁ = S₀: Exact match
          FullMatch(sourceNodeVariants, existingNodeVariants)
        } else if (sourceNodeVariants.subsetOf(existingNodeVariants)) {
          // S₁ ⊂ S₀: Source is ancestor (has fewer, more basal variants)
          SourceIsAncestor(sourceNodeVariants, existingNodeVariants, existing)
        } else if (existingNodeVariants.subsetOf(sourceNodeVariants)) {
          // S₁ ⊃ S₀: Source is descendant (has more, derived variants)
          SourceIsDescendant(sourceNodeVariants, existingNodeVariants)
        } else {
          // Disjoint or partial overlap
          DisjointBranch(sourceNodeVariants, intersection)
        }
    }
  }
}

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
  timestamp: LocalDateTime,
  changeSetId: Option[Int] = None // For change tracking integration
)

/**
 * Accumulator for merge statistics and results.
 *
 * Tracks all outcomes during the merge including ambiguous placements
 * that require human curator review.
 */
private[services] case class MergeAccumulator(
  statistics: MergeStatistics,
  conflicts: List[MergeConflict],
  splits: List[SplitOperation],
  ambiguities: List[PlacementAmbiguity],
  errors: List[String]
)

private[services] object MergeAccumulator {
  val empty: MergeAccumulator = MergeAccumulator(
    statistics = MergeStatistics.empty,
    conflicts = List.empty,
    splits = List.empty,
    ambiguities = List.empty,
    errors = List.empty
  )
}

/**
 * Pre-loaded variant lookup cache.
 * Maps variant name (uppercase) -> variant ID.
 * Built once at the start of merge to avoid N+1 queries.
 */
private[services] case class VariantCache(
  nameToVariantId: Map[String, Int]
)

/**
 * In-memory representation of the existing tree T₀ with O(1) lookup indexes.
 *
 * == Formal Role: Baseline Tree (T₀) ==
 *
 * This structure represents the existing/baseline phylogenetic tree that
 * the incoming tree T₁ will be merged into. It provides:
 *
 *   - byName: Index for conflict detection (O(1) name lookup)
 *   - byVariant: Index for Set Intersection matching (O(1) variant lookup)
 *
 * Matching is based purely on mutation signatures (Set Inclusion Property),
 * not names, to handle different naming conventions across sources.
 *
 * == Set Inclusion Property ==
 *
 * If C(N₁) ⊂ C(N₂), then N₁ is an ancestor of N₂ in the phylogenetic tree.
 * This is the fundamental property that enables correct tree grafting.
 */
private[services] case class ExistingTree(
  root: ExistingTreeNode,
  byName: Map[String, ExistingTreeNode],         // name (uppercase) -> node (for conflict detection only)
  byVariant: Map[String, Seq[ExistingTreeNode]]  // variant name (uppercase) -> nodes with that variant in cumulative set
) {
  /** Find node by name - O(1) - used only for conflict detection */
  def findByName(name: String): Option[ExistingTreeNode] =
    byName.get(name.toUpperCase)

  /**
   * Find best matching node for source cumulative variants.
   *
   * == Formal Role: BFS Alignment Step ==
   *
   * This implements the SNP set intersection search from the Identify-Match-Graft
   * algorithm. Given C(source), find the node in T₀ with maximum overlap.
   *
   * @param sourceCumulativeVariants C(T₁) - cumulative variants of source node
   * @return The node in T₀ with highest variant overlap, or None
   */
  def findMatchByVariants(sourceCumulativeVariants: Set[String]): Option[ExistingTreeNode] = {
    if (sourceCumulativeVariants.isEmpty) return None

    // Find all nodes that share cumulative variants, count overlaps
    val candidates = sourceCumulativeVariants
      .flatMap(v => byVariant.getOrElse(v, Seq.empty))
      .groupBy(identity)
      .view.mapValues(_.size)
      .toSeq
      .sortBy(-_._2)

    // Return node with highest overlap
    candidates.headOption.filter(_._2 >= 1).map(_._1)
  }

  /**
   * Find a phylogenetically compatible node for grafting.
   *
   * == Formal Role: Node Contraction / Grafting Validation ==
   *
   * This method validates the Set Inclusion Property before grafting:
   *   C(parent) ⊆ C(candidate) - the candidate must be downstream in our lineage
   *
   * This prevents the critical error of grafting nodes from unrelated lineages
   * (e.g., grafting A-branch nodes under B-branch) which can occur when
   * recurrent SNPs (back-mutations) create false positive matches.
   *
   * == Recurrent SNP Guard ==
   *
   * Recurrent SNPs are the same mutation occurring independently in different
   * lineages. Without phylogenetic validation, these could cause:
   *   - A1b1-M9431 being grafted under B2b1a2 (wrong!)
   *
   * The Set Inclusion check ensures we only graft nodes that share our
   * ancestral path, not just a single variant.
   *
   * @param parentCumulativeVariants C(parent) - the ancestral lineage signature
   * @param childNodeVariants U(child) - variants that define this specific branch
   * @return The best matching node that is phylogenetically compatible, or None
   */
  def findPhylogeneticMatch(
    parentCumulativeVariants: Set[String],
    childNodeVariants: Set[String]
  ): Option[ExistingTreeNode] = {
    if (childNodeVariants.isEmpty) return None

    // Step 1: Find candidate nodes that have at least one of the child's defining variants
    val candidatesWithVariant = childNodeVariants
      .flatMap(v => byVariant.getOrElse(v, Seq.empty))
      .toSeq.distinct

    // Step 2: Apply Set Inclusion Property - filter to same lineage only
    // Their cumulative variants must CONTAIN our parent's cumulative variants
    // This is the key recurrent SNP guard
    val phylogeneticallyCompatible = candidatesWithVariant.filter { node =>
      parentCumulativeVariants.subsetOf(node.cumulativeVariants)
    }

    if (phylogeneticallyCompatible.isEmpty) return None

    // Step 3: Score by overlap with child's node-specific variants
    val scored = phylogeneticallyCompatible.map { node =>
      val overlap = node.nodeVariants.intersect(childNodeVariants).size
      (node, overlap)
    }.filter(_._2 > 0).sortBy(-_._2)

    scored.headOption.map(_._1)
  }

  /**
   * Find ALL phylogenetically compatible nodes for star cluster sibling sweeping.
   *
   * == Formal Role: Star Cluster Resolution ==
   *
   * In phylogenetics, a "star cluster" is a polytomy where multiple lineages
   * diverge simultaneously from a single ancestor. When T₁ provides finer
   * resolution (grouping some of these siblings under a new intermediate node),
   * we need to identify ALL siblings that should be reparented together.
   *
   * This method returns all nodes that:
   *   1. Are phylogenetically compatible (C(parent) ⊆ C(node))
   *   2. Share at least one variant with the child signature
   *
   * == Example ==
   *
   * T₀ has star cluster: A → {B1, B2, B3, C1, C2}
   * T₁ groups B-variants: A → B → {B1, B2, B3}
   *
   * When processing B's children, this method returns [B1, B2, B3] so all
   * can be reparented under B simultaneously.
   *
   * @param parentCumulativeVariants C(parent) - the ancestral lineage signature
   * @param childNodeVariants U(child) - variants that define this branch
   * @return All matching nodes that should be reparented together
   */
  def findAllPhylogeneticMatches(
    parentCumulativeVariants: Set[String],
    childNodeVariants: Set[String]
  ): Seq[ExistingTreeNode] = {
    if (childNodeVariants.isEmpty) return Seq.empty

    // Step 1: Find candidate nodes that have at least one of the child's defining variants
    val candidatesWithVariant = childNodeVariants
      .flatMap(v => byVariant.getOrElse(v, Seq.empty))
      .toSeq.distinct

    // Step 2: Apply Set Inclusion Property - filter to same lineage only
    val phylogeneticallyCompatible = candidatesWithVariant.filter { node =>
      parentCumulativeVariants.subsetOf(node.cumulativeVariants)
    }

    // Step 3: Return all nodes with overlapping node-specific variants
    phylogeneticallyCompatible.filter { node =>
      node.nodeVariants.intersect(childNodeVariants).nonEmpty
    }
  }

  /**
   * Find a matching node by variant overlap with RELAXED phylogenetic check.
   *
   * == Formal Role: Global Variant Match for Cross-Tree Merging ==
   *
   * This method is used when merging trees with DIFFERENT intermediate structures.
   * For example:
   *   - ISOGG tree: R1b → R1b1 → R1b1a → ... → R1b-L21 (defined by L21)
   *   - DecodingUs tree: R1b → [different structure] → R1b1a1b1a1a2c1 (has L21)
   *
   * When ISOGG's intermediate nodes (R1b1, R1b1a) don't match existing structure,
   * we still want R1b-L21 to find R1b1a1b1a1a2c1 by their shared L21 variant.
   *
   * == Recurrent SNP Guard ==
   *
   * To prevent cross-lineage false matches (R1b-L21 matching I-L21), we require
   * SOME cumulative variant overlap between the source's ancestry and the
   * candidate's ancestry. This ensures they're in the same major lineage.
   *
   * For example:
   *   - R1b-L21's cumulative includes M343 (R1b's marker)
   *   - R1b1a1b1a1a2c1's cumulative also includes M343 → MATCH OK
   *   - I-L21's cumulative would NOT include M343 → REJECTED
   *
   * @param sourceNodeVariants U(source) - variants defined at the source node
   * @param sourceCumulativeVariants C(source) - all variants from source's ancestry
   * @return The best matching node by variant overlap in same lineage, or None
   */
  def findGlobalVariantMatch(
    sourceNodeVariants: Set[String],
    sourceCumulativeVariants: Set[String]
  ): Option[ExistingTreeNode] = {
    if (sourceNodeVariants.isEmpty) return None

    // Step 1: Find all candidate nodes that share any variant
    val candidatesWithVariant = sourceNodeVariants
      .flatMap(v => byVariant.getOrElse(v, Seq.empty))
      .toSeq.distinct

    if (candidatesWithVariant.isEmpty) return None

    // Step 2: LINEAGE GUARD - require cumulative overlap to prevent cross-lineage matches
    // This catches recurrent SNPs like L21 appearing in both R1b and I lineages
    val sameLineageCandidates = candidatesWithVariant.filter { node =>
      // Require at least 1 shared cumulative variant (ancestral marker)
      // This ensures the candidate is in the same major lineage as the source
      val cumulativeOverlap = node.cumulativeVariants.intersect(sourceCumulativeVariants)
      cumulativeOverlap.nonEmpty
    }

    if (sameLineageCandidates.isEmpty) return None

    // Step 3: Score candidates by NODE-LEVEL variant overlap (not cumulative)
    // This finds the node where these variants are actually DEFINED, not just inherited
    val scored = sameLineageCandidates.map { node =>
      val overlap = node.nodeVariants.intersect(sourceNodeVariants).size
      (node, overlap)
    }.filter(_._2 > 0).sortBy(-_._2)

    // Step 4: Safety checks to avoid false matches
    scored.headOption.flatMap { case (node, overlap) =>
      val overlapRatio = overlap.toDouble / sourceNodeVariants.size

      // Check 1: Require significant overlap from source's perspective
      // Either ≥2 shared variants OR ≥50% of source variants
      val hasSignificantOverlap = overlap >= 2 || overlapRatio >= 0.5

      // Check 2: DESCENDANT GUARD - prevent matching to a much deeper node
      // If the existing node has MANY more variants than the source, it's likely
      // a descendant, not a true match. The source should create intermediates
      // leading TO that node, not collapse into it.
      //
      // Example: Source G2a2b2a1a1a1a1a1a1 has 2 variants, G-Y38189 has 78
      // This is NOT a match - ISOGG is providing intermediate structure that
      // should be preserved, leading down to G-Y38189.
      //
      // We allow up to 10x difference (e.g., source=5, existing=50 is OK)
      // but reject extreme cases (source=2, existing=78)
      val nodeVariantRatio = if (sourceNodeVariants.nonEmpty) {
        node.nodeVariants.size.toDouble / sourceNodeVariants.size
      } else Double.MaxValue

      val isNotDescendantMismatch = nodeVariantRatio <= 10.0

      if (hasSignificantOverlap && isNotDescendantMismatch) {
        Some(node)
      } else {
        None // Either weak match or descendant mismatch - let normal flow handle it
      }
    }
  }
}

private[services] object ExistingTree {
  /** Build tree with indexes from a root node */
  def fromRoot(root: ExistingTreeNode): ExistingTree = {
    val byName = scala.collection.mutable.Map.empty[String, ExistingTreeNode]
    val byVariant = scala.collection.mutable.Map.empty[String, List[ExistingTreeNode]]

    def index(node: ExistingTreeNode): Unit = {
      byName(node.haplogroup.name.toUpperCase) = node
      // Index by CUMULATIVE variants (the full mutation signature)
      node.cumulativeVariants.foreach { v =>
        byVariant(v) = node :: byVariant.getOrElse(v, Nil)
      }
      node.children.foreach(index)
    }

    index(root)
    ExistingTree(root, byName.toMap, byVariant.toMap)
  }
}

/**
 * In-memory tree node for existing haplogroups.
 *
 * == Formal Role: Normalized Node with U(N) and C(N) ==
 *
 * Each node in the phylogenetic tree is defined by two variant sets:
 *
 *   - U(N) = nodeVariants: Unique SNP set - variants defined AT this node only
 *   - C(N) = cumulativeVariants: Cumulative SNP set - all variants from root to N
 *
 * The relationship: C(N) = C(parent) ∪ U(N)
 *
 * This normalization enables the Set Inclusion Property for tree comparison:
 *   If C(N₁) ⊂ C(N₂), then N₁ is an ancestor of N₂
 *
 * == Path Enumeration ==
 *
 * The cumulativeVariants field implements Path Enumeration - the complete
 * mutational signature from Y-Adam (root) to this node. This signature
 * uniquely identifies the node's position in the phylogeny regardless of
 * naming conventions.
 *
 * @param haplogroup The haplogroup data (name, metadata, provenance)
 * @param nodeVariants U(N) - Variants defined at THIS node (not inherited)
 * @param cumulativeVariants C(N) - All variants from root to this node (inherited + own)
 * @param children Child nodes in the tree
 */
private[services] case class ExistingTreeNode(
  haplogroup: Haplogroup,
  nodeVariants: Set[String],
  cumulativeVariants: Set[String],
  children: Seq[ExistingTreeNode]
) {
  /**
   * Find a matching node within a bounded depth of this node.
   *
   * == Formal Role: Depth-Limited BFS for Granularity Mismatch ==
   *
   * Different sources may split branches at different points (star clusters).
   * This method handles cases where T₁ and T₀ have different resolution
   * by searching within a depth limit.
   *
   * @param sourceName Name of the source node
   * @param sourceNodeVariants U(T₁) - Variants defined at the source node (not cumulative)
   * @param maxDepth Maximum levels to search (default 5 for star cluster variations)
   * @return The best matching node within depth limit, or None
   */
  def findMatchWithinDepth(sourceName: String, sourceNodeVariants: Set[String], maxDepth: Int = 5): Option[ExistingTreeNode] = {
    if (maxDepth <= 0) return None

    // First: exact name match at this level (highest priority)
    val nameMatch = children.find(_.haplogroup.name.equalsIgnoreCase(sourceName))
    if (nameMatch.isDefined) return nameMatch

    // Second: variant overlap match at this level
    if (sourceNodeVariants.nonEmpty) {
      val variantMatches = children.map { child =>
        val overlap = child.nodeVariants.intersect(sourceNodeVariants).size
        (child, overlap)
      }.filter(_._2 > 0).sortBy(-_._2)

      if (variantMatches.nonEmpty) {
        return Some(variantMatches.head._1)
      }
    }

    // Third: recurse into children with reduced depth
    children.iterator.flatMap(_.findMatchWithinDepth(sourceName, sourceNodeVariants, maxDepth - 1)).nextOption()
  }
}
