package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.*
import play.api.Logging
import repositories.{TestTypeRepository, TestTypeTargetRegionRepository}

import scala.concurrent.{ExecutionContext, Future}

case class TargetedTestCapabilities(
                                     testType: TestTypeRow,
                                     targetRegions: Seq[TestTypeTargetRegion],
                                     supportsYDna: Boolean,
                                     supportsMtDna: Boolean,
                                     primaryContig: Option[String],
                                     totalTargetedBases: Option[Long]
                                   )

@Singleton
class TargetedSequencingService @Inject()(
                                           testTypeRepo: TestTypeRepository,
                                           targetRegionRepo: TestTypeTargetRegionRepository
                                         )(implicit ec: ExecutionContext) extends Logging {

  def getTargetedTestCapabilities(testTypeCode: String): Future[Option[TargetedTestCapabilities]] = {
    testTypeRepo.findByCode(testTypeCode).flatMap {
      case None => Future.successful(None)
      case Some(testType) =>
        testType.id match {
          case None => Future.successful(None)
          case Some(ttId) =>
            targetRegionRepo.findByTestTypeId(ttId).map { regions =>
              val primaryContig = regions.headOption.map(_.contigName)
              val totalBases = regions.flatMap(_.regionSize).sum
              Some(TargetedTestCapabilities(
                testType = testType,
                targetRegions = regions,
                supportsYDna = testType.supportsHaplogroupY,
                supportsMtDna = testType.supportsHaplogroupMt,
                primaryContig = primaryContig,
                totalTargetedBases = if (totalBases > 0) Some(totalBases.toLong) else None
              ))
            }
        }
    }
  }

  def assessCoverage(
                      testTypeCode: String,
                      actualMeanDepth: Option[Double],
                      actualCoveragePct: Option[Double]
                    ): Future[Option[TargetedCoverageAssessment]] = {
    testTypeRepo.findByCode(testTypeCode).flatMap {
      case None => Future.successful(None)
      case Some(testType) =>
        testType.id match {
          case None => Future.successful(None)
          case Some(ttId) =>
            targetRegionRepo.findByTestTypeId(ttId).map { regions =>
              if (regions.isEmpty) None
              else Some(buildAssessment(testType, regions, actualMeanDepth, actualCoveragePct))
            }
        }
    }
  }

  private[services] def buildAssessment(
                                          testType: TestTypeRow,
                                          regions: Seq[TestTypeTargetRegion],
                                          actualMeanDepth: Option[Double],
                                          actualCoveragePct: Option[Double]
                                        ): TargetedCoverageAssessment = {
    val regionResults = regions.map { region =>
      val meetsDepth = (actualMeanDepth, region.expectedMinDepth) match {
        case (Some(actual), Some(expected)) => actual >= expected
        case _ => true
      }
      val meetsCoverage = (actualCoveragePct, region.expectedCoveragePct) match {
        case (Some(actual), Some(expected)) => actual >= expected
        case _ => true
      }

      RegionCoverageResult(
        regionName = region.regionName,
        contigName = region.contigName,
        startPosition = region.startPosition,
        endPosition = region.endPosition,
        expectedCoveragePct = region.expectedCoveragePct,
        expectedMinDepth = region.expectedMinDepth,
        actualMeanDepth = actualMeanDepth,
        actualCoveragePct = actualCoveragePct,
        meetsExpectation = meetsDepth && meetsCoverage
      )
    }

    val overallCoverage = actualCoveragePct.getOrElse(0.0)
    val allMeet = regionResults.forall(_.meetsExpectation)

    TargetedCoverageAssessment(
      testTypeCode = testType.code,
      testTypeDisplayName = testType.displayName,
      targetRegions = regionResults,
      overallCoveragePct = overallCoverage,
      overallMeetsExpectation = allMeet,
      qualityTier = TargetedCoverageAssessment.qualityTierFromCoverage(overallCoverage)
    )
  }

  def getTargetedYTests: Future[Seq[TestTypeRow]] = {
    testTypeRepo.findByCapability(supportsY = Some(true)).map { tests =>
      tests.filter(_.targetType == TargetType.YChromosome)
    }
  }

  def getTargetedMtTests: Future[Seq[TestTypeRow]] = {
    testTypeRepo.findByCapability(supportsMt = Some(true)).map { tests =>
      tests.filter(_.targetType == TargetType.MtDna)
    }
  }

  def findUpgradePath(currentTestTypeCode: String): Future[Option[TestTypeRow]] = {
    testTypeRepo.findByCode(currentTestTypeCode).flatMap {
      case Some(current) if current.successorTestTypeId.isDefined =>
        testTypeRepo.getTestTypeRowsByIds(Seq(current.successorTestTypeId.get)).map(_.headOption)
      case _ => Future.successful(None)
    }
  }

  def isTargetedTest(testTypeCode: String): Future[Boolean] = {
    testTypeRepo.findByCode(testTypeCode).map {
      case Some(tt) => tt.targetType == TargetType.YChromosome || tt.targetType == TargetType.MtDna
      case None => false
    }
  }
}
