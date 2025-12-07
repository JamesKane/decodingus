package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.genomics.SequenceLibrary
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait SequenceLibraryRepository {
  /**
   * Creates a new sequence library record.
   *
   * @param library the sequence library to create
   * @return a future containing the created sequence library with its assigned ID
   */
  def create(library: SequenceLibrary): Future[SequenceLibrary]

  /**
   * Retrieves a sequence library by its ID.
   *
   * @param id the unique identifier of the sequence library
   * @return a future containing an optional sequence library if found
   */
  def findById(id: Int): Future[Option[SequenceLibrary]]

  /**
   * Retrieves all sequence libraries for a given sample.
   *
   * @param sampleGuid the UUID of the sample
   * @return a future containing a sequence of sequence libraries
   */
  def findBySampleGuid(sampleGuid: UUID): Future[Seq[SequenceLibrary]]

  /**
   * Updates an existing sequence library.
   *
   * @param library the sequence library with updated fields
   * @return a future containing true if update was successful
   */
  def update(library: SequenceLibrary): Future[Boolean]

  /**
   * Deletes a sequence library by its ID.
   *
   * @param id the ID of the sequence library to delete
   * @return a future containing true if deletion was successful
   */
  def delete(id: Int): Future[Boolean]

  /**
   * Finds all sequence libraries created within a date range.
   *
   * @param start start date-time
   * @param end   end date-time
   * @return a future containing a sequence of matching libraries
   */
  def findByDateRange(start: LocalDateTime, end: LocalDateTime): Future[Seq[SequenceLibrary]]

  def findByAtUri(atUri: String): Future[Option[SequenceLibrary]]

  def deleteByAtUri(atUri: String): Future[Boolean]
}

@Singleton
class SequenceLibraryRepositoryImpl @Inject()(
                                               override protected val dbConfigProvider: DatabaseConfigProvider
                                             )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SequenceLibraryRepository {

  import models.dal.MyPostgresProfile.api.*

  private val sequenceLibraries = DatabaseSchema.domain.genomics.sequenceLibraries

  override def create(library: SequenceLibrary): Future[SequenceLibrary] = {
    val insertQuery = (sequenceLibraries returning sequenceLibraries.map(_.id)
      into ((lib, id) => lib.copy(id = Some(id))))
      .+=(library)

    db.run(insertQuery.transactionally)
  }

  override def findById(id: Int): Future[Option[SequenceLibrary]] = {
    db.run(sequenceLibraries.filter(_.id === id).result.headOption)
  }

  override def findByAtUri(atUri: String): Future[Option[SequenceLibrary]] = {
    db.run(sequenceLibraries.filter(_.atUri === atUri).result.headOption)
  }

  override def findBySampleGuid(sampleGuid: UUID): Future[Seq[SequenceLibrary]] = {
    db.run(sequenceLibraries.filter(_.sampleGuid === sampleGuid).result)
  }

  override def update(library: SequenceLibrary): Future[Boolean] = {
    library.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updateQuery = sequenceLibraries
          .filter(_.id === id)
          .map(l => (
            l.sampleGuid,
            l.lab,
            l.testType,
            l.runDate,
            l.instrument,
            l.reads,
            l.readLength,
            l.pairedEnd,
            l.insertSize,
            l.atUri,
            l.atCid,
            l.updatedAt
          ))
          .update((
            library.sampleGuid,
            library.lab,
            library.testType,
            library.runDate,
            library.instrument,
            library.reads,
            library.readLength,
            library.pairedEnd,
            library.insertSize,
            library.atUri,
            library.atCid,
            Some(LocalDateTime.now())
          ))

        db.run(updateQuery.transactionally.map(_ > 0))
    }
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(sequenceLibraries.filter(_.id === id).delete.map(_ > 0))
  }

  override def deleteByAtUri(atUri: String): Future[Boolean] = {
    db.run(sequenceLibraries.filter(_.atUri === atUri).delete.map(_ > 0))
  }

  override def findByDateRange(start: LocalDateTime, end: LocalDateTime): Future[Seq[SequenceLibrary]] = {
    db.run(
      sequenceLibraries
        .filter(l => l.runDate >= start && l.runDate <= end)
        .sortBy(_.runDate.desc)
        .result
    )
  }
}