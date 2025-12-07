package models.dal

import models.PDSRegistration
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime

object MetadataSchema {

  class PDSRegistrationsTable(tag: Tag) extends Table[PDSRegistration](tag, "pds_registrations") {
    def did = column[String]("did", O.PrimaryKey)

    def pdsUrl = column[String]("pds_url")

    def handle = column[String]("handle")

    def lastCommitCid = column[Option[String]]("last_commit_cid")

    def lastCommitSeq = column[Option[Long]]("last_commit_seq")

    def cursor = column[Long]("cursor")

    def createdAt = column[ZonedDateTime]("created_at")

    def updatedAt = column[ZonedDateTime]("updated_at")

    def leasedByInstanceId = column[Option[String]]("leased_by_instance_id")

    def leaseExpiresAt = column[Option[ZonedDateTime]]("lease_expires_at")

    def processingStatus = column[String]("processing_status")

    def * : ProvenShape[PDSRegistration] = (
      did, pdsUrl, handle, lastCommitCid, lastCommitSeq, cursor, createdAt, updatedAt,
      leasedByInstanceId, leaseExpiresAt, processingStatus
    ) <> ((PDSRegistration.apply _).tupled, PDSRegistration.unapply)
  }

  val pdsRegistrations = TableQuery[PDSRegistrationsTable]
}
