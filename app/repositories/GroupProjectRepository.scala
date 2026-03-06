package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.{GroupProject, GroupProjectMember}
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait GroupProjectRepository {
  def create(project: GroupProject): Future[GroupProject]
  def findById(id: Int): Future[Option[GroupProject]]
  def findByGuid(guid: UUID): Future[Option[GroupProject]]
  def findByAtUri(atUri: String): Future[Option[GroupProject]]
  def findByOwner(ownerDid: String): Future[Seq[GroupProject]]
  def findByType(projectType: String): Future[Seq[GroupProject]]
  def findByTargetHaplogroup(haplogroup: String): Future[Seq[GroupProject]]
  def update(project: GroupProject): Future[Boolean]
  def softDelete(id: Int): Future[Boolean]
  def softDeleteByAtUri(atUri: String): Future[Boolean]
}

class GroupProjectRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with GroupProjectRepository
    with Logging {

  import models.dal.MyPostgresProfile.api.*

  private val projects = DatabaseSchema.domain.project.groupProjects

  override def create(project: GroupProject): Future[GroupProject] =
    runQuery(
      (projects returning projects.map(_.id) into ((p, id) => p.copy(id = Some(id)))) += project
    )

  override def findById(id: Int): Future[Option[GroupProject]] =
    runQuery(projects.filter(p => p.id === id && !p.deleted).result.headOption)

  override def findByGuid(guid: UUID): Future[Option[GroupProject]] =
    runQuery(projects.filter(p => p.projectGuid === guid && !p.deleted).result.headOption)

  override def findByAtUri(atUri: String): Future[Option[GroupProject]] =
    runQuery(projects.filter(p => p.atUri === atUri && !p.deleted).result.headOption)

  override def findByOwner(ownerDid: String): Future[Seq[GroupProject]] =
    runQuery(projects.filter(p => p.ownerDid === ownerDid && !p.deleted).result)

  override def findByType(projectType: String): Future[Seq[GroupProject]] =
    runQuery(projects.filter(p => p.projectType === projectType && !p.deleted).result)

  override def findByTargetHaplogroup(haplogroup: String): Future[Seq[GroupProject]] =
    runQuery(projects.filter(p => p.targetHaplogroup === haplogroup && !p.deleted).result)

  override def update(project: GroupProject): Future[Boolean] = project.id match {
    case None => Future.successful(false)
    case Some(id) =>
      runQuery(
        projects.filter(_.id === id)
          .map(p => (p.projectName, p.description, p.backgroundInfo, p.joinPolicy,
            p.haplogroupRequirement, p.memberListVisibility, p.strPolicy, p.snpPolicy,
            p.publicTreeView, p.successionPolicy, p.atCid, p.updatedAt))
          .update((project.projectName, project.description, project.backgroundInfo, project.joinPolicy,
            project.haplogroupRequirement, project.memberListVisibility, project.strPolicy, project.snpPolicy,
            project.publicTreeView, project.successionPolicy, project.atCid, LocalDateTime.now()))
      ).map(_ > 0)
  }

  override def softDelete(id: Int): Future[Boolean] =
    runQuery(
      projects.filter(_.id === id)
        .map(p => (p.deleted, p.updatedAt))
        .update((true, LocalDateTime.now()))
    ).map(_ > 0)

  override def softDeleteByAtUri(atUri: String): Future[Boolean] =
    runQuery(
      projects.filter(_.atUri === atUri)
        .map(p => (p.deleted, p.updatedAt))
        .update((true, LocalDateTime.now()))
    ).map(_ > 0)
}

trait GroupProjectMemberRepository {
  def create(member: GroupProjectMember): Future[GroupProjectMember]
  def findById(id: Int): Future[Option[GroupProjectMember]]
  def findByProjectId(projectId: Int): Future[Seq[GroupProjectMember]]
  def findByProjectAndCitizen(projectId: Int, citizenDid: String): Future[Option[GroupProjectMember]]
  def findByCitizen(citizenDid: String): Future[Seq[GroupProjectMember]]
  def findByProjectAndStatus(projectId: Int, status: String): Future[Seq[GroupProjectMember]]
  def findByProjectAndRole(projectId: Int, role: String): Future[Seq[GroupProjectMember]]
  def findByAtUri(atUri: String): Future[Option[GroupProjectMember]]
  def update(member: GroupProjectMember): Future[Boolean]
  def updateStatus(id: Int, status: String): Future[Boolean]
  def updateRole(id: Int, role: String): Future[Boolean]
  def countByProjectAndStatus(projectId: Int, status: String): Future[Int]
  def countActiveByProject(projectId: Int): Future[Int]
}

class GroupProjectMemberRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with GroupProjectMemberRepository
    with Logging {

  import models.dal.MyPostgresProfile.api.*

  private val members = DatabaseSchema.domain.project.groupProjectMembers

  override def create(member: GroupProjectMember): Future[GroupProjectMember] =
    runQuery(
      (members returning members.map(_.id) into ((m, id) => m.copy(id = Some(id)))) += member
    )

  override def findById(id: Int): Future[Option[GroupProjectMember]] =
    runQuery(members.filter(_.id === id).result.headOption)

  override def findByProjectId(projectId: Int): Future[Seq[GroupProjectMember]] =
    runQuery(members.filter(_.groupProjectId === projectId).result)

  override def findByProjectAndCitizen(projectId: Int, citizenDid: String): Future[Option[GroupProjectMember]] =
    runQuery(members.filter(m => m.groupProjectId === projectId && m.citizenDid === citizenDid).result.headOption)

  override def findByCitizen(citizenDid: String): Future[Seq[GroupProjectMember]] =
    runQuery(members.filter(_.citizenDid === citizenDid).result)

  override def findByProjectAndStatus(projectId: Int, status: String): Future[Seq[GroupProjectMember]] =
    runQuery(members.filter(m => m.groupProjectId === projectId && m.status === status).result)

  override def findByProjectAndRole(projectId: Int, role: String): Future[Seq[GroupProjectMember]] =
    runQuery(members.filter(m => m.groupProjectId === projectId && m.role === role).result)

  override def findByAtUri(atUri: String): Future[Option[GroupProjectMember]] =
    runQuery(members.filter(_.atUri === atUri).result.headOption)

  override def update(member: GroupProjectMember): Future[Boolean] = member.id match {
    case None => Future.successful(false)
    case Some(id) =>
      runQuery(
        members.filter(_.id === id)
          .map(m => (m.role, m.status, m.displayName, m.kitId, m.visibility, m.subgroupIds,
            m.contributionLevel, m.atCid, m.updatedAt))
          .update((member.role, member.status, member.displayName, member.kitId,
            play.api.libs.json.Json.toJson(member.visibility), member.subgroupIds,
            member.contributionLevel, member.atCid, LocalDateTime.now()))
      ).map(_ > 0)
  }

  override def updateStatus(id: Int, status: String): Future[Boolean] =
    runQuery(
      members.filter(_.id === id)
        .map(m => (m.status, m.updatedAt))
        .update((status, LocalDateTime.now()))
    ).map(_ > 0)

  override def updateRole(id: Int, role: String): Future[Boolean] =
    runQuery(
      members.filter(_.id === id)
        .map(m => (m.role, m.updatedAt))
        .update((role, LocalDateTime.now()))
    ).map(_ > 0)

  override def countByProjectAndStatus(projectId: Int, status: String): Future[Int] =
    runQuery(members.filter(m => m.groupProjectId === projectId && m.status === status).length.result)

  override def countActiveByProject(projectId: Int): Future[Int] =
    runQuery(members.filter(m => m.groupProjectId === projectId && m.status === "ACTIVE").length.result)
}
