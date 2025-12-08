package services

import models.domain.genomics.DataGenerationMethod
import models.domain.genomics.TestTypeRow

import scala.concurrent.Future

trait TestTypeService {
  /**
   * Get test type definition by code.
   */
  def getByCode(code: String): Future[Option[TestTypeRow]]

  /**
   * Get all active test types in a category.
   */
  def getByCategory(category: DataGenerationMethod): Future[Seq[TestTypeRow]]

  /**
   * Get test types that support a specific capability.
   */
  def getByCapability(
    supportsY: Option[Boolean] = None,
    supportsMt: Option[Boolean] = None,
    supportsAutosomalIbd: Option[Boolean] = None,
    supportsAncestry: Option[Boolean] = None
  ): Future[Seq[TestTypeRow]]

  /**
   * Validate that a test type code is valid.
   */
  def isValidCode(code: String): Future[Boolean]

  /**
   * Get target regions for a test type.
   */
  // def getTargetRegions(testTypeId: Int): Future[Seq[TestTypeTargetRegion]] // TODO: Implement TestTypeTargetRegion
}
