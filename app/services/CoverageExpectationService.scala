package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.*
import play.api.Logging
import repositories.{CoverageExpectationProfileRepository, TestTypeRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CoverageExpectationService @Inject()(
                                            profileRepo: CoverageExpectationProfileRepository,
                                            testTypeRepo: TestTypeRepository
                                          )(implicit ec: ExecutionContext) extends Logging {

  def assessVariantCallingConfidence(
                                      testTypeCode: String,
                                      coverageMetrics: CoverageMetricsInput
                                    ): Future[Option[SampleCoverageAssessment]] = {
    testTypeRepo.findByCode(testTypeCode).flatMap {
      case None => Future.successful(None)
      case Some(testType) =>
        testType.id match {
          case None => Future.successful(None)
          case Some(ttId) =>
            val isChip = testType.category == DataGenerationMethod.Genotyping
            profileRepo.findByTestTypeId(ttId).map { profiles =>
              if (profiles.isEmpty) None
              else Some(buildAssessment(testType, profiles, coverageMetrics, isChip))
            }
        }
    }
  }

  private[services] def buildAssessment(
                                          testType: TestTypeRow,
                                          profiles: Seq[CoverageExpectationProfile],
                                          metrics: CoverageMetricsInput,
                                          isChip: Boolean
                                        ): SampleCoverageAssessment = {
    val confidences = profiles.map { profile =>
      evaluateProfile(profile, metrics, isChip)
    }

    val overallConfidence = deriveOverallConfidence(confidences)

    SampleCoverageAssessment(
      testTypeCode = testType.code,
      testTypeDisplayName = testType.displayName,
      isChipBased = isChip,
      confidences = confidences,
      overallConfidence = overallConfidence
    )
  }

  private[services] def evaluateProfile(
                                          profile: CoverageExpectationProfile,
                                          metrics: CoverageMetricsInput,
                                          isChip: Boolean
                                        ): VariantCallingConfidence = {
    if (isChip) {
      evaluateChipProfile(profile, metrics)
    } else {
      evaluateSequencingProfile(profile, metrics)
    }
  }

  private def evaluateChipProfile(
                                    profile: CoverageExpectationProfile,
                                    metrics: CoverageMetricsInput
                                  ): VariantCallingConfidence = {
    val confidence = metrics.markerCount match {
      case Some(count) if count >= 500 => VariantCallingConfidence.HIGH
      case Some(count) if count >= 100 => VariantCallingConfidence.MEDIUM
      case Some(count) if count > 0 => VariantCallingConfidence.LOW
      case _ => VariantCallingConfidence.INSUFFICIENT
    }

    val details = metrics.markerCount.map(c => "markerCount" -> c.toString).toMap ++
      metrics.noCallRate.map(r => "noCallRate" -> f"$r%.4f")

    val noCallOk = metrics.noCallRate.forall(_ <= 0.05)

    VariantCallingConfidence(
      contigName = profile.contigName,
      variantClass = profile.variantClass,
      depthConfidence = confidence,
      coverageAdequate = true,
      mappingQualityAdequate = true,
      callableBasesAdequate = true,
      overallConfidence = if (noCallOk) confidence else downgrade(confidence),
      details = details
    )
  }

  private def evaluateSequencingProfile(
                                          profile: CoverageExpectationProfile,
                                          metrics: CoverageMetricsInput
                                        ): VariantCallingConfidence = {
    val depthConf = metrics.meanDepth match {
      case Some(depth) => profile.confidenceForDepth(depth)
      case None => VariantCallingConfidence.INSUFFICIENT
    }

    val coverageOk = (metrics.coveragePctAt1x, profile.minCoveragePct) match {
      case (Some(actual), Some(expected)) => actual >= expected
      case _ => true
    }

    val mappingQualOk = (metrics.meanMappingQuality, profile.minMappingQuality) match {
      case (Some(actual), Some(expected)) => actual >= expected
      case _ => true
    }

    val callableOk = (metrics.callablePct, profile.minCallablePct) match {
      case (Some(actual), Some(expected)) => actual >= expected
      case _ => true
    }

    val penalties = Seq(
      if (!coverageOk) 1 else 0,
      if (!mappingQualOk) 1 else 0,
      if (!callableOk) 1 else 0
    ).sum

    val overall = (0 until penalties).foldLeft(depthConf)((conf, _) => downgrade(conf))

    val details = Map.empty[String, String] ++
      metrics.meanDepth.map(d => "meanDepth" -> f"$d%.1f") ++
      metrics.coveragePctAt1x.map(c => "coveragePctAt1x" -> f"$c%.4f") ++
      metrics.meanMappingQuality.map(q => "meanMappingQuality" -> f"$q%.1f") ++
      metrics.callablePct.map(p => "callablePct" -> f"$p%.4f")

    VariantCallingConfidence(
      contigName = profile.contigName,
      variantClass = profile.variantClass,
      depthConfidence = depthConf,
      coverageAdequate = coverageOk,
      mappingQualityAdequate = mappingQualOk,
      callableBasesAdequate = callableOk,
      overallConfidence = overall,
      details = details
    )
  }

  private[services] def downgrade(confidence: String): String = confidence match {
    case VariantCallingConfidence.HIGH => VariantCallingConfidence.MEDIUM
    case VariantCallingConfidence.MEDIUM => VariantCallingConfidence.LOW
    case _ => VariantCallingConfidence.INSUFFICIENT
  }

  private[services] def deriveOverallConfidence(confidences: Seq[VariantCallingConfidence]): String = {
    if (confidences.isEmpty) return VariantCallingConfidence.INSUFFICIENT
    val levels = confidences.map(c => confidenceOrdinal(c.overallConfidence))
    confidenceFromOrdinal(levels.min)
  }

  private def confidenceOrdinal(c: String): Int = c match {
    case VariantCallingConfidence.HIGH => 3
    case VariantCallingConfidence.MEDIUM => 2
    case VariantCallingConfidence.LOW => 1
    case _ => 0
  }

  private def confidenceFromOrdinal(o: Int): String = o match {
    case 3 => VariantCallingConfidence.HIGH
    case 2 => VariantCallingConfidence.MEDIUM
    case 1 => VariantCallingConfidence.LOW
    case _ => VariantCallingConfidence.INSUFFICIENT
  }

  def getProfilesForTestType(testTypeCode: String): Future[Seq[CoverageExpectationProfile]] = {
    testTypeRepo.findByCode(testTypeCode).flatMap {
      case None => Future.successful(Seq.empty)
      case Some(tt) => tt.id match {
        case None => Future.successful(Seq.empty)
        case Some(ttId) => profileRepo.findByTestTypeId(ttId)
      }
    }
  }

  def getConfidenceForVariant(
                                testTypeCode: String,
                                contigName: String,
                                variantClass: String,
                                meanDepth: Double
                              ): Future[Option[String]] = {
    testTypeRepo.findByCode(testTypeCode).flatMap {
      case None => Future.successful(None)
      case Some(tt) => tt.id match {
        case None => Future.successful(None)
        case Some(ttId) =>
          profileRepo.findByTestTypeContigAndClass(ttId, contigName, variantClass).map {
            case None => None
            case Some(profile) => Some(profile.confidenceForDepth(meanDepth))
          }
      }
    }
  }
}

case class CoverageMetricsInput(
                                  meanDepth: Option[Double] = None,
                                  coveragePctAt1x: Option[Double] = None,
                                  meanMappingQuality: Option[Double] = None,
                                  callablePct: Option[Double] = None,
                                  markerCount: Option[Int] = None,
                                  noCallRate: Option[Double] = None
                                )

object CoverageMetricsInput {
  def fromEmbeddedCoverage(ec: EmbeddedCoverage, callableLoci: Option[BiosampleCallableLoci] = None): CoverageMetricsInput = {
    val callablePct = for {
      callable <- ec.basesCallable
      total <- ec.basesCallable.map(_ + ec.basesNoCoverage.getOrElse(0L) + ec.basesLowQualityMapping.getOrElse(0L))
      if total > 0
    } yield callable.toDouble / total

    CoverageMetricsInput(
      meanDepth = ec.meanDepth,
      coveragePctAt1x = ec.percentCoverageAt1x,
      meanMappingQuality = ec.meanMappingQuality,
      callablePct = callablePct
    )
  }

  def fromGenotypeMetrics(gm: GenotypeMetrics): CoverageMetricsInput = {
    CoverageMetricsInput(
      markerCount = gm.totalMarkersCalled,
      noCallRate = gm.noCallRate
    )
  }
}
