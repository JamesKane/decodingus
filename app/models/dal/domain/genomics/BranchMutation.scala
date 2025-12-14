package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.haplogroups.HaplogroupsTable
import play.api.libs.json.{Json, OFormat}

/**
 * Represents a state transition along a tree branch.
 *
 * Records where mutations occurred in the phylogenetic tree,
 * tracking the change from parent to child haplogroup state.
 *
 * @param id                  Auto-generated primary key
 * @param variantId           FK to the variant that changed
 * @param parentHaplogroupId  FK to parent haplogroup node
 * @param childHaplogroupId   FK to child haplogroup node
 * @param fromState           State at parent node (e.g., "G", "15")
 * @param toState             State at child node (e.g., "A", "16")
 * @param stepDirection       For STRs: +1 = expansion, -1 = contraction; NULL for SNPs
 * @param confidence          Confidence from ASR algorithm
 */
case class BranchMutation(
  id: Option[Int] = None,
  variantId: Int,
  parentHaplogroupId: Int,
  childHaplogroupId: Int,
  fromState: String,
  toState: String,
  stepDirection: Option[Int] = None,
  confidence: Option[BigDecimal] = None
)

object BranchMutation {
  implicit val format: OFormat[BranchMutation] = Json.format[BranchMutation]
}

/**
 * Slick table definition for branch_mutation.
 */
class BranchMutationTable(tag: Tag)
  extends Table[BranchMutation](tag, Some("public"), "branch_mutation") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def variantId = column[Int]("variant_id")

  def parentHaplogroupId = column[Int]("parent_haplogroup_id")

  def childHaplogroupId = column[Int]("child_haplogroup_id")

  def fromState = column[String]("from_state")

  def toState = column[String]("to_state")

  def stepDirection = column[Option[Int]]("step_direction")

  def confidence = column[Option[BigDecimal]]("confidence")

  def * = (
    id.?,
    variantId,
    parentHaplogroupId,
    childHaplogroupId,
    fromState,
    toState,
    stepDirection,
    confidence
  ).mapTo[BranchMutation]

  def variantFK = foreignKey(
    "branch_mutation_variant_fk",
    variantId,
    TableQuery[VariantV2Table]
  )(_.variantId, onDelete = ForeignKeyAction.Cascade)

  def parentHaplogroupFK = foreignKey(
    "branch_mutation_parent_haplogroup_fk",
    parentHaplogroupId,
    TableQuery[HaplogroupsTable]
  )(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  def childHaplogroupFK = foreignKey(
    "branch_mutation_child_haplogroup_fk",
    childHaplogroupId,
    TableQuery[HaplogroupsTable]
  )(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  def uniqueBranchVariant = index(
    "idx_branch_mutation_unique",
    (variantId, parentHaplogroupId, childHaplogroupId),
    unique = true
  )
}
