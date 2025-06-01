package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.BiosampleType
import org.hashids.Hashids
import play.api.Configuration
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api._

import scala.concurrent.{ExecutionContext, Future}

case class AccessionMetadata(
                              pgpParticipantId: Option[String] = None,
                              citizenBiosampleDid: Option[String] = None,
                              existingAccession: Option[String] = None
                            )

trait AccessionNumberGenerator {
  def generateAccession(biosampleType: BiosampleType, metadata: AccessionMetadata): Future[String]
  def decodeAccession(accession: String): Future[Option[Long]] // Added for reverse lookup
}

@Singleton
class BiosampleAccessionGenerator @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider,
                                             config: Configuration
                                           )(implicit ec: ExecutionContext)
  extends AccessionNumberGenerator
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  private val DuPrefix = "DU"
  private val alphabet = "ABCDEFGHIJKLMNPQRSTUVWXYZ1234567890" // Removed O to avoid confusion with 0
  private val salt = config.get[String]("biosample.hash.salt")
  private val hashLength = 6 // Gives us plenty of combinations while keeping it readable

  private lazy val hasher = new Hashids(salt, hashLength, alphabet)

  private def getNextCitizenSequence(): Future[Long] = {
    val query = sql"SELECT nextval('citizen_biosample_seq')".as[Long]
    db.run(query).map(_.head)
  }

  override def decodeAccession(accession: String): Future[Option[Long]] = Future {
    if (accession.startsWith(DuPrefix)) {
      val hash = accession.substring(DuPrefix.length)
      try {
        val decoded = hasher.decode(hash)
        if (decoded.isEmpty) None else Some(decoded.head)
      } catch {
        case _: Exception => None
      }
    } else None
  }

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
        getNextCitizenSequence().map { seq =>
          s"$DuPrefix-${hasher.encode(seq)}"
        }
    }
  }
}