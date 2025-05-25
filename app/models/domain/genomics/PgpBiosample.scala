package models.domain.genomics

import java.util.UUID

/**
 * Represents a PGP (Personal Genome Project) biological sample associated with a study participant.
 *
 * @param id               An optional unique identifier for the biosample, used for internal tracking purposes.
 * @param pgpParticipantId The identifier of the PGP participant associated with this biosample.
 * @param sex              The biological sex of the individual from which the sample was obtained.
 * @param sampleGuid       A universally unique identifier (UUID) to uniquely identify the biosample across systems.
 */
case class PgpBiosample(
                         id: Option[Int],
                         pgpParticipantId: String,
                         sex: String,
                         sampleGuid: UUID
                       )
