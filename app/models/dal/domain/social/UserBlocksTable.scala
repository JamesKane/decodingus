package models.dal.domain.social

import models.domain.social.UserBlock
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.LocalDateTime

class UserBlocksTable(tag: Tag) extends Table[UserBlock](tag, Some("social"), "user_blocks") {

  def blockerDid = column[String]("blocker_did")
  def blockedDid = column[String]("blocked_did")
  def reason = column[Option[String]]("reason")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[UserBlock] = (blockerDid, blockedDid, reason, createdAt).mapTo[UserBlock]

  def pk = primaryKey("pk_user_blocks", (blockerDid, blockedDid))
}
