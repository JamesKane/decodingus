package models.dal

import models.{User, UserReputationScore}
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID

class UserReputationScoresTable(tag: Tag) extends Table[UserReputationScore](tag, "user_reputation_scores") {
  def userId = column[UUID]("user_id", O.PrimaryKey)

  def score = column[Long]("score")

  def lastCalculatedAt = column[ZonedDateTime]("last_calculated_at")

  def * : ProvenShape[UserReputationScore] = (
    userId,
    score,
    lastCalculatedAt
  ).mapTo[UserReputationScore]

  def userFk = foreignKey("fk_user_reputation_scores_user_id", userId, TableQuery[UsersTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}