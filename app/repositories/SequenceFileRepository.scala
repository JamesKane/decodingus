package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.genomics.SequenceFile
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait SequenceFileRepository {
  /**
   * Creates a new sequence file record.
   *
   * @param file the sequence file to create
   * @return a future containing the created sequence file with its assigned ID
   */
  def create(file: SequenceFile): Future[SequenceFile]

  /**
   * Retrieves a sequence file by its ID.
   *
   * @param id the unique identifier of the sequence file
   * @return a future containing an optional sequence file if found
   */
  def findById(id: Int): Future[Option[SequenceFile]]

  /**
   * Updates an existing sequence file.
   *
   * @param file the sequence file with updated fields
   * @return a future containing true if update was successful
   */
  def update(file: SequenceFile): Future[Boolean]

  /**
   * Deletes a sequence file.
   *
   * @param id the ID of the sequence file to delete
   * @return a future containing true if deletion was successful
   */
  def delete(id: Int): Future[Boolean]

  /**
   * Finds all sequence files associated with a library.
   *
   * @param libraryId the ID of the sequence library
   * @return a future containing a sequence of files
   */
  def findByLibraryId(libraryId: Int): Future[Seq[SequenceFile]]

  /**
   * Deletes all sequence files associated with a library.
   *
   * @param libraryId the ID of the sequence library
   * @return a future containing the number of deleted files
   */
  def deleteByLibraryId(libraryId: Int): Future[Int]
}

@Singleton
class SequenceFileRepositoryImpl @Inject()(
                                            override protected val dbConfigProvider: DatabaseConfigProvider
                                          )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SequenceFileRepository {

  import models.dal.MyPostgresProfile.api.*

  private val sequenceFiles = DatabaseSchema.domain.genomics.sequenceFiles

  override def create(file: SequenceFile): Future[SequenceFile] = {
    val insertQuery = (sequenceFiles returning sequenceFiles.map(_.id)
      into ((f, id) => f.copy(id = Some(id))))
      .+=(file)

    db.run(insertQuery.transactionally)
  }

  override def findById(id: Int): Future[Option[SequenceFile]] = {
    db.run(sequenceFiles.filter(_.id === id).result.headOption)
  }

  override def update(file: SequenceFile): Future[Boolean] = {
    file.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updateQuery = sequenceFiles
          .filter(_.id === id)
          .map(f => (
            f.libraryId,
            f.fileName,
            f.fileSizeBytes,
            f.fileFormat,
            f.aligner,
            f.targetReference,
            f.updatedAt
          ))
          .update((
            file.libraryId,
            file.fileName,
            file.fileSizeBytes,
            file.fileFormat,
            file.aligner,
            file.targetReference,
            Some(LocalDateTime.now())
          ))

        db.run(updateQuery.transactionally.map(_ > 0))
    }
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(sequenceFiles.filter(_.id === id).delete.map(_ > 0))
  }

  override def deleteByLibraryId(libraryId: Int): Future[Int] = {
    db.run(sequenceFiles.filter(_.libraryId === libraryId).delete)
  }

  override def findByLibraryId(libraryId: Int): Future[Seq[SequenceFile]] = {
    db.run(sequenceFiles.filter(_.libraryId === libraryId).result)
  }
}