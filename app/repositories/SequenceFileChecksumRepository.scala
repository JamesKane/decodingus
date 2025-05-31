package repositories

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.SequenceFileChecksum
import models.dal.{DatabaseSchema, MyPostgresProfile}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait SequenceFileChecksumRepository {
  /**
   * Creates a new checksum record.
   *
   * @param checksum the checksum to create
   * @return a future containing the created checksum with its assigned ID
   */
  def create(checksum: SequenceFileChecksum): Future[SequenceFileChecksum]

  /**
   * Creates multiple checksum records.
   *
   * @param checksums sequence of checksums to create
   * @return a future containing the created checksums with their assigned IDs
   */
  def createMany(checksums: Seq[SequenceFileChecksum]): Future[Seq[SequenceFileChecksum]]

  /**
   * Retrieves all checksums for a sequence file.
   *
   * @param fileId the ID of the sequence file
   * @return a future containing a sequence of checksums
   */
  def findByFileId(fileId: Int): Future[Seq[SequenceFileChecksum]]

  /**
   * Updates an existing checksum.
   *
   * @param checksum the checksum with updated fields
   * @return a future containing true if update was successful
   */
  def update(checksum: SequenceFileChecksum): Future[Boolean]

  /**
   * Deletes all checksums for a sequence file.
   *
   * @param fileId the ID of the sequence file
   * @return a future containing the number of deleted checksums
   */
  def deleteByFileId(fileId: Int): Future[Int]

  /**
   * Finds a sequence file by its checksum value and algorithm.
   *
   * @param value the checksum value to search for
   * @param algorithm the algorithm used to generate the checksum
   * @return a future containing an optional checksum if found
   */
  def findByValueAndAlgorithm(value: String, algorithm: String): Future[Option[SequenceFileChecksum]]
}

@Singleton
class SequenceFileChecksumRepositoryImpl @Inject()(
                                                    override protected val dbConfigProvider: DatabaseConfigProvider
                                                  )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SequenceFileChecksumRepository {

  import models.dal.MyPostgresProfile.api._

  private val checksums = DatabaseSchema.domain.genomics.sequenceFileChecksums

  override def create(checksum: SequenceFileChecksum): Future[SequenceFileChecksum] = {
    val insertQuery = (checksums returning checksums.map(_.id)
      into ((c, id) => c.copy(id = Some(id))))
      .+=(checksum)

    db.run(insertQuery.transactionally)
  }

  override def createMany(checksums: Seq[SequenceFileChecksum]): Future[Seq[SequenceFileChecksum]] = {
    if (checksums.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val insertQuery = (this.checksums returning this.checksums.map(_.id)
        into ((c, id) => c.copy(id = Some(id))))
        .++=(checksums)

      db.run(insertQuery.transactionally)
    }
  }

  override def findByFileId(fileId: Int): Future[Seq[SequenceFileChecksum]] = {
    db.run(checksums.filter(_.sequenceFileId === fileId).result)
  }

  def update(checksum: SequenceFileChecksum): Future[Boolean] = {
    checksum.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updateQuery = checksums
          .filter(_.id === id)
          .map(c => c)
          .update(checksum)

        db.run(updateQuery.transactionally.map(_ > 0))
    }
  }

  override def deleteByFileId(fileId: Int): Future[Int] = {
    db.run(checksums.filter(_.sequenceFileId === fileId).delete)
  }

  override def findByValueAndAlgorithm(value: String, algorithm: String): Future[Option[SequenceFileChecksum]] = {
    db.run(
      checksums
        .filter(c => c.checksum === value && c.algorithm === algorithm)
        .result
        .headOption
    )
  }
}