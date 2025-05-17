package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Represents information about a specific population.
 *
 * @constructor Creates a new instance of PopulationInfo.
 * @param populationName Name of the population.
 * @param probability    Probability associated with the population.
 * @param methodName     Name of the method or approach used to determine the population information.
 */
case class PopulationInfo(populationName: String, probability: BigDecimal, methodName: String)

/**
 * Represents a biosample with detailed origin and associated metadata.
 *
 * @param sampleName        An optional name of the sample.
 * @param enaAccession      ENA (European Nucleotide Archive) accession identifier for the sample.
 * @param sex               An optional gender or sex information for the sample.
 * @param yDnaHaplogroup    An optional Y-DNA haplogroup associated with the sample.
 * @param mtDnaHaplogroup   An optional mitochondrial DNA haplogroup associated with the sample.
 * @param reads             An optional number of reads generated for the sample.
 * @param readLen           An optional length of each read.
 * @param geoCoord          An optional geographical coordinate specifying the origin of the sample.
 * @param bestFitPopulation An optional population information associated with the sample.
 */
case class BiosampleWithOrigin(
                                sampleName: Option[String], 
                                enaAccession: String, 
                                sex: Option[String], 
                                yDnaHaplogroup: Option[String], 
                                mtDnaHaplogroup: Option[String], 
                                reads: Option[Int], 
                                readLen: Option[Int], 
                                geoCoord: Option[GeoCoord],
                                bestFitPopulation: Option[PopulationInfo],
                              ) {
  /**
   * Formats the geographic coordinate of the origin into a human-readable string.
   * If the geographic coordinate is available, it will return the latitude and longitude with appropriate directional indicators (N/S and E/W).
   * If the geographic coordinate is not available, it will return "Origin Not Available".
   *
   * @return A formatted string representing the origin's geographic coordinate or a fallback message if unavailable.
   */
  def formattedOrigin: String = geoCoord match {
    case Some(lat, lon) =>
      val latDir = if (lat >= 0) "N" else "S"
      val lonDir = if (lon >= 0) "E" else "W"
      f"${math.abs(lat)}%.2f°$latDir, ${math.abs(lon)}%.2f°$lonDir"
    case None =>
      "Origin Not Available"
  }

  import scala.math.BigDecimal

  /**
   * Estimates the coverage depth of the genome based on the number of reads and the read length.
   * If either the number of reads or the read length is unavailable, returns None.
   *
   * @return An optional `Long` value representing the estimated coverage depth. If either input is missing, returns None.
   */
  def estimateCoverageDepth: Option[Long] = (reads, readLen) match {
    case (Some(reads), Some(readLen)) =>
      val totalBases = BigDecimal(reads) * BigDecimal(readLen)
      val genomeSize = BigDecimal(3_099_441_038L)
      Some((totalBases / genomeSize).toLong)
    case _ => None
  }
}

/**
 * Companion object for the PopulationInfo case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * PopulationInfo instances using the Play Framework's JSON library.
 *
 * This formatter can be utilized for converting PopulationInfo objects to JSON
 * representation and vice versa in a type-safe and automated manner.
 */
object PopulationInfo {
  implicit val populationInfoFormat: OFormat[PopulationInfo] = Json.format[PopulationInfo]
}

/**
 * Companion object for the `BiosampleWithOrigin` class.
 * Provides an implicit JSON formatter for instances of `BiosampleWithOrigin`.
 */
object BiosampleWithOrigin {
  implicit val biosampleWithOriginFormat: OFormat[BiosampleWithOrigin] = Json.format[BiosampleWithOrigin]
}

