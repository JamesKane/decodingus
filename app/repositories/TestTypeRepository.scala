package repositories

import models.domain.genomics.{DataGenerationMethod, TestTypeRow}

import scala.concurrent.Future

trait TestTypeRepository {
  def findByCode(code: String): Future[Option[TestTypeRow]]
  def listAll(): Future[Seq[TestTypeRow]]
  def findByCategory(category: DataGenerationMethod): Future[Seq[TestTypeRow]]
  def findByCapability(
    supportsY: Option[Boolean] = None,
    supportsMt: Option[Boolean] = None,
    supportsAutosomalIbd: Option[Boolean] = None,
    supportsAncestry: Option[Boolean] = None
  ): Future[Seq[TestTypeRow]]
  def create(testType: TestTypeRow): Future[TestTypeRow]
  def update(testType: TestTypeRow): Future[Boolean]
  def delete(id: Int): Future[Boolean]
  def getTestTypeRowsByIds(ids: Seq[Int]): Future[Seq[TestTypeRow]]
}
