package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.MetadataSchema
import models.domain.pds.PdsSubmission
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.db.NamedDatabase
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PdsSubmissionRepository {
  def create(submission: PdsSubmission): Future[PdsSubmission]
  def findById(id: Int): Future[Option[PdsSubmission]]
  def findByNode(nodeId: Int, limit: Int = 100): Future[Seq[PdsSubmission]]
  def findByNodeAndType(nodeId: Int, submissionType: String): Future[Seq[PdsSubmission]]
  def findByBiosampleId(biosampleId: Int): Future[Seq[PdsSubmission]]
  def findByBiosampleGuid(guid: UUID): Future[Seq[PdsSubmission]]
  def findByStatus(status: String, limit: Int = 100): Future[Seq[PdsSubmission]]
  def findByTypeAndStatus(submissionType: String, status: String, limit: Int = 100): Future[Seq[PdsSubmission]]
  def updateStatus(id: Int, status: String, reviewedBy: Option[String], reviewNotes: Option[String]): Future[Boolean]
  def countByNodeAndStatus(nodeId: Int): Future[Map[String, Int]]
  def countByStatus(): Future[Map[String, Int]]
}

@Singleton
class PdsSubmissionRepositoryImpl @Inject()(
  @NamedDatabase("metadata") protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with PdsSubmissionRepository
    with Logging {

  import profile.api.*
  import models.dal.MyPostgresProfile.api.playJsonTypeMapper

  private val submissions = MetadataSchema.pdsSubmissions

  override def create(submission: PdsSubmission): Future[PdsSubmission] =
    db.run((submissions returning submissions.map(_.id) into ((s, id) => s.copy(id = Some(id)))) += submission)

  override def findById(id: Int): Future[Option[PdsSubmission]] =
    db.run(submissions.filter(_.id === id).result.headOption)

  override def findByNode(nodeId: Int, limit: Int): Future[Seq[PdsSubmission]] =
    db.run(submissions.filter(_.pdsNodeId === nodeId).sortBy(_.createdAt.desc).take(limit).result)

  override def findByNodeAndType(nodeId: Int, submissionType: String): Future[Seq[PdsSubmission]] =
    db.run(submissions.filter(s => s.pdsNodeId === nodeId && s.submissionType === submissionType)
      .sortBy(_.createdAt.desc).result)

  override def findByBiosampleId(biosampleId: Int): Future[Seq[PdsSubmission]] =
    db.run(submissions.filter(_.biosampleId === biosampleId).sortBy(_.createdAt.desc).result)

  override def findByBiosampleGuid(guid: UUID): Future[Seq[PdsSubmission]] =
    db.run(submissions.filter(_.biosampleGuid === guid).sortBy(_.createdAt.desc).result)

  override def findByStatus(status: String, limit: Int): Future[Seq[PdsSubmission]] =
    db.run(submissions.filter(_.status === status).sortBy(_.createdAt.desc).take(limit).result)

  override def findByTypeAndStatus(submissionType: String, status: String, limit: Int): Future[Seq[PdsSubmission]] =
    db.run(submissions.filter(s => s.submissionType === submissionType && s.status === status)
      .sortBy(_.createdAt.desc).take(limit).result)

  override def updateStatus(id: Int, status: String, reviewedBy: Option[String], reviewNotes: Option[String]): Future[Boolean] =
    db.run(
      submissions.filter(_.id === id)
        .map(s => (s.status, s.reviewedBy, s.reviewedAt, s.reviewNotes))
        .update((status, reviewedBy, Some(LocalDateTime.now()), reviewNotes))
    ).map(_ > 0)

  override def countByNodeAndStatus(nodeId: Int): Future[Map[String, Int]] =
    db.run(
      submissions.filter(_.pdsNodeId === nodeId)
        .groupBy(_.status).map { case (status, group) => (status, group.length) }
        .result
    ).map(_.toMap)

  override def countByStatus(): Future[Map[String, Int]] =
    db.run(
      submissions.groupBy(_.status).map { case (status, group) => (status, group.length) }.result
    ).map(_.toMap)
}
