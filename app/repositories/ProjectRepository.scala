package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.MyPostgresProfile.api.*
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.Project
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait ProjectRepository {
  def create(project: Project): Future[Project]

  def findByProjectGuid(projectGuid: UUID): Future[Option[Project]]

  def findByAtUri(atUri: String): Future[Option[Project]]

  def update(project: Project, expectedAtCid: Option[String]): Future[Boolean]

  def softDelete(projectGuid: UUID): Future[Boolean]

  def softDeleteByAtUri(atUri: String): Future[Boolean]
}

@Singleton
class ProjectRepositoryImpl @Inject()(
                                       protected val dbConfigProvider: DatabaseConfigProvider
                                     )(implicit ec: ExecutionContext) extends ProjectRepository with HasDatabaseConfigProvider[MyPostgresProfile] {

  private val projects = DatabaseSchema.domain.project.projects

  override def create(project: Project): Future[Project] = {
    val insertQuery = (projects returning projects.map(_.id)
      into ((p, id) => p.copy(id = Some(id)))) += project
    db.run(insertQuery)
  }

  override def findByProjectGuid(projectGuid: UUID): Future[Option[Project]] = {
    db.run(projects.filter(p => p.projectGuid === projectGuid && !p.deleted).result.headOption)
  }

  override def findByAtUri(atUri: String): Future[Option[Project]] = {
    db.run(projects.filter(p => p.atUri === atUri && !p.deleted).result.headOption)
  }

  override def update(project: Project, expectedAtCid: Option[String]): Future[Boolean] = {
    val query = projects.filter { p =>
      p.projectGuid === project.projectGuid &&
        p.atCid === expectedAtCid
    }

    val updateAction = query.map(p => (
      p.name,
      p.description,
      p.ownerDid,
      p.atUri,
      p.atCid,
      p.updatedAt,
      p.deleted
    )).update((
      project.name,
      project.description,
      project.ownerDid,
      project.atUri,
      project.atCid,
      LocalDateTime.now(),
      project.deleted
    ))

    db.run(updateAction.map(_ > 0))
  }

  override def softDelete(projectGuid: UUID): Future[Boolean] = {
    val q = projects.filter(_.projectGuid === projectGuid)
      .map(p => (p.deleted, p.updatedAt))
      .update((true, LocalDateTime.now()))
    db.run(q.map(_ > 0))
  }

  override def softDeleteByAtUri(atUri: String): Future[Boolean] = {
    val q = projects.filter(_.atUri === atUri)
      .map(p => (p.deleted, p.updatedAt))
      .update((true, LocalDateTime.now()))
    db.run(q.map(_ > 0))
  }
}
