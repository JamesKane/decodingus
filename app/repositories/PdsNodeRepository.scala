package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.MetadataSchema
import models.domain.pds.{PdsFleetConfig, PdsHeartbeatLog, PdsNode}
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.db.NamedDatabase
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait PdsNodeRepository {
  def create(node: PdsNode): Future[PdsNode]
  def findById(id: Int): Future[Option[PdsNode]]
  def findByDid(did: String): Future[Option[PdsNode]]
  def findByStatus(status: String): Future[Seq[PdsNode]]
  def findAll(): Future[Seq[PdsNode]]
  def update(node: PdsNode): Future[Boolean]
  def updateStatus(id: Int, status: String): Future[Boolean]
  def updateHeartbeat(id: Int, status: String, softwareVersion: Option[String],
                      lastCommitCid: Option[String], lastCommitRev: Option[String]): Future[Boolean]
  def countByStatus(): Future[Map[String, Int]]
  def findStaleNodes(threshold: LocalDateTime): Future[Seq[PdsNode]]
  def delete(id: Int): Future[Boolean]
}

@Singleton
class PdsNodeRepositoryImpl @Inject()(
  @NamedDatabase("metadata") protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with PdsNodeRepository
    with Logging {

  import profile.api.*
  import models.dal.MyPostgresProfile.api.playJsonTypeMapper

  private val nodes = MetadataSchema.pdsNodes

  override def create(node: PdsNode): Future[PdsNode] =
    db.run((nodes returning nodes.map(_.id) into ((n, id) => n.copy(id = Some(id)))) += node)

  override def findById(id: Int): Future[Option[PdsNode]] =
    db.run(nodes.filter(_.id === id).result.headOption)

  override def findByDid(did: String): Future[Option[PdsNode]] =
    db.run(nodes.filter(_.did === did).result.headOption)

  override def findByStatus(status: String): Future[Seq[PdsNode]] =
    db.run(nodes.filter(_.status === status).result)

  override def findAll(): Future[Seq[PdsNode]] =
    db.run(nodes.sortBy(_.did).result)

  override def update(node: PdsNode): Future[Boolean] = node.id match {
    case None => Future.successful(false)
    case Some(id) =>
      val now = LocalDateTime.now()
      val q1 = nodes.filter(_.id === id)
        .map(n => (n.pdsUrl, n.handle, n.nodeName, n.softwareVersion, n.status))
        .update((node.pdsUrl, node.handle, node.nodeName, node.softwareVersion, node.status))
      val q2 = nodes.filter(_.id === id)
        .map(n => (n.capabilities, n.ipAddress, n.osInfo, n.updatedAt))
        .update((node.capabilities, node.ipAddress, node.osInfo, now))
      db.run(DBIO.seq(q1, q2).transactionally).map(_ => true)
  }

  override def updateStatus(id: Int, status: String): Future[Boolean] =
    db.run(
      nodes.filter(_.id === id)
        .map(n => (n.status, n.updatedAt))
        .update((status, LocalDateTime.now()))
    ).map(_ > 0)

  override def updateHeartbeat(id: Int, status: String, softwareVersion: Option[String],
                               lastCommitCid: Option[String], lastCommitRev: Option[String]): Future[Boolean] =
    db.run(
      nodes.filter(_.id === id)
        .map(n => (n.status, n.softwareVersion, n.lastHeartbeat, n.lastCommitCid, n.lastCommitRev, n.updatedAt))
        .update((status, softwareVersion, Some(LocalDateTime.now()), lastCommitCid, lastCommitRev, LocalDateTime.now()))
    ).map(_ > 0)

  override def countByStatus(): Future[Map[String, Int]] =
    db.run(nodes.groupBy(_.status).map { case (status, group) => (status, group.length) }.result)
      .map(_.toMap)

  override def findStaleNodes(threshold: LocalDateTime): Future[Seq[PdsNode]] =
    db.run(
      nodes.filter(n =>
        n.status =!= "OFFLINE" && (n.lastHeartbeat.isEmpty || n.lastHeartbeat < threshold)
      ).result
    )

  override def delete(id: Int): Future[Boolean] =
    db.run(nodes.filter(_.id === id).delete).map(_ > 0)
}

trait PdsHeartbeatLogRepository {
  def create(log: PdsHeartbeatLog): Future[PdsHeartbeatLog]
  def findByNode(nodeId: Int, limit: Int = 100): Future[Seq[PdsHeartbeatLog]]
  def findByNodeSince(nodeId: Int, since: LocalDateTime): Future[Seq[PdsHeartbeatLog]]
  def deleteOlderThan(cutoff: LocalDateTime): Future[Int]
}

@Singleton
class PdsHeartbeatLogRepositoryImpl @Inject()(
  @NamedDatabase("metadata") protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with PdsHeartbeatLogRepository
    with Logging {

  import profile.api.*

  private val logs = MetadataSchema.pdsHeartbeatLogs

  override def create(log: PdsHeartbeatLog): Future[PdsHeartbeatLog] =
    db.run((logs returning logs.map(_.id) into ((l, id) => l.copy(id = Some(id)))) += log)

  override def findByNode(nodeId: Int, limit: Int): Future[Seq[PdsHeartbeatLog]] =
    db.run(logs.filter(_.pdsNodeId === nodeId).sortBy(_.recordedAt.desc).take(limit).result)

  override def findByNodeSince(nodeId: Int, since: LocalDateTime): Future[Seq[PdsHeartbeatLog]] =
    db.run(logs.filter(l => l.pdsNodeId === nodeId && l.recordedAt >= since).sortBy(_.recordedAt.desc).result)

  override def deleteOlderThan(cutoff: LocalDateTime): Future[Int] =
    db.run(logs.filter(_.recordedAt < cutoff).delete)
}

trait PdsFleetConfigRepository {
  def findByKey(key: String): Future[Option[PdsFleetConfig]]
  def findAll(): Future[Seq[PdsFleetConfig]]
  def upsert(key: String, value: String, updatedBy: Option[String] = None): Future[Boolean]
}

@Singleton
class PdsFleetConfigRepositoryImpl @Inject()(
  @NamedDatabase("metadata") protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with PdsFleetConfigRepository
    with Logging {

  import profile.api.*

  private val configs = MetadataSchema.pdsFleetConfigs

  override def findByKey(key: String): Future[Option[PdsFleetConfig]] =
    db.run(configs.filter(_.configKey === key).result.headOption)

  override def findAll(): Future[Seq[PdsFleetConfig]] =
    db.run(configs.sortBy(_.configKey).result)

  override def upsert(key: String, value: String, updatedBy: Option[String]): Future[Boolean] =
    findByKey(key).flatMap {
      case Some(existing) =>
        db.run(
          configs.filter(_.configKey === key)
            .map(c => (c.configValue, c.updatedBy, c.updatedAt))
            .update((value, updatedBy, LocalDateTime.now()))
        ).map(_ > 0)
      case None =>
        db.run(
          configs += PdsFleetConfig(configKey = key, configValue = value, updatedBy = updatedBy)
        ).map(_ > 0)
    }
}
