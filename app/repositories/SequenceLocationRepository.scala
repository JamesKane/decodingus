package repositories

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{SequenceAtpLocation, SequenceHttpLocation}
import models.dal.{DatabaseSchema, MyPostgresProfile}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait SequenceLocationRepository[T] {
  /**
   * Creates a new location record.
   *
   * @param location the location to create
   * @return a future containing the created location with its assigned ID
   */
  def create(location: T): Future[T]

  /**
   * Creates multiple location records.
   *
   * @param locations sequence of locations to create
   * @return a future containing the created locations with their assigned IDs
   */
  def createMany(locations: Seq[T]): Future[Seq[T]]

  /**
   * Retrieves all locations for a sequence file.
   *
   * @param fileId the ID of the sequence file
   * @return a future containing a sequence of locations
   */
  def findByFileId(fileId: Int): Future[Seq[T]]

  /**
   * Updates an existing location.
   *
   * @param location the location with updated fields
   * @return a future containing true if update was successful
   */
  def update(location: T): Future[Boolean]

  /**
   * Deletes all locations for a sequence file.
   *
   * @param fileId the ID of the sequence file
   * @return a future containing the number of deleted locations
   */
  def deleteByFileId(fileId: Int): Future[Int]
}

@Singleton
class SequenceHttpLocationRepositoryImpl @Inject()(
                                                    override protected val dbConfigProvider: DatabaseConfigProvider
                                                  )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SequenceLocationRepository[SequenceHttpLocation] {

  import models.dal.MyPostgresProfile.api._

  private val httpLocations = DatabaseSchema.domain.genomics.sequenceHttpLocations

  override def create(location: SequenceHttpLocation): Future[SequenceHttpLocation] = {
    val insertQuery = (httpLocations returning httpLocations.map(_.id)
      into ((loc, id) => loc.copy(id = Some(id))))
      .+=(location)

    db.run(insertQuery.transactionally)
  }

  override def createMany(locations: Seq[SequenceHttpLocation]): Future[Seq[SequenceHttpLocation]] = {
    if (locations.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val insertQuery = (httpLocations returning httpLocations.map(_.id)
        into ((loc, id) => loc.copy(id = Some(id))))
        .++=(locations)

      db.run(insertQuery.transactionally)
    }
  }

  override def findByFileId(fileId: Int): Future[Seq[SequenceHttpLocation]] = {
    db.run(httpLocations.filter(_.sequenceFileId === fileId).result)
  }

  override def update(location: SequenceHttpLocation): Future[Boolean] = {
    location.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updateQuery = httpLocations
          .filter(_.id === id)
          .map(l => (l.fileUrl, l.fileIndexUrl))
          .update((location.fileUrl, location.fileIndexUrl))

        db.run(updateQuery.transactionally.map(_ > 0))
    }
  }

  override def deleteByFileId(fileId: Int): Future[Int] = {
    db.run(httpLocations.filter(_.sequenceFileId === fileId).delete)
  }
}

@Singleton
class SequenceAtpLocationRepositoryImpl @Inject()(
                                                   override protected val dbConfigProvider: DatabaseConfigProvider
                                                 )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SequenceLocationRepository[SequenceAtpLocation] {

  import models.dal.MyPostgresProfile.api._

  private val atpLocations = DatabaseSchema.domain.genomics.sequenceAtpLocations

  override def create(location: SequenceAtpLocation): Future[SequenceAtpLocation] = {
    val insertQuery = (atpLocations returning atpLocations.map(_.id)
      into ((loc, id) => loc.copy(id = Some(id))))
      .+=(location)

    db.run(insertQuery.transactionally)
  }

  override def createMany(locations: Seq[SequenceAtpLocation]): Future[Seq[SequenceAtpLocation]] = {
    if (locations.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val insertQuery = (atpLocations returning atpLocations.map(_.id)
        into ((loc, id) => loc.copy(id = Some(id))))
        .++=(locations)

      db.run(insertQuery.transactionally)
    }
  }

  override def findByFileId(fileId: Int): Future[Seq[SequenceAtpLocation]] = {
    db.run(atpLocations.filter(_.sequenceFileId === fileId).result)
  }

  override def update(location: SequenceAtpLocation): Future[Boolean] = {
    location.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updateQuery = atpLocations
          .filter(_.id === id)
          .map(l => (l.repoDid, l.recordCid, l.indexCid))
          .update((location.repoDID, location.recordCID, location.indexCID))

        db.run(updateQuery.transactionally.map(_ > 0))
    }
  }

  override def deleteByFileId(fileId: Int): Future[Int] = {
    db.run(atpLocations.filter(_.sequenceFileId === fileId).delete)
  }
}