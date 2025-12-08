package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{DataGenerationMethod, TestTypeRow}
import repositories.TestTypeRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestTypeServiceImpl @Inject()(
  testTypeRepository: TestTypeRepository
)(implicit ec: ExecutionContext)
  extends TestTypeService {

  override def getByCode(code: String): Future[Option[TestTypeRow]] = {
    testTypeRepository.findByCode(code)
  }

  override def getByCategory(category: DataGenerationMethod): Future[Seq[TestTypeRow]] = {
    testTypeRepository.findByCategory(category)
  }

  override def getByCapability(
    supportsY: Option[Boolean],
    supportsMt: Option[Boolean],
    supportsAutosomalIbd: Option[Boolean],
    supportsAncestry: Option[Boolean]
  ): Future[Seq[TestTypeRow]] = {
    testTypeRepository.findByCapability(supportsY, supportsMt, supportsAutosomalIbd, supportsAncestry)
  }

  override def isValidCode(code: String): Future[Boolean] = {
    testTypeRepository.findByCode(code).map(_.isDefined)
  }

  // override def getTargetRegions(testTypeId: Int): Future[Seq[TestTypeTargetRegion]] = ??? // TODO: Implement
}
