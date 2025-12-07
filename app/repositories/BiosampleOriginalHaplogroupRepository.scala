package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.publications.BiosampleOriginalHaplogroup
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for managing biosample original haplogroup data.
 * This trait defines the contract for interacting with haplogroup assignments
 * as they were originally reported in publications.
 */
trait BiosampleOriginalHaplogroupRepository {
  /**
   * Retrieves all original haplogroup assignments for a specific biosample.
   *
   * @param biosampleId the unique identifier of the biosample
   * @return a future containing a sequence of original haplogroup assignments
   */
  def findByBiosampleId(biosampleId: Int): Future[Seq[BiosampleOriginalHaplogroup]]

  /**
   * Retrieves all original haplogroup assignments reported in a specific publication.
   *
   * @param publicationId the unique identifier of the publication
   * @return a future containing a sequence of original haplogroup assignments
   */
  def findByPublicationId(publicationId: Int): Future[Seq[BiosampleOriginalHaplogroup]]

  /**
   * Creates a new original haplogroup assignment record.
   *
   * @param haplogroup the haplogroup assignment to create
   * @return a future containing the created haplogroup assignment with its ID
   */
  def create(haplogroup: BiosampleOriginalHaplogroup): Future[BiosampleOriginalHaplogroup]

  /**
   * Updates an existing original haplogroup assignment.
   *
   * @param haplogroup the haplogroup assignment with updated information
   * @return a future containing true if the update was successful
   */
  def update(haplogroup: BiosampleOriginalHaplogroup): Future[Boolean]

  /**
   * Deletes a specific original haplogroup assignment.
   *
   * @param id the unique identifier of the haplogroup assignment to delete
   * @return a future containing true if the deletion was successful
   */
  def delete(id: Int): Future[Boolean]

  /**
   * Retrieves the original haplogroup assignment for a specific biosample-publication pair.
   *
   * @param biosampleId   the unique identifier of the biosample
   * @param publicationId the unique identifier of the publication
   * @return a future containing an optional haplogroup assignment
   */
  def findByBiosampleAndPublication(biosampleId: Int, publicationId: Int): Future[Option[BiosampleOriginalHaplogroup]]

  /**
   * Deletes all `BiosampleOriginalHaplogroup` entries associated with the specified biosample ID.
   *
   * @param biosampleId The unique identifier of the biosample for which associated entries are to be deleted.
   * @return A `Future` containing the number of deleted rows.
   */
  def deleteByBiosampleId(biosampleId: Int): Future[Int]
}

@Singleton
class BiosampleOriginalHaplogroupRepositoryImpl @Inject()(
                                                           protected val dbConfigProvider: DatabaseConfigProvider
                                                         )(implicit ec: ExecutionContext)
  extends BiosampleOriginalHaplogroupRepository
    with HasDatabaseConfigProvider[JdbcProfile] {

  private val haplogroups = DatabaseSchema.domain.publications.biosampleOriginalHaplogroups

  override def findByBiosampleId(biosampleId: Int): Future[Seq[BiosampleOriginalHaplogroup]] = {
    db.run(haplogroups.filter(_.biosampleId === biosampleId).result)
  }

  override def findByPublicationId(publicationId: Int): Future[Seq[BiosampleOriginalHaplogroup]] = {
    db.run(haplogroups.filter(_.publicationId === publicationId).result)
  }

  override def create(haplogroup: BiosampleOriginalHaplogroup): Future[BiosampleOriginalHaplogroup] = {
    db.run(
      (haplogroups returning haplogroups.map(_.id)
        into ((hg, id) => hg.copy(id = Some(id)))) += haplogroup
    )
  }

  override def update(haplogroup: BiosampleOriginalHaplogroup): Future[Boolean] = {
    haplogroup.id match {
      case None => Future.successful(false)
      case Some(id) =>
        db.run(
          haplogroups
            .filter(_.id === id)
            .map(h => (h.originalYHaplogroup, h.originalMtHaplogroup, h.notes))
            .update((haplogroup.originalYHaplogroup, haplogroup.originalMtHaplogroup, haplogroup.notes))
            .map(_ > 0)
        )
    }
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(haplogroups.filter(_.id === id).delete.map(_ > 0))
  }

  override def deleteByBiosampleId(biosampleId: Int): Future[Int] = {
    db.run(haplogroups.filter(_.biosampleId === biosampleId).delete)
  }

  override def findByBiosampleAndPublication(
                                              biosampleId: Int,
                                              publicationId: Int
                                            ): Future[Option[BiosampleOriginalHaplogroup]] = {
    db.run(
      haplogroups
        .filter(h => h.biosampleId === biosampleId && h.publicationId === publicationId)
        .result
        .headOption
    )
  }
}