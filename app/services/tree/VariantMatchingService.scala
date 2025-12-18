package services.tree

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.{PhyloNodeInput, VariantInput}
import models.domain.haplogroups.{ExistingTreeNode, Haplogroup, VariantIndex}
import play.api.Logging
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for tree building, indexing, and phylogenetic matching
 * during tree merge operations.
 *
 * Handles:
 * - Building ExistingTree structures with cumulative variant normalization
 * - Creating variant-to-haplogroup indexes for O(1) lookups
 * - Finding existing matches by name or variant overlap
 * - Forest handling (multiple roots unified under virtual Super-Adam)
 */
@Singleton
class VariantMatchingService @Inject()(
  haplogroupRepository: HaplogroupCoreRepository,
  haplogroupVariantRepository: HaplogroupVariantRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Build an index of existing haplogroups by their variant names.
   * This enables variant-based matching across different naming conventions.
   *
   * @param haplogroupType Y or MT haplogroup type
   * @return VariantIndex with maps for variant-to-haplogroup and name-to-haplogroup lookups
   */
  def buildVariantIndex(haplogroupType: HaplogroupType): Future[VariantIndex] = {
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
   * Build a scoped variant index for a subset of haplogroups.
   * Used for subtree merge operations.
   *
   * @param haplogroups The haplogroups to include in the index
   * @return VariantIndex scoped to the provided haplogroups
   */
  def buildVariantIndexForScope(haplogroups: Seq[Haplogroup]): Future[VariantIndex] = {
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
   * actual roots, enabling unified indexing and matching across all fragments.
   *
   * @param haplogroupType Y or MT haplogroup type
   * @return Optional root node of the existing tree (may be virtual Super-Adam if forest)
   */
  def buildExistingTree(haplogroupType: HaplogroupType): Future[Option[ExistingTreeNode]] = {
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

  /**
   * Find an existing haplogroup that matches the given node.
   *
   * Matching priority:
   * 1. Exact name match (most reliable)
   * 2. Variant-based match with name confirmation (variant match + similar name)
   * 3. Variant-based match with multiple shared variants
   *
   * This avoids false matches where downstream haplogroups inherit ancestral variants.
   *
   * @param node The source node to match
   * @param index The variant index to search
   * @return Optional matching haplogroup
   */
  def findExistingMatch(node: PhyloNodeInput, index: VariantIndex): Option[Haplogroup] = {
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
   * Count nodes in an ExistingTreeNode tree.
   */
  def countTreeNodes(node: ExistingTreeNode): Int = {
    1 + node.children.map(countTreeNodes).sum
  }

  /**
   * Extract all variant names (primary + aliases) from a VariantInput.
   */
  private def allVariantNames(variant: VariantInput): List[String] =
    variant.name :: variant.aliases

  /**
   * Extract all variant names from a list of VariantInput.
   */
  private def allVariantNames(variants: List[VariantInput]): List[String] =
    variants.flatMap(allVariantNames)
}
