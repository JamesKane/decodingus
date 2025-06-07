package models.domain.genomics

import com.vividsolutions.jts.geom.Point

import java.util.UUID

/**
 * Represents a biosample with attributes describing its unique identifiers, source, and metadata.
 *
 * @param id              An optional unique identifier for the biosample, used for internal purposes.
 * @param sampleGuid      A globally unique identifier for the biosample.
 * @param sampleAccession A unique external accession for the biosample, often used for database references.
 * @param description     A textual description of the biosample.
 * @param alias           An optional short identifier or alias for the biosample, which may simplify referencing.
 * @param centerName      The name of the organization or center responsible for the biosample.
 * @param specimenDonorId An optional identifier linking the biosample to a specific specimen donor.
 * @param locked          A flag indicating whether the biosample is immutable or restricted from further edits.
 * @param sourcePlatform  An optional field specifying the platform or technology used to derive the biosample.
 */
case class Biosample(
                      id: Option[Int] = None,
                      sampleGuid: UUID,
                      sampleAccession: String,
                      description: String,
                      alias: Option[String],
                      centerName: String,
                      specimenDonorId: Option[Int],
                      locked: Boolean = false,
                      sourcePlatform: Option[String] = None
                    )
