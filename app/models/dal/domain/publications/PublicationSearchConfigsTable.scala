package models.dal.domain.publications

import models.domain.publications.PublicationSearchConfig
import models.dal.MyPostgresProfile.api.*
import java.time.LocalDateTime
import play.api.libs.json.JsValue

class PublicationSearchConfigsTable(tag: Tag) extends Table[PublicationSearchConfig](tag, "publication_search_configs") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def searchQuery = column[String]("search_query")
  def concepts = column[Option[JsValue]]("concepts")
  def journals = column[Option[JsValue]]("journals")
  def enabled = column[Boolean]("enabled")
  def lastRun = column[Option[LocalDateTime]]("last_run")
  def createdAt = column[LocalDateTime]("created_at")

  def * = (
    id.?,
    name,
    searchQuery,
    concepts,
    journals,
    enabled,
    lastRun,
    createdAt
  ).mapTo[PublicationSearchConfig]
}
