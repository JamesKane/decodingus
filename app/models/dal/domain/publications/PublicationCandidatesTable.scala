package models.dal.domain.publications

import models.domain.publications.PublicationCandidate
import models.dal.MyPostgresProfile.api.*
import java.time.{LocalDate, LocalDateTime}
import java.util.UUID
import play.api.libs.json.JsValue

class PublicationCandidatesTable(tag: Tag) extends Table[PublicationCandidate](tag, "publication_candidates") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def openAlexId = column[String]("openalex_id", O.Unique)
  def doi = column[Option[String]]("doi")
  def title = column[String]("title")
  def abstractSummary = column[Option[String]]("abstract")
  def publicationDate = column[Option[LocalDate]]("publication_date")
  def journalName = column[Option[String]]("journal_name")
  def relevanceScore = column[Option[Double]]("relevance_score")
  def discoveryDate = column[LocalDateTime]("discovery_date")
  def status = column[String]("status")
  def reviewedBy = column[Option[UUID]]("reviewed_by")
  def reviewedAt = column[Option[LocalDateTime]]("reviewed_at")
  def rejectionReason = column[Option[String]]("rejection_reason")
  def rawMetadata = column[Option[JsValue]]("raw_metadata")

  def * = (
    id.?,
    openAlexId,
    doi,
    title,
    abstractSummary,
    publicationDate,
    journalName,
    relevanceScore,
    discoveryDate,
    status,
    reviewedBy,
    reviewedAt,
    rejectionReason,
    rawMetadata
  ).mapTo[PublicationCandidate]
}
