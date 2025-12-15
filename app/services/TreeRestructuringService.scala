package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.VariantV2
import models.domain.haplogroups.Haplogroup
import play.api.Logging
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, VariantV2Repository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for tree restructuring operations: split and merge.
 */
@Singleton
class TreeRestructuringService @Inject()(
    haplogroupRepository: HaplogroupCoreRepository,
    haplogroupVariantRepository: HaplogroupVariantRepository,
    variantV2Repository: VariantV2Repository,
    auditService: CuratorAuditService
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Split: Create a new subclade by moving variants and optionally re-parenting children.
   *
   * @param parentId ID of the parent haplogroup
   * @param newHaplogroup The new subclade haplogroup to create
   * @param variantIds IDs of variants to MOVE from parent to new child
   * @param childIds IDs of existing children to re-parent under new subclade
   * @param userId User performing the operation
   * @return ID of newly created haplogroup
   */
  def splitBranch(
      parentId: Int,
      newHaplogroup: Haplogroup,
      variantIds: Seq[Int],
      childIds: Seq[Int],
      userId: UUID
  ): Future[Int] = {
    for {
      // Verify parent exists
      parentOpt <- haplogroupRepository.findById(parentId)
      parent = parentOpt.getOrElse(throw new IllegalArgumentException(s"Parent haplogroup $parentId not found"))

      // Get parent's current children to validate childIds
      currentChildren <- haplogroupRepository.getDirectChildren(parentId)
      currentChildIds = currentChildren.flatMap(_.id).toSet
      _ = if (!childIds.forall(currentChildIds.contains)) {
        throw new IllegalArgumentException("Some childIds are not direct children of the parent")
      }

      // Create the new subclade with parent as its parent
      (newId, _) <- haplogroupRepository.createWithParent(newHaplogroup, Some(parentId), "split-operation")

      // Move variants from parent to new child
      movedVariantCount <- moveVariants(parentId, newId, variantIds)

      // Re-parent selected children to the new subclade
      _ <- Future.traverse(childIds) { childId =>
        haplogroupRepository.updateParent(childId, newId, "split-operation")
      }

      // Log the operation
      _ <- auditService.logBranchSplit(userId, parentId, newId, movedVariantCount, childIds, Some(s"Split ${newHaplogroup.name} from ${parent.name}"))

    } yield newId
  }

  /**
   * Merge: Absorb a child haplogroup into its parent (inverse of split).
   * Child's variants move to parent, child's children become parent's children, child is deleted.
   *
   * @param childId ID of the child haplogroup to absorb
   * @param userId User performing the operation
   * @return ID of the parent haplogroup
   */
  def mergeIntoParent(childId: Int, userId: UUID): Future[Int] = {
    for {
      // Verify child exists and has a parent
      childOpt <- haplogroupRepository.findById(childId)
      child = childOpt.getOrElse(throw new IllegalArgumentException(s"Haplogroup $childId not found"))

      parentOpt <- haplogroupRepository.getParent(childId)
      parent = parentOpt.getOrElse(throw new IllegalArgumentException(s"Haplogroup $childId has no parent - cannot merge root"))
      parentId = parent.id.get

      // Get child's children (grandchildren) to promote
      grandchildren <- haplogroupRepository.getDirectChildren(childId)
      grandchildIds = grandchildren.flatMap(_.id)

      // Get child's variants to move up
      childVariants <- haplogroupVariantRepository.getHaplogroupVariants(childId)

      // Get parent's existing variants to check for duplicates
      parentVariants <- haplogroupVariantRepository.getHaplogroupVariants(parentId)
      parentVariantIds = parentVariants.flatMap(_.variantId).toSet

      // Move unique variants from child to parent
      movedVariantCount <- moveVariantsUp(childId, parentId, parentVariantIds)

      // Promote grandchildren to parent
      _ <- Future.traverse(grandchildIds) { grandchildId =>
        haplogroupRepository.updateParent(grandchildId, parentId, "merge-operation")
      }

      // Soft-delete the child (this will also soft-delete its parent relationship)
      _ <- haplogroupRepository.softDelete(childId, "merge-operation")

      // Log the operation
      _ <- auditService.logMergeIntoParent(userId, parentId, childId, movedVariantCount, grandchildIds.size, Some(s"Merged ${child.name} into ${parent.name}"))

    } yield parentId
  }

  /**
   * Move variants from source haplogroup to target haplogroup.
   */
  private def moveVariants(sourceId: Int, targetId: Int, variantIds: Seq[Int]): Future[Int] = {
    if (variantIds.isEmpty) {
      Future.successful(0)
    } else {
      Future.traverse(variantIds) { variantId =>
        for {
          // Remove from source
          _ <- haplogroupVariantRepository.removeVariantFromHaplogroup(sourceId, variantId)
          // Add to target
          _ <- haplogroupVariantRepository.addVariantToHaplogroup(targetId, variantId)
        } yield 1
      }.map(_.sum)
    }
  }

  /**
   * Move all unique variants from child to parent.
   */
  private def moveVariantsUp(childId: Int, parentId: Int, existingParentVariantIds: Set[Int]): Future[Int] = {
    for {
      childVariants <- haplogroupVariantRepository.getHaplogroupVariants(childId)
      childVariantIds = childVariants.flatMap(_.variantId)

      // Only move variants that don't already exist on parent
      uniqueVariantIds = childVariantIds.filterNot(existingParentVariantIds.contains)

      // Move unique variants
      _ <- Future.traverse(uniqueVariantIds) { variantId =>
        for {
          _ <- haplogroupVariantRepository.removeVariantFromHaplogroup(childId, variantId)
          _ <- haplogroupVariantRepository.addVariantToHaplogroup(parentId, variantId)
        } yield ()
      }
    } yield uniqueVariantIds.size
  }

  /**
   * Get preview information for a split operation.
   */
  def getSplitPreview(parentId: Int): Future[SplitPreview] = {
    for {
      parentOpt <- haplogroupRepository.findById(parentId)
      parent = parentOpt.getOrElse(throw new IllegalArgumentException(s"Parent haplogroup $parentId not found"))
      variants <- haplogroupVariantRepository.getHaplogroupVariants(parentId)
      children <- haplogroupRepository.getDirectChildren(parentId)
    } yield SplitPreview(parent, variants, children)
  }

  /**
   * Get preview information for a merge operation.
   */
  def getMergePreview(childId: Int): Future[MergePreview] = {
    for {
      childOpt <- haplogroupRepository.findById(childId)
      child = childOpt.getOrElse(throw new IllegalArgumentException(s"Haplogroup $childId not found"))

      parentOpt <- haplogroupRepository.getParent(childId)
      parent = parentOpt.getOrElse(throw new IllegalArgumentException(s"Haplogroup $childId has no parent"))

      childVariants <- haplogroupVariantRepository.getHaplogroupVariants(childId)
      grandchildren <- haplogroupRepository.getDirectChildren(childId)

      parentVariants <- haplogroupVariantRepository.getHaplogroupVariants(parent.id.get)
      parentVariantIds = parentVariants.flatMap(_.variantId).toSet

      // Calculate unique variants that will be moved
      uniqueVariants = childVariants.filter { v =>
        v.variantId.exists(!parentVariantIds.contains(_))
      }

    } yield MergePreview(child, parent, childVariants, uniqueVariants, grandchildren)
  }
}

/**
 * Preview data for a split operation.
 */
case class SplitPreview(
    parent: Haplogroup,
    variants: Seq[VariantV2],
    children: Seq[Haplogroup]
)

/**
 * Preview data for a merge operation.
 */
case class MergePreview(
    child: Haplogroup,
    parent: Haplogroup,
    allVariants: Seq[VariantV2],
    uniqueVariants: Seq[VariantV2],
    grandchildren: Seq[Haplogroup]
)
