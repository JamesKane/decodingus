package models.domain.haplogroups

import models.HaplogroupType
import models.api.haplogroups.{ConflictStrategy, MergeConflict, MergeStatistics, PlacementAmbiguity, SourcePriorityConfig, SplitOperation}

import java.time.LocalDateTime

// ============================================================================
// Tree Merge Data Structures
// ============================================================================
//
// These structures support the "Identify-Match-Graft" tree merge algorithm
// used for merging external haplogroup trees into the DecodingUs baseline.
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
sealed trait MergeCase {
  def description: String
}

object MergeCase {
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
case class VariantIndex(
  variantToHaplogroup: Map[String, Seq[Haplogroup]],
  haplogroupByName: Map[String, Haplogroup]
)

/**
 * Context for merge operations.
 */
case class MergeContext(
  haplogroupType: HaplogroupType,
  sourceName: String,
  priorityConfig: SourcePriorityConfig,
  conflictStrategy: ConflictStrategy,
  timestamp: LocalDateTime,
  changeSetId: Option[Int] = None, // For change tracking integration
  stagingMode: Boolean = true // When true, only record changes, don't apply to production
)

/**
 * Accumulator for merge statistics and results.
 *
 * Tracks all outcomes during the merge including ambiguous placements
 * that require human curator review.
 */
case class MergeAccumulator(
  statistics: MergeStatistics,
  conflicts: List[MergeConflict],
  splits: List[SplitOperation],
  ambiguities: List[PlacementAmbiguity],
  errors: List[String]
)

object MergeAccumulator {
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
case class VariantCache(
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
case class ExistingTree(
  root: ExistingTreeNode,
  byName: Map[String, ExistingTreeNode],         // name (uppercase) -> node (for conflict detection only)
  byVariant: Map[String, Seq[ExistingTreeNode]], // variant name (uppercase) -> nodes with that variant in cumulative set
  byId: Map[Int, ExistingTreeNode] = Map.empty   // haplogroup ID -> node (for parent lookup)
) {
  /** Find node by name - O(1) - used only for conflict detection */
  def findByName(name: String): Option[ExistingTreeNode] =
    byName.get(name.toUpperCase)

  /** Find node by haplogroup ID - O(1) */
  def findById(id: Int): Option[ExistingTreeNode] =
    byId.get(id)

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

object ExistingTree {
  /** Build tree with indexes from a root node */
  def fromRoot(root: ExistingTreeNode): ExistingTree = {
    val byName = scala.collection.mutable.Map.empty[String, ExistingTreeNode]
    val byVariant = scala.collection.mutable.Map.empty[String, List[ExistingTreeNode]]
    val byId = scala.collection.mutable.Map.empty[Int, ExistingTreeNode]

    def index(node: ExistingTreeNode): Unit = {
      byName(node.haplogroup.name.toUpperCase) = node
      node.haplogroup.id.foreach(id => byId(id) = node)
      // Index by CUMULATIVE variants (the full mutation signature)
      node.cumulativeVariants.foreach { v =>
        byVariant(v) = node :: byVariant.getOrElse(v, Nil)
      }
      node.children.foreach(index)
    }

    index(root)
    ExistingTree(root, byName.toMap, byVariant.toMap, byId.toMap)
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
case class ExistingTreeNode(
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
