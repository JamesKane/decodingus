package models.domain.genomics

import com.vividsolutions.jts.geom.Point

import java.util.UUID

/**
 * Represents a biological sample, providing metadata such as its type, identifier, description, 
 * and several optional attributes describing its source, associated donor, 
 * and other related information.
 *
 * @param id                  An optional unique identifier for the biosample.
 * @param sampleType          The type of the biosample, represented as an enumeration value.
 * @param sampleGuid          A universally unique identifier (UUID) for the biosample.
 * @param sampleAccession     A unique accession string identifying the biosample.
 * @param description         A textual description of the biosample.
 * @param alias               An optional alias or alternate name for the biosample.
 * @param centerName          The name of the center responsible for the biosample.
 * @param sex                 An optional specification of the biological sex related to the biosample.
 * @param geocoord            An optional geographical coordinate indicating the origin of the biosample.
 * @param specimenDonorId     An optional unique identifier for the donor of the specimen.
 * @param locked              A boolean flag to indicate if the biosample is locked for editing or modifications.
 * @param pgpParticipantId    An optional identifier for a PGP participant associated with the biosample.
 * @param citizenBiosampleDid An optional decentralized identifier (DID) for the citizen's biosample.
 * @param sourcePlatform      An optional specification of the platform from which the biosample was sourced.
 * @param dateRangeStart      An optional starting year representing the range of dates linked to the biosample.
 * @param dateRangeEnd        An optional ending year representing the range of dates linked to the biosample.
 */
case class Biosample(
                      id: Option[Int] = None,
                      sampleType: BiosampleType,
                      sampleGuid: UUID,
                      sampleAccession: String,
                      description: String,
                      alias: Option[String],
                      centerName: String,
                      sex: Option[String],
                      geocoord: Option[Point],
                      specimenDonorId: Option[Int],
                      locked: Boolean = false,
                      pgpParticipantId: Option[String] = None,
                      citizenBiosampleDid: Option[String] = None,
                      sourcePlatform: Option[String] = None,
                      dateRangeStart: Option[Int] = None,
                      dateRangeEnd: Option[Int] = None
                    )
