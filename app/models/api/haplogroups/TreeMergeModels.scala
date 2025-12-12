package models.api.haplogroups

import models.HaplogroupType
import play.api.libs.json.{Format, Json, OFormat, Reads, Writes}

/**
 * API DTOs for Haplogroup Tree Merge operations.
 *
 * Supports merging external haplogroup trees from sources like ISOGG, ytree.net,
 * and other researchers into the DecodingUs baseline tree.
 */

// ============================================================================
// Input Tree Structure
// ============================================================================

/**
 * A node in the input phylogenetic tree for merging.
 * Matching is done by variants, not names, to handle different naming conventions.
 */
case class PhyloNodeInput(
  name: String,
  variants: List[String] = List.empty,
  formedYbp: Option[Int] = None,
  formedYbpLower: Option[Int] = None,
  formedYbpUpper: Option[Int] = None,
  tmrcaYbp: Option[Int] = None,
  tmrcaYbpLower: Option[Int] = None,
  tmrcaYbpUpper: Option[Int] = None,
  children: List[PhyloNodeInput] = List.empty
)

object PhyloNodeInput {
  implicit val format: OFormat[PhyloNodeInput] = Json.format[PhyloNodeInput]
}

// ============================================================================
// Merge Configuration
// ============================================================================

/**
 * Configuration for source priority during merge.
 * Lower index = higher priority.
 */
case class SourcePriorityConfig(
  sourcePriorities: List[String],
  defaultPriority: Int = 100
)

object SourcePriorityConfig {
  implicit val format: OFormat[SourcePriorityConfig] = Json.format[SourcePriorityConfig]
}

/**
 * Strategy for handling conflicts during merge.
 */
sealed trait ConflictStrategy

object ConflictStrategy {
  case object HigherPriorityWins extends ConflictStrategy
  case object KeepExisting extends ConflictStrategy
  case object AlwaysUpdate extends ConflictStrategy

  implicit val reads: Reads[ConflictStrategy] = Reads.StringReads.map {
    case "higher_priority_wins" => HigherPriorityWins
    case "keep_existing" => KeepExisting
    case "always_update" => AlwaysUpdate
    case other => throw new IllegalArgumentException(s"Unknown conflict strategy: $other")
  }

  implicit val writes: Writes[ConflictStrategy] = Writes.StringWrites.contramap {
    case HigherPriorityWins => "higher_priority_wins"
    case KeepExisting => "keep_existing"
    case AlwaysUpdate => "always_update"
  }

  implicit val format: Format[ConflictStrategy] = Format(reads, writes)
}

// ============================================================================
// Request DTOs
// ============================================================================

/**
 * Request for full tree merge (replace entire Y-DNA or mtDNA tree).
 */
case class TreeMergeRequest(
  haplogroupType: HaplogroupType,
  sourceTree: PhyloNodeInput,
  sourceName: String,
  priorityConfig: Option[SourcePriorityConfig] = None,
  conflictStrategy: Option[ConflictStrategy] = None,
  dryRun: Boolean = false
)

object TreeMergeRequest {
  implicit val format: OFormat[TreeMergeRequest] = Json.format[TreeMergeRequest]
}

/**
 * Request for subtree merge (merge under a specific anchor node).
 */
case class SubtreeMergeRequest(
  haplogroupType: HaplogroupType,
  anchorHaplogroupName: String,
  sourceTree: PhyloNodeInput,
  sourceName: String,
  priorityConfig: Option[SourcePriorityConfig] = None,
  conflictStrategy: Option[ConflictStrategy] = None,
  dryRun: Boolean = false
)

object SubtreeMergeRequest {
  implicit val format: OFormat[SubtreeMergeRequest] = Json.format[SubtreeMergeRequest]
}

/**
 * Request for merge preview.
 */
case class MergePreviewRequest(
  haplogroupType: HaplogroupType,
  anchorHaplogroupName: Option[String] = None,
  sourceTree: PhyloNodeInput,
  sourceName: String,
  priorityConfig: Option[SourcePriorityConfig] = None
)

object MergePreviewRequest {
  implicit val format: OFormat[MergePreviewRequest] = Json.format[MergePreviewRequest]
}

// ============================================================================
// Response DTOs
// ============================================================================

/**
 * Statistics from a merge operation.
 */
case class MergeStatistics(
  nodesProcessed: Int,
  nodesCreated: Int,
  nodesUpdated: Int,
  nodesUnchanged: Int,
  variantsAdded: Int,
  variantsUpdated: Int,
  relationshipsCreated: Int,
  relationshipsUpdated: Int,
  splitOperations: Int = 0
)

object MergeStatistics {
  implicit val format: OFormat[MergeStatistics] = Json.format[MergeStatistics]

  val empty: MergeStatistics = MergeStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0)

  def combine(a: MergeStatistics, b: MergeStatistics): MergeStatistics = MergeStatistics(
    nodesProcessed = a.nodesProcessed + b.nodesProcessed,
    nodesCreated = a.nodesCreated + b.nodesCreated,
    nodesUpdated = a.nodesUpdated + b.nodesUpdated,
    nodesUnchanged = a.nodesUnchanged + b.nodesUnchanged,
    variantsAdded = a.variantsAdded + b.variantsAdded,
    variantsUpdated = a.variantsUpdated + b.variantsUpdated,
    relationshipsCreated = a.relationshipsCreated + b.relationshipsCreated,
    relationshipsUpdated = a.relationshipsUpdated + b.relationshipsUpdated,
    splitOperations = a.splitOperations + b.splitOperations
  )
}

/**
 * Details of a conflict encountered during merge.
 */
case class MergeConflict(
  haplogroupName: String,
  field: String,
  existingValue: String,
  newValue: String,
  resolution: String,
  existingSource: String,
  newSource: String
)

object MergeConflict {
  implicit val format: OFormat[MergeConflict] = Json.format[MergeConflict]
}

/**
 * Details of a split operation performed during merge.
 */
case class SplitOperation(
  parentName: String,
  newIntermediateName: String,
  variantsRedistributed: List[String],
  childrenReassigned: List[String],
  source: String
)

object SplitOperation {
  implicit val format: OFormat[SplitOperation] = Json.format[SplitOperation]
}

/**
 * Result of a merge operation.
 */
case class TreeMergeResponse(
  success: Boolean,
  message: String,
  statistics: MergeStatistics,
  conflicts: List[MergeConflict] = List.empty,
  splits: List[SplitOperation] = List.empty,
  errors: List[String] = List.empty
)

object TreeMergeResponse {
  implicit val format: OFormat[TreeMergeResponse] = Json.format[TreeMergeResponse]

  def failure(message: String, errors: List[String] = List.empty): TreeMergeResponse =
    TreeMergeResponse(
      success = false,
      message = message,
      statistics = MergeStatistics.empty,
      errors = errors
    )
}

/**
 * Preview of merge results (without applying changes).
 */
case class MergePreviewResponse(
  statistics: MergeStatistics,
  conflicts: List[MergeConflict],
  splits: List[SplitOperation],
  newNodes: List[String],
  updatedNodes: List[String],
  unchangedNodes: List[String]
)

object MergePreviewResponse {
  implicit val format: OFormat[MergePreviewResponse] = Json.format[MergePreviewResponse]
}
