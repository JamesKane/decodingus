package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{Biosample, BiosampleType}
import repositories.*

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PgpBiosampleService @Inject()(
                                     biosampleRepository: BiosampleRepository,
                                     accessionGenerator: AccessionNumberGenerator
                                   )(implicit ec: ExecutionContext) {

  /**
   * Creates a PGP biosample with associated metadata and data.
   *
   * @param participantId The PGP participant identifier
   * @param sampleDid     The citizen biosample DID (Decentralized Identifier)
   * @param description   Sample description
   * @param centerName    Name of the center/institution
   * @param coordinates   Optional tuple of (latitude, longitude) for sample origin
   * @return Future containing the UUID of the created biosample
   */
  def createPgpBiosample(
                          participantId: String,
                          sampleDid: String,
                          description: String,
                          centerName: String,
                          sex: Option[String] = None
                        ): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    val metadata = AccessionMetadata(
      pgpParticipantId = Some(participantId),
      citizenBiosampleDid = Some(sampleDid)
    )

    for {
      accession <- accessionGenerator.generateAccession(BiosampleType.PGP, metadata)
      biosample <- biosampleRepository.create(Biosample(
        id = None,
        sampleType = BiosampleType.PGP,
        sampleGuid = sampleGuid,
        sampleAccession = accession,
        description = description,
        alias = None,
        centerName = centerName,
        sex = sex,
        geocoord = None,
        specimenDonorId = None,
        pgpParticipantId = Some(participantId),
        citizenBiosampleDid = Some(sampleDid),
        sourcePlatform = Some("PGP"),
        dateRangeStart = None,
        dateRangeEnd = None
      ))
    } yield sampleGuid
  }
}