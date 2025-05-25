package models.dal.domain

import models.dal.MyPostgresProfile.api.*
import models.domain.PgpBiosample

import java.util.UUID

/**
 * Represents the database table definition for PGP (Personal Genome Project) biosample records.
 *
 * @constructor Initializes a new instance of the `PgpBiosamplesTable` class, mapping columns
 *              to the corresponding attributes of a `PgpBiosample` entity.
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 *
 *            Columns:
 *            - `id`: A unique identifier for each biosample record (primary key, auto-increment).
 *            - `pgpParticipantId`: A unique identifier for the PGP participant associated with the biosample.
 *            - `sex`: The biological sex of the participant associated with the biosample.
 *            - `sampleGuid`: A universally unique identifier (UUID) for the biosample, ensuring global uniqueness.
 *
 *            Primary key:
 *            - `id`: The primary key for the table, auto-generated.
 *
 *            Constraints:
 *            - `pgpParticipantId`: Enforced as unique to prevent duplicate records for the same PGP participant.
 *
 *            Mapping:
 *            - Defines a mapping to the `PgpBiosample` case class, converting database rows into domain objects
 *              and vice versa.
 */
class PgpBiosamplesTable(tag: Tag) extends Table[PgpBiosample](tag, "pgp_biosample") {
  def id = column[Int]("pgp_biosample_id", O.PrimaryKey, O.AutoInc)

  def pgpParticipantId = column[String]("pgp_participant_id", O.Unique)

  def sex = column[String]("sex")

  def sampleGuid = column[UUID]("sample_guid")

  def * = (id.?, pgpParticipantId, sex, sampleGuid).mapTo[PgpBiosample]
}
