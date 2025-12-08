package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.domain.publications.PublicationCandidatesTable
import models.domain.publications.PublicationCandidate
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PublicationCandidateRepository {
  def create(candidate: PublicationCandidate): Future[PublicationCandidate]
  def findById(id: Int): Future[Option[PublicationCandidate]]
  def findByOpenAlexId(id: String): Future[Option[PublicationCandidate]]
  def listPending(page: Int, pageSize: Int): Future[(Seq[PublicationCandidate], Long)]
  def updateStatus(id: Int, status: String, reviewedBy: Option[UUID], reason: Option[String]): Future[Boolean]
  def bulkReject(ids: Seq[Int], reason: String, reviewedBy: UUID): Future[Int]
  def saveCandidates(candidates: Seq[PublicationCandidate]): Future[Seq[PublicationCandidate]]
}

@Singleton
class PublicationCandidateRepositoryImpl @Inject()(
  override protected val dbConfigProvider: DatabaseConfigProvider
)(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PublicationCandidateRepository {

  import models.dal.MyPostgresProfile.api.*

  private val candidatesTable = DatabaseSchema.domain.publications.publicationCandidates

  override def create(candidate: PublicationCandidate): Future[PublicationCandidate] = {
    db.run((candidatesTable returning candidatesTable.map(_.id)
      into ((c, id) => c.copy(id = Some(id)))) += candidate)
  }

  override def findById(id: Int): Future[Option[PublicationCandidate]] = {
    db.run(candidatesTable.filter(_.id === id).result.headOption)
  }

  override def findByOpenAlexId(id: String): Future[Option[PublicationCandidate]] = {
    db.run(candidatesTable.filter(_.openAlexId === id).result.headOption)
  }

  override def listPending(page: Int, pageSize: Int): Future[(Seq[PublicationCandidate], Long)] = {
    val query = candidatesTable.filter(_.status === "pending")
    val totalCountQuery = query.length.result
    
    val pagedQuery = query
      .sortBy(_.relevanceScore.desc.nullsLast)
      .drop((page - 1) * pageSize)
      .take(pageSize)
      .result

    for {
      total <- db.run(totalCountQuery)
      results <- db.run(pagedQuery)
    } yield (results, total.toLong)
  }

  override def updateStatus(id: Int, status: String, reviewedBy: Option[UUID], reason: Option[String]): Future[Boolean] = {
    val updateAction = candidatesTable.filter(_.id === id)
      .map(c => (c.status, c.reviewedBy, c.reviewedAt, c.rejectionReason))
      .update((status, reviewedBy, Some(LocalDateTime.now()), reason))

    db.run(updateAction).map(_ > 0)
  }

  override def bulkReject(ids: Seq[Int], reason: String, reviewedBy: UUID): Future[Int] = {
    val updateAction = candidatesTable.filter(_.id.inSet(ids))
      .map(c => (c.status, c.reviewedBy, c.reviewedAt, c.rejectionReason))
      .update(("rejected", Some(reviewedBy), Some(LocalDateTime.now()), Some(reason)))

    db.run(updateAction)
  }

  override def saveCandidates(candidates: Seq[PublicationCandidate]): Future[Seq[PublicationCandidate]] = {
    // Insert or Ignore (ON CONFLICT DO NOTHING) is standard for this. 
    // But PublicationCandidate.openAlexId is unique.
    // Slick doesn't have a built-in "insertOrIgnoreAll" easily.
    // We can do it one by one or filter existing first.
    // For simplicity, let's filter existing by openAlexId.

    if (candidates.isEmpty) return Future.successful(Seq.empty)

    val openAlexIds = candidates.map(_.openAlexId)
    
    for {
      existingIds <- db.run(candidatesTable.filter(_.openAlexId.inSet(openAlexIds)).map(_.openAlexId).result)
      newCandidates = candidates.filterNot(c => existingIds.contains(c.openAlexId))
      saved <- if (newCandidates.nonEmpty) {
        db.run((candidatesTable returning candidatesTable.map(_.id)
          into ((c, id) => c.copy(id = Some(id)))) ++= newCandidates)
      } else {
        Future.successful(Seq.empty)
      }
    } yield saved
  }
}
