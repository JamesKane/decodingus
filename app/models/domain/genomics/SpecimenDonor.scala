package models.domain.genomics

import com.vividsolutions.jts.geom.Point

/**
 * Represents a donor of a specimen, encapsulating key attributes related to the donor, 
 * their origin, biological characteristics, and other identifiers.
 *
 * @param id                  An optional unique identifier for the specimen donor.
 * @param donorIdentifier     A unique identifier for the donor, serving as an external reference.
 * @param originBiobank       The name of the biobank or organization from which the donor originated.
 * @param donorType           The type of biosample provided by the donor, expressed as a `BiosampleType`.
 * @param sex                 An optional biological sex of the donor, represented as a `BiologicalSex` value.
 * @param geocoord            An optional geographical coordinate indicating the origin location of the donor.
 * @param pgpParticipantId    An optional identifier for the donor as a participant in the Personal Genome Project (PGP).
 * @param citizenBiosampleDid An optional decentralized identifier (DID) for a citizen-defined biosample associated with the donor.
 * @param dateRangeStart      An optional starting year for the time range relevant to the donor or specimen data.
 * @param dateRangeEnd        An optional ending year for the time range relevant to the donor or specimen data.
 */
case class SpecimenDonor(
                          id: Option[Int] = None,
                          donorIdentifier: String,
                          originBiobank: String,
                          donorType: BiosampleType,
                          sex: Option[BiologicalSex],
                          geocoord: Option[Point],
                          pgpParticipantId: Option[String] = None,
                          citizenBiosampleDid: Option[String] = None,
                          dateRangeStart: Option[Int] = None,
                          dateRangeEnd: Option[Int] = None
                        )

