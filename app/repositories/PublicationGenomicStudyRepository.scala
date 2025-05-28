package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.publications.PublicationGenomicStudy
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}


/**
 * Trait that defines the repository interface for managing associations between publications 
 * and genomic studies (ENA studies).
 *
 * This repository provides abstraction for common CRUD operations and queries specifically related 
 * to the `PublicationGenomicStudy` model.
 *
 * Operations include creating, updating, deleting, and retrieving relationships between publications 
 * and their associated genomic studies.
 */
trait PublicationGenomicStudyRepository {
  /**
   * Creates a new association between a publication and a genomic study.
   *
   * The method persists a `PublicationGenomicStudy` instance in the repository and returns
   * the created instance wrapped in a `Future`. This association enables linking a 
   * specific publication to its corresponding ENA study.
   *
   * @param link The `PublicationGenomicStudy` instance representing the relationship to be created.
   *             It contains the unique identifiers for both the publication (`publicationId`) 
   *             and the ENA study (`studyId`).
   * @return A `Future` containing the created `PublicationGenomicStudy` instance, as confirmed 
   *         by the repository after a successful operation.
   */
  def create(link: PublicationGenomicStudy): Future[PublicationGenomicStudy]

  /**
   * Retrieves a sequence of genomic study associations for a specific publication.
   *
   * This method queries the repository to find all instances of `PublicationGenomicStudy`
   * associated with the given publication identifier (`publicationId`).
   *
   * @param publicationId The unique identifier of the publication for which associated genomic studies are to be retrieved.
   * @return A `Future` containing a sequence of `PublicationGenomicStudy` instances, representing the associations found for the specified publication.
   */
  def findByPublicationId(publicationId: Int): Future[Seq[PublicationGenomicStudy]]

  /**
   * Retrieves a sequence of publication-genomic study associations for a specific ENA study.
   *
   * This method queries the repository to find all `PublicationGenomicStudy` instances
   * associated with the given ENA study identifier (`enaStudyId`). This is useful for
   * obtaining all publications linked to a specific ENA study.
   *
   * @param enaStudyId The unique identifier of the ENA study for which associated 
   *                   `PublicationGenomicStudy` records are to be retrieved.
   * @return A `Future` containing a sequence of `PublicationGenomicStudy` instances 
   *         representing the associations found for the specified ENA study.
   */
  def findByEnaStudyId(enaStudyId: Int): Future[Seq[PublicationGenomicStudy]]

  /**
   * Updates the given genomic study publication in the system.
   *
   * @param link the `PublicationGenomicStudy` entity to be updated
   * @return a `Future` containing an `Option` of the updated `PublicationGenomicStudy` if successful, or `None` if no updates were made
   */
  def update(link: PublicationGenomicStudy): Future[Option[PublicationGenomicStudy]]

  /**
   * Deletes the association between a publication and an ENA study.
   *
   * This method removes the specified `PublicationGenomicStudy` record
   * from the repository based on the provided `publicationId` and `enaStudyId`.
   *
   * @param publicationId The unique identifier of the publication to be unlinked.
   * @param enaStudyId    The unique identifier of the ENA study to be unlinked.
   * @return A `Future` containing the number of rows affected by the deletion.
   */
  def delete(publicationId: Int, enaStudyId: Int): Future[Int]

  /**
   * Checks if an association between a publication and an ENA study exists.
   *
   * The method verifies the presence of a `PublicationGenomicStudy` record
   * in the repository for the specified publication and ENA study identifiers.
   *
   * @param publicationId The unique identifier of the publication to check.
   * @param enaStudyId    The unique identifier of the ENA study to check.
   * @return A `Future` containing `true` if the association exists, or `false` otherwise.
   */
  def exists(publicationId: Int, enaStudyId: Int): Future[Boolean]

  /**
   * Retrieves all associations between publications and genomic studies.
   *
   * This method fetches all `PublicationGenomicStudy` records stored in the repository.
   * It is useful for obtaining a complete list of publication-genomic study relationships.
   *
   * @return A `Future` containing a sequence of `PublicationGenomicStudy` instances,
   *         representing all the associations present in the repository.
   */
  def findAll(): Future[Seq[PublicationGenomicStudy]]
}

@Singleton
class PublicationGenomicStudyRepositoryImpl @Inject()(
                                                   dbConfigProvider: DatabaseConfigProvider
                                                 )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PublicationGenomicStudyRepository {

  import models.dal.MyPostgresProfile.api.*

  val publicationGenomicStudies = DatabaseSchema.domain.publications.publicationGenomicStudies

  override def create(link: PublicationGenomicStudy): Future[PublicationGenomicStudy] = {
    runQuery(
      publicationGenomicStudies
        .returning(publicationGenomicStudies)
        .insertOrUpdate(link)
    ).map(_ => link)
  }

  override def findAll(): Future[Seq[PublicationGenomicStudy]] = {
    db.run(publicationGenomicStudies.result)
  }

  override def findByPublicationId(publicationId: Int): Future[Seq[PublicationGenomicStudy]] = {
    db.run(
      publicationGenomicStudies
        .filter(_.publicationId === publicationId)
        .result
    )
  }

  override def findByEnaStudyId(enaStudyId: Int): Future[Seq[PublicationGenomicStudy]] = {
    db.run(
      publicationGenomicStudies
        .filter(_.genomicStudyId === enaStudyId)
        .result
    )
  }

  override def update(link: PublicationGenomicStudy): Future[Option[PublicationGenomicStudy]] = {
    db.run(
      publicationGenomicStudies
        .filter(r =>
          r.publicationId === link.publicationId &&
            r.genomicStudyId === link.studyId
        )
        .update(link)
    ).map {
      case 0 => None // No rows were updated
      case _ => Some(link)
    }
  }

  override def delete(publicationId: Int, enaStudyId: Int): Future[Int] = {
    db.run(
      publicationGenomicStudies
        .filter(r =>
          r.publicationId === publicationId &&
            r.genomicStudyId === enaStudyId
        )
        .delete
    )
  }

  override def exists(publicationId: Int, enaStudyId: Int): Future[Boolean] = {
    db.run(
      publicationGenomicStudies
        .filter(r =>
          r.publicationId === publicationId &&
            r.genomicStudyId === enaStudyId
        )
        .exists
        .result
    )
  }
}


