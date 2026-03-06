package services

import jakarta.inject.Inject
import models.domain.haplogroups.{AgeEstimate, AnchorType, GenealogicalAnchor}
import play.api.Logging
import repositories.GenealogicalAnchorRepository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for managing genealogical anchors and applying them as constraints
 * to age estimation.
 *
 * Anchors provide historical calibration points:
 * - KNOWN_MRCA: A documented most recent common ancestor with a known date
 * - MDKA: Most distant known ancestor (provides a minimum age)
 * - ANCIENT_DNA: Carbon-dated ancient DNA sample assigned to a haplogroup
 */
class GenealogicalAnchorService @Inject()(
  anchorRepo: GenealogicalAnchorRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Get all anchors for a haplogroup, ordered by confidence descending.
   */
  def getAnchorsForHaplogroup(haplogroupId: Int): Future[Seq[GenealogicalAnchor]] =
    anchorRepo.findByHaplogroup(haplogroupId).map(_.sortBy(a => -a.confidence.getOrElse(BigDecimal(0)).toDouble))

  /**
   * Create a new anchor after validation.
   */
  def createAnchor(anchor: GenealogicalAnchor): Future[GenealogicalAnchor] = {
    validateAnchor(anchor)
    anchorRepo.create(anchor)
  }

  /**
   * Update an existing anchor after validation.
   */
  def updateAnchor(anchor: GenealogicalAnchor): Future[Boolean] = {
    validateAnchor(anchor)
    anchorRepo.update(anchor)
  }

  /**
   * Delete an anchor by ID.
   */
  def deleteAnchor(id: Int): Future[Boolean] =
    anchorRepo.delete(id)

  /**
   * Apply anchor constraints to an age estimate.
   *
   * Constraints narrow the estimate based on historical evidence:
   * - KNOWN_MRCA: The haplogroup formed before this date (provides upper bound on age YBP)
   * - MDKA: The haplogroup must be at least as old as this ancestor (provides lower bound on age YBP)
   * - ANCIENT_DNA: Provides a hard lower bound (haplogroup must predate the sample)
   *
   * @return Adjusted estimate with constraint metadata
   */
  def applyAnchorConstraints(
    haplogroupId: Int,
    estimate: AgeEstimate
  ): Future[AnchorConstrainedEstimate] = {
    anchorRepo.findByHaplogroup(haplogroupId).map { anchors =>
      if (anchors.isEmpty) {
        AnchorConstrainedEstimate(estimate, anchors = Seq.empty, constrained = false)
      } else {
        applyConstraints(estimate, anchors)
      }
    }
  }

  /**
   * Apply constraints from anchors to an estimate.
   */
  private[services] def applyConstraints(
    estimate: AgeEstimate,
    anchors: Seq[GenealogicalAnchor]
  ): AnchorConstrainedEstimate = {
    var adjustedYbp = estimate.ybp
    var adjustedLower = estimate.ybpLower.getOrElse(0)
    var adjustedUpper = estimate.ybpUpper.getOrElse(Int.MaxValue)
    var constrained = false

    for (anchor <- anchors) {
      val anchorYbp = anchor.toYbp
      val uncertainty = anchor.dateUncertaintyYears.getOrElse(0)
      val weight = anchor.confidence.map(_.toDouble).getOrElse(0.5)

      anchor.anchorType match {
        case AnchorType.KnownMrca =>
          // Known MRCA: haplogroup formed before this person lived
          // The TMRCA must be >= this date (in YBP terms, >= anchorYbp)
          val lowerBound = anchorYbp - uncertainty
          if (adjustedLower < lowerBound) {
            adjustedLower = lowerBound
            constrained = true
          }
          if (adjustedYbp < lowerBound) {
            adjustedYbp = lowerBound
            constrained = true
          }

        case AnchorType.Mdka =>
          // Most distant known ancestor: haplogroup is at least this old
          val lowerBound = anchorYbp - uncertainty
          if (adjustedLower < lowerBound) {
            adjustedLower = lowerBound
            constrained = true
          }
          if (adjustedYbp < lowerBound) {
            adjustedYbp = lowerBound
            constrained = true
          }

        case AnchorType.AncientDna =>
          // Ancient DNA with carbon dating: hard lower bound
          val carbonYbp = anchor.carbonDateBp.getOrElse(anchorYbp)
          val carbonSigma = anchor.carbonDateSigma.getOrElse(uncertainty)
          val lowerBound = carbonYbp - 2 * carbonSigma // 2-sigma lower bound
          if (adjustedLower < lowerBound) {
            adjustedLower = lowerBound
            constrained = true
          }
          if (adjustedYbp < lowerBound) {
            adjustedYbp = lowerBound
            constrained = true
          }
      }
    }

    // Ensure consistency
    if (adjustedLower > adjustedUpper) adjustedUpper = adjustedLower
    if (adjustedYbp > adjustedUpper) adjustedUpper = adjustedYbp

    val adjustedEstimate = AgeEstimate(adjustedYbp, Some(adjustedLower), Some(adjustedUpper))

    if (constrained) {
      logger.debug(s"Anchor constraint applied for haplogroup: " +
        s"${estimate.ybp} -> $adjustedYbp YBP (${anchors.size} anchors)")
    }

    AnchorConstrainedEstimate(adjustedEstimate, anchors, constrained)
  }

  /**
   * Validate anchor data before persistence.
   */
  private[services] def validateAnchor(anchor: GenealogicalAnchor): Unit = {
    require(anchor.haplogroupId > 0, "Haplogroup ID must be positive")

    anchor.anchorType match {
      case AnchorType.AncientDna =>
        require(anchor.carbonDateBp.isDefined || anchor.dateCe != 0,
          "Ancient DNA anchors must have carbon_date_bp or a non-zero date_ce")
      case _ => // No additional validation
    }

    anchor.confidence.foreach { c =>
      require(c >= 0 && c <= 1, s"Confidence must be between 0 and 1, got $c")
    }
  }
}

/**
 * Result of applying anchor constraints to an age estimate.
 */
case class AnchorConstrainedEstimate(
  estimate: AgeEstimate,
  anchors: Seq[GenealogicalAnchor],
  constrained: Boolean
)
