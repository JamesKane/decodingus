package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.MyPostgresProfile.api.*
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.genomics.CitizenBiosample
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait CitizenBiosampleRepository {
  def create(biosample: CitizenBiosample): Future[CitizenBiosample]

  def findByGuid(guid: UUID): Future[Option[CitizenBiosample]]

  def findByAtUri(atUri: String): Future[Option[CitizenBiosample]]

  def findByAccession(accession: String): Future[Option[CitizenBiosample]]

  /**
   * Updates the biosample.
   *
   * @param biosample     The biosample with new values.
   * @param expectedAtCid The atCid expected to be currently in the database for this record.
   * @return Future[Boolean] true if update succeeded, false otherwise (e.g. record not found or atCid mismatch).
   */
  def update(biosample: CitizenBiosample, expectedAtCid: Option[String]): Future[Boolean]

  def softDelete(guid: UUID): Future[Boolean]

  def softDeleteByAtUri(atUri: String): Future[Boolean]
}

@Singleton
class CitizenBiosampleRepositoryImpl @Inject()(
                                                protected val dbConfigProvider: DatabaseConfigProvider
                                              )(implicit ec: ExecutionContext) extends CitizenBiosampleRepository with HasDatabaseConfigProvider[MyPostgresProfile] {

  private val citizenBiosamples = DatabaseSchema.domain.genomics.citizenBiosamples

  override def create(biosample: CitizenBiosample): Future[CitizenBiosample] = {
    val insertQuery = (citizenBiosamples returning citizenBiosamples.map(_.id)
      into ((bs, id) => bs.copy(id = Some(id)))) += biosample
    db.run(insertQuery)
  }

  override def findByGuid(guid: UUID): Future[Option[CitizenBiosample]] = {
    db.run(citizenBiosamples.filter(b => b.sampleGuid === guid && !b.deleted).result.headOption)
  }

  override def findByAtUri(atUri: String): Future[Option[CitizenBiosample]] = {
    db.run(citizenBiosamples.filter(b => b.atUri === atUri && !b.deleted).result.headOption)
  }

  override def findByAccession(accession: String): Future[Option[CitizenBiosample]] = {
    db.run(citizenBiosamples.filter(b => b.accession === accession && !b.deleted).result.headOption)
  }

  override def update(biosample: CitizenBiosample, expectedAtCid: Option[String]): Future[Boolean] = {
    val query = citizenBiosamples.filter { b =>
      b.sampleGuid === biosample.sampleGuid &&
        b.atCid === expectedAtCid
    }

    val updateAction = query.map(b => (
      b.atUri,
      b.accession,
      b.alias,
      b.sourcePlatform,
      b.collectionDate,
      b.sex,
      b.geocoord,
      b.description,
      b.yHaplogroup,
      b.mtHaplogroup,
      b.atCid,
      b.updatedAt,
      b.deleted
    )).update((
      biosample.atUri,
      biosample.accession,
      biosample.alias,
      biosample.sourcePlatform,
      biosample.collectionDate,
      biosample.sex,
      biosample.geocoord,
      biosample.description,
      biosample.yHaplogroup,
      biosample.mtHaplogroup,
      biosample.atCid,
      LocalDateTime.now(),
      biosample.deleted
    ))

    db.run(updateAction.map(_ > 0))
  }

  override def softDelete(guid: UUID): Future[Boolean] = {
    val q = citizenBiosamples.filter(_.sampleGuid === guid)
      .map(b => (b.deleted, b.updatedAt))
      .update((true, LocalDateTime.now()))
    db.run(q.map(_ > 0))
  }

  override def softDeleteByAtUri(atUri: String): Future[Boolean] = {
    val q = citizenBiosamples.filter(_.atUri === atUri)
      .map(b => (b.deleted, b.updatedAt))
      .update((true, LocalDateTime.now()))
    db.run(q.map(_ > 0))
  }
}
