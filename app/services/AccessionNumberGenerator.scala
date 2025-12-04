package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.BiosampleType
import org.hashids.Hashids
import play.api.Configuration
import repositories.CitizenSequenceRepository

import scala.concurrent.{ExecutionContext, Future}

case class AccessionMetadata(
                              pgpParticipantId: Option[String] = None,
                              citizenBiosampleDid: Option[String] = None,
                              existingAccession: Option[String] = None
                            )

/**
 * Trait for generating and decoding accession numbers associated with biological samples.
 * An accession number is a unique identifier assigned to a biosample, which can be
 * generated based on the biosample type and associated metadata and decoded for reverse lookups.
 */
trait AccessionNumberGenerator {
  def generateAccession(biosampleType: BiosampleType, metadata: AccessionMetadata): Future[String]
  def decodeAccession(accession: String): Future[Option[Long]] // Added for reverse lookup
}

/**
 * Singleton class responsible for generating and decoding accession numbers for biosamples.
 * This implementation uses hashing and unique sequence generation to support multiple
 * biosample types including Standard, Ancient, PGP, and Citizen.
 *
 * It extends the `AccessionNumberGenerator` trait and relies on a hashing library 
 * for encoding and decoding accession numbers.
 *
 * @constructor Creates a new BiosampleAccessionGenerator instance.
 * @param sequenceRepo   Repository for fetching sequence values.
 * @param config         Application configuration for loading settings like hashing salt.
 * @param ec             ExecutionContext for managing asynchronous operations.
 */
@Singleton
class BiosampleAccessionGenerator @Inject()(
                                             sequenceRepo: CitizenSequenceRepository,
                                             config: Configuration
                                           )(implicit ec: ExecutionContext)
  extends AccessionNumberGenerator {

  private val DuPrefix = "DU"
  private val alphabet = "ABCDEFGHIJKLMNPQRSTUVWXYZ1234567890" // Removed O to avoid confusion with 0
  private val salt = config.get[String]("biosample.hash.salt")
  private val hashLength = 6 // Gives us plenty of combinations while keeping it readable

  private lazy val hasher = new Hashids(salt, hashLength, alphabet)

  /**
   * Decodes the given accession string to retrieve an optional numeric identifier.
   *
   * @param accession The encoded accession string to be decoded. It is typically
   *                  prefixed with a specific identifier (`DuPrefix`).
   * @return A Future containing an Option of Long. The Option will contain the
   *         decoded numeric identifier if successful, or None if decoding fails
   *         or the input does not follow the expected format.
   */
  override def decodeAccession(accession: String): Future[Option[Long]] = Future {
    if (accession.startsWith(DuPrefix)) {
      val hash = accession.substring(DuPrefix.length)
      try {
        val decoded = hasher.decode(hash)
        decoded.headOption
      } catch {
        case _: Exception => None
      }
    } else None
  }

  /**
   * Generates an accession string based on the specified biosample type and associated metadata.
   *
   * @param biosampleType The type of biosample, which determines the logic for generating the accession.
   *                      Acceptable values are Standard, Ancient, PGP, or Citizen.
   * @param metadata      The metadata required to generate the accession. This may include
   *                      existing accession information, PGP participant ID, or other relevant details
   *                      based on the biosample type.
   * @return A Future containing the generated accession string. If required metadata is missing
   *         or invalid for the specified biosample type, the Future will fail with an exception.
   */
  override def generateAccession(biosampleType: BiosampleType, metadata: AccessionMetadata): Future[String] = {
    biosampleType match {
      case BiosampleType.Standard | BiosampleType.Ancient =>
        metadata.existingAccession match {
          case Some(accession) => Future.successful(accession)
          case None => Future.failed(
            new IllegalArgumentException("ENA/NCBI accession is required for Standard and Ancient samples")
          )
        }

      case BiosampleType.PGP =>
        metadata.pgpParticipantId match {
          case Some(id) => Future.successful(s"PGP-$id")
          case None => Future.failed(
            new IllegalArgumentException("PGP participant ID is required")
          )
        }

      case BiosampleType.Citizen =>
        sequenceRepo.getNextSequence().map { seq =>
          s"$DuPrefix-${hasher.encode(seq)}"
        }
    }
  }
}