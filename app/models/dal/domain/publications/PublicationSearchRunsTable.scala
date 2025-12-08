package models.dal.domain.publications

import models.domain.publications.PublicationSearchRun
import models.dal.MyPostgresProfile.api.*
import java.time.LocalDateTime

class PublicationSearchRunsTable(tag: Tag) extends Table[PublicationSearchRun](tag, "publication_search_runs") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def configId = column[Int]("config_id")
  def runAt = column[LocalDateTime]("run_at")
  def candidatesFound = column[Int]("candidates_found")
  def newCandidates = column[Int]("new_candidates")
  def queryUsed = column[Option[String]]("query_used")
  def durationMs = column[Option[Int]]("duration_ms")

  def * = (
    id.?,
    configId,
    runAt,
    candidatesFound,
    newCandidates,
    queryUsed,
    durationMs
  ).mapTo[PublicationSearchRun]
  
  def config = foreignKey("fk_run_config", configId, TableQuery[PublicationSearchConfigsTable])(_.id)
}
