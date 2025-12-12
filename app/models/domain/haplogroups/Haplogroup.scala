package models.domain.haplogroups

import models.HaplogroupType

import java.time.LocalDateTime

/**
 * Represents a haplogroup, which is a genetic population group of people who share a common ancestor
 * on the paternal or maternal line. This case class captures details about a haplogroup, including its 
 * type, name, lineage, and other metadata.
 *
 * @param id              An optional unique identifier for the haplogroup. Typically used for internal purposes.
 * @param name            The name of the haplogroup. This is required and serves as the primary identifier in a lineage context.
 * @param lineage         An optional description of the lineage to which the haplogroup belongs.
 * @param description     An optional textual description of the haplogroup providing additional context or details.
 * @param haplogroupType  The type of haplogroup (e.g., Y-DNA or mtDNA). This is represented as an enum.
 * @param revisionId      An integer that indicates the revision or version of the haplogroup data.
 * @param source          The source or origin of the haplogroup information for traceability purposes.
 * @param confidenceLevel A textual representation of the confidence level associated with assigning this haplogroup.
 * @param validFrom       The timestamp indicating when this haplogroup record became valid or effective.
 * @param validUntil      An optional timestamp indicating when this haplogroup record is no longer valid.
 */
/**
 * Represents age estimate for a haplogroup branch (formed date or TMRCA).
 * Values are in years before present (YBP) with optional 95% confidence interval.
 *
 * @param ybp      Point estimate in years before present
 * @param ybpLower Lower bound of 95% confidence interval
 * @param ybpUpper Upper bound of 95% confidence interval
 */
case class AgeEstimate(
                        ybp: Int,
                        ybpLower: Option[Int] = None,
                        ybpUpper: Option[Int] = None
                      ) {
  /**
   * Convert YBP to calendar year (AD/BC).
   * Assumes present = 1950 CE (radiocarbon dating convention).
   */
  def toCalendarYear: Int = 1950 - ybp

  /**
   * Format as human-readable string (e.g., "2500 BC" or "500 AD").
   */
  def formatted: String = {
    val year = toCalendarYear
    if (year < 0) s"${-year} BC" else s"$year AD"
  }

  /**
   * Format with confidence interval if available.
   */
  def formattedWithRange: String = {
    (ybpLower, ybpUpper) match {
      case (Some(lower), Some(upper)) =>
        val lowerYear = 1950 - upper // Note: higher YBP = older = lower calendar year
        val upperYear = 1950 - lower
        val lowerStr = if (lowerYear < 0) s"${-lowerYear} BC" else s"$lowerYear AD"
        val upperStr = if (upperYear < 0) s"${-upperYear} BC" else s"$upperYear AD"
        s"$formatted ($lowerStr – $upperStr)"
      case _ => formatted
    }
  }
}

case class Haplogroup(
                       id: Option[Int] = None,
                       name: String,
                       lineage: Option[String],
                       description: Option[String],
                       haplogroupType: HaplogroupType,
                       revisionId: Int,
                       source: String,
                       confidenceLevel: String,
                       validFrom: LocalDateTime,
                       validUntil: Option[LocalDateTime],
                       formedYbp: Option[Int] = None,
                       formedYbpLower: Option[Int] = None,
                       formedYbpUpper: Option[Int] = None,
                       tmrcaYbp: Option[Int] = None,
                       tmrcaYbpLower: Option[Int] = None,
                       tmrcaYbpUpper: Option[Int] = None,
                       ageEstimateSource: Option[String] = None,
                       provenance: Option[HaplogroupProvenance] = None
                     ) {
  /** Get formed date as AgeEstimate if available */
  def formedEstimate: Option[AgeEstimate] = formedYbp.map(y => AgeEstimate(y, formedYbpLower, formedYbpUpper))

  /** Get TMRCA as AgeEstimate if available */
  def tmrcaEstimate: Option[AgeEstimate] = tmrcaYbp.map(y => AgeEstimate(y, tmrcaYbpLower, tmrcaYbpUpper))
}