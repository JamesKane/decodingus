package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.curator.AuditLogEntry
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CuratorAuditRepository @Inject()(
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  private val auditLog = DatabaseSchema.curator.auditLog

  /**
   * Log an audit entry.
   */
  def logAction(entry: AuditLogEntry): Future[AuditLogEntry] = {
    val entryWithId = entry.copy(id = Some(entry.id.getOrElse(UUID.randomUUID())))
    db.run((auditLog returning auditLog) += entryWithId)
  }

  /**
   * Get audit history for a specific entity.
   */
  def getEntityHistory(entityType: String, entityId: Int): Future[Seq[AuditLogEntry]] = {
    db.run(
      auditLog
        .filter(e => e.entityType === entityType && e.entityId === entityId)
        .sortBy(_.createdAt.desc)
        .result
    )
  }

  /**
   * Get recent audit actions with pagination.
   */
  def getRecentActions(limit: Int = 50, offset: Int = 0): Future[Seq[AuditLogEntry]] = {
    db.run(
      auditLog
        .sortBy(_.createdAt.desc)
        .drop(offset)
        .take(limit)
        .result
    )
  }

  /**
   * Get actions by a specific user.
   */
  def getActionsByUser(userId: UUID, limit: Int = 50, offset: Int = 0): Future[Seq[AuditLogEntry]] = {
    db.run(
      auditLog
        .filter(_.userId === userId)
        .sortBy(_.createdAt.desc)
        .drop(offset)
        .take(limit)
        .result
    )
  }

  /**
   * Count total audit entries for pagination.
   */
  def countAll(): Future[Int] = {
    db.run(auditLog.length.result)
  }

  /**
   * Count audit entries for a specific entity.
   */
  def countByEntity(entityType: String, entityId: Int): Future[Int] = {
    db.run(
      auditLog
        .filter(e => e.entityType === entityType && e.entityId === entityId)
        .length
        .result
    )
  }
}
