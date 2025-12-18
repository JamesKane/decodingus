package services.tree

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.*
import models.domain.haplogroups.{Haplogroup, HaplogroupProvenance, MergeContext}
import play.api.Logging
import repositories.HaplogroupCoreRepository

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Using}

/**
 * Service responsible for provenance tracking, age estimate management,
 * and ambiguity report generation during tree merge operations.
 *
 * Handles:
 * - Source attribution and credit assignment (priority-based)
 * - Age estimate merging from multiple sources
 * - Markdown report generation for merge ambiguities
 */
@Singleton
class TreeMergeProvenanceService @Inject()(
  haplogroupRepository: HaplogroupCoreRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Update provenance for an existing haplogroup.
   *
   * Uses priority-based credit assignment:
   * - ISOGG and other protected sources are always preserved
   * - Higher priority sources (lower index) take precedence
   *
   * @param existing The existing haplogroup to update
   * @param newVariants New variants being added (for variant provenance tracking)
   * @param context Merge context with source name and priority config
   * @return Future[Boolean] indicating success
   */
  def updateProvenance(
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
   *
   * Incoming age estimates fill in missing values but don't overwrite existing ones.
   *
   * @param haplogroupId ID of the haplogroup to update
   * @param node Source node with age estimate data
   * @param sourceName Name of the source providing the estimates
   * @return Future[Boolean] indicating success
   */
  def updateAgeEstimates(
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
   *
   * @param source Source name to look up
   * @param config Priority configuration
   * @return Priority index (lower is higher priority)
   */
  def getPriority(source: String, config: SourcePriorityConfig): Int = {
    config.sourcePriorities.indexOf(source) match {
      case -1 => config.defaultPriority
      case idx => idx
    }
  }

  /**
   * Check if node has any age estimates.
   */
  def hasAgeEstimates(node: PhyloNodeInput): Boolean = {
    node.formedYbp.isDefined || node.tmrcaYbp.isDefined
  }

  /**
   * Extract primary variant names from a list of VariantInput.
   */
  private def primaryVariantNames(variants: List[VariantInput]): List[String] =
    variants.map(_.name)

  /**
   * Write a markdown report of merge ambiguities for curator review.
   *
   * Generates a structured report at logs/merge-reports/ containing:
   * - Merge statistics
   * - Ambiguity summary by type
   * - Detailed entries sorted by confidence (lowest first)
   *
   * @param ambiguities List of placement ambiguities detected during merge
   * @param statistics Merge statistics for context
   * @param sourceName Name of the source being merged (e.g., "ISOGG")
   * @param haplogroupType Y or MT
   * @param timestamp When the merge occurred
   * @return Path to the written report, or None if writing failed
   */
  def writeAmbiguityReport(
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
        writer.println("*Report generated by DecodingUs TreeMergeProvenanceService*")
      }.get

      reportFile.getAbsolutePath
    }.toOption
  }
}
