package models.domain.genomics

import com.vividsolutions.jts.geom.Point

import java.util.UUID

/**
 * Represents a biological sample with detailed information regarding its metadata and identifiers.
 *
 * @param id              An optional unique identifier for the biosample.
 * @param sampleAccession The unique accession number of the sample, typically used in external or public databases.
 * @param description     A textual description of the biosample providing additional context.
 * @param alias           An optional alias or alternative name for the biosample.
 * @param centerName      The name of the center or institution responsible for handling, processing, or collecting the sample.
 * @param sex             An optional description of the sex of the individual from which the sample was obtained.
 * @param geocoord        An optional geographic coordinate (latitude and longitude) representing the location of the sample's origin.
 * @param specimenDonorId An optional identifier linking to a donor or specimen from which the sample was derived.
 * @param sampleGuid      A universally unique identifier (UUID) to identify the sample across systems and datasets.
 */
case class Biosample(
                      id: Option[Int] = None,
                      sampleAccession: String,
                      description: String,
                      alias: Option[String],
                      centerName: String,
                      sex: Option[String],
                      geocoord: Option[Point],
                      specimenDonorId: Option[Int],
                      sampleGuid: UUID,
                      locked: Boolean = false
                    )