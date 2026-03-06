package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.atmosphere.{HaplogroupAssignments, VariantCall}
import models.domain.genomics.*
import play.api.Logging
import repositories.GenotypeDataRepository

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class ChipQualityAssessment(
                                   overallQuality: String,
                                   noCallRateAcceptable: Boolean,
                                   yDnaCoverage: Option[String],
                                   mtDnaCoverage: Option[String],
                                   warnings: Seq[String]
                                 )

case class ChipRegistrationResult(
                                    genotypeDataId: Option[Int],
                                    testTypeCode: String,
                                    provider: String,
                                    qualityAssessment: ChipQualityAssessment,
                                    yHaplogroupAssigned: Option[String],
                                    mtHaplogroupAssigned: Option[String],
                                    yPrivateVariantsExtracted: Int,
                                    mtPrivateVariantsExtracted: Int
                                  )

@Singleton
class ChipDataRegistrationService @Inject()(
                                              testTypeService: TestTypeService,
                                              genotypeRepo: GenotypeDataRepository,
                                              privateVariantService: PrivateVariantExtractionService
                                            )(implicit ec: ExecutionContext) extends Logging {

  val MaxNoCallRate: Double = 0.05
  val MinMarkerCount: Int = 100000
  val MinYMarkersForHaplogroup: Int = 50
  val MinMtMarkersForHaplogroup: Int = 20

  def assessQuality(metrics: GenotypeMetrics, testType: Option[TestTypeRow]): ChipQualityAssessment = {
    val warnings = scala.collection.mutable.ArrayBuffer[String]()

    val noCallOk = metrics.noCallRate.forall(_ <= MaxNoCallRate)
    if (!noCallOk) {
      warnings += s"No-call rate ${metrics.noCallRate.getOrElse(0.0)} exceeds threshold $MaxNoCallRate"
    }

    val totalMarkers = metrics.totalMarkersCalled.getOrElse(0)
    if (totalMarkers < MinMarkerCount) {
      warnings += s"Total markers called ($totalMarkers) below minimum ($MinMarkerCount)"
    }

    val expectedMarkers = testType.flatMap(_.expectedMarkerCount)
    expectedMarkers.foreach { expected =>
      val ratio = totalMarkers.toDouble / expected
      if (ratio < 0.90) {
        warnings += s"Marker count ($totalMarkers) is ${(ratio * 100).toInt}% of expected ($expected)"
      }
    }

    val yCoverage = metrics.yMarkersCalled.map { yCalled =>
      if (yCalled >= MinYMarkersForHaplogroup) "SUFFICIENT"
      else if (yCalled > 0) "LIMITED"
      else "NONE"
    }

    val mtCoverage = metrics.mtMarkersCalled.map { mtCalled =>
      if (mtCalled >= MinMtMarkersForHaplogroup) "SUFFICIENT"
      else if (mtCalled > 0) "LIMITED"
      else "NONE"
    }

    val quality = if (!noCallOk || totalMarkers < MinMarkerCount) "LOW"
    else if (warnings.isEmpty) "HIGH"
    else "MEDIUM"

    ChipQualityAssessment(
      overallQuality = quality,
      noCallRateAcceptable = noCallOk,
      yDnaCoverage = yCoverage,
      mtDnaCoverage = mtCoverage,
      warnings = warnings.toSeq
    )
  }

  def extractPrivateVariantsFromChip(
                                       citizenBiosampleId: Int,
                                       sampleGuid: UUID,
                                       haplogroupAssignments: Option[HaplogroupAssignments]
                                     ): Future[ChipVariantExtractionResult] = {
    haplogroupAssignments match {
      case None => Future.successful(ChipVariantExtractionResult(0, 0))
      case Some(assignments) =>
        val yFut = assignments.yDna match {
          case Some(yResult) if yResult.privateVariants.flatMap(_.variants).exists(_.nonEmpty) =>
            val variants = yResult.privateVariants.get.variants.get
            privateVariantService.extractFromCitizenBiosample(
              citizenBiosampleId, sampleGuid, yResult.haplogroupName,
              HaplogroupType.Y, variants
            ).map(_.size).recover {
              case e: Exception =>
                logger.warn(s"Failed to extract Y private variants for sample $sampleGuid: ${e.getMessage}")
                0
            }
          case _ => Future.successful(0)
        }

        val mtFut = assignments.mtDna match {
          case Some(mtResult) if mtResult.privateVariants.flatMap(_.variants).exists(_.nonEmpty) =>
            val variants = mtResult.privateVariants.get.variants.get
            privateVariantService.extractFromCitizenBiosample(
              citizenBiosampleId, sampleGuid, mtResult.haplogroupName,
              HaplogroupType.MT, variants
            ).map(_.size).recover {
              case e: Exception =>
                logger.warn(s"Failed to extract mtDNA private variants for sample $sampleGuid: ${e.getMessage}")
                0
            }
          case _ => Future.successful(0)
        }

        for {
          yCount <- yFut
          mtCount <- mtFut
        } yield ChipVariantExtractionResult(yCount, mtCount)
    }
  }

  def validateChipData(
                         testTypeCode: String,
                         provider: String,
                         metrics: GenotypeMetrics
                       ): Future[Either[Seq[String], TestTypeRow]] = {
    testTypeService.getByCode(testTypeCode).map {
      case None =>
        Left(Seq(s"Unknown test type code: $testTypeCode"))
      case Some(testType) =>
        val errors = scala.collection.mutable.ArrayBuffer[String]()

        if (testType.category != DataGenerationMethod.Genotyping) {
          errors += s"Test type $testTypeCode is not a genotyping test"
        }

        if (provider.isBlank) {
          errors += "Provider is required"
        }

        metrics.totalMarkersCalled match {
          case Some(count) if count <= 0 =>
            errors += "Total markers called must be positive"
          case None =>
            errors += "Total markers called is required"
          case _ => // ok
        }

        if (errors.isEmpty) Right(testType)
        else Left(errors.toSeq)
    }
  }

  def findExistingByHash(sourceFileHash: String): Future[Option[GenotypeData]] = {
    genotypeRepo.findBySourceFileHash(sourceFileHash)
  }
}

case class ChipVariantExtractionResult(
                                         yPrivateVariants: Int,
                                         mtPrivateVariants: Int
                                       ) {
  def total: Int = yPrivateVariants + mtPrivateVariants
}
