package services.social

import models.dal.DatabaseSchema
import models.domain.social.{ReputationEvent, UserReputationScore}
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.PostgresProfile

import java.time.LocalDateTime
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReputationService @Inject()(
                                   protected val dbConfigProvider: DatabaseConfigProvider
                                 )(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[PostgresProfile] with Logging {

  import profile.api.*

  private val events = DatabaseSchema.domain.social.reputationEvents
  private val eventTypes = DatabaseSchema.domain.social.reputationEventTypes
  private val scores = DatabaseSchema.domain.social.userReputationScores

  /**
   * Records a reputation event and updates the user's score transactionally.
   *
   * @param userId            The ID of the user receiving the reputation change.
   * @param eventTypeName     The unique name of the event type (e.g., "ACCOUNT_VERIFIED").
   * @param relatedEntity     Optional tuple of (EntityType, EntityId) to link the event to a specific object.
   * @param sourceUserId      Optional ID of the user who triggered the event (e.g., the upvoter).
   * @param notes             Optional notes or reasoning.
   * @return A Future containing the user's new total score.
   */
  def recordEvent(
                   userId: UUID,
                   eventTypeName: String,
                   relatedEntity: Option[(String, UUID)] = None,
                   sourceUserId: Option[UUID] = None,
                   notes: Option[String] = None
                 ): Future[Long] = {

    val action = for {
      // 1. Lookup Event Type
      eventTypeOpt <- eventTypes.filter(_.name === eventTypeName).result.headOption
      eventType = eventTypeOpt.getOrElse(throw new IllegalArgumentException(s"ReputationEventType '$eventTypeName' not found"))

      // 2. Create Event Record
      event = ReputationEvent(
        userId = userId,
        eventTypeId = eventType.id.get,
        actualPointsChange = eventType.defaultPointsChange,
        sourceUserId = sourceUserId,
        relatedEntityType = relatedEntity.map(_._1),
        relatedEntityId = relatedEntity.map(_._2),
        notes = notes,
        createdAt = LocalDateTime.now()
      )
      _ <- events += event

      // 3. Update User Score (Upsert)
      // Lock the row for update to prevent race conditions if needed, but for simple increments, atomic SQL is often enough.
      // Here we'll fetch, calculate, and update within the transaction.
      currentScoreOpt <- scores.filter(_.userId === userId).result.headOption
      newScoreVal = currentScoreOpt.map(_.score).getOrElse(0L) + eventType.defaultPointsChange
      _ <- scores.insertOrUpdate(UserReputationScore(userId, newScoreVal, LocalDateTime.now()))

    } yield newScoreVal

    db.run(action.transactionally).recover {
      case e: Exception =>
        logger.error(s"Failed to record reputation event '$eventTypeName' for user $userId", e)
        throw e
    }
  }

  /**
   * Retrieves the current reputation score for a user.
   *
   * @param userId The user's ID.
   * @return Future[Long] representing the score (defaults to 0 if not found).
   */
  def getScore(userId: UUID): Future[Long] = {
    db.run(scores.filter(_.userId === userId).map(_.score).result.headOption).map(_.getOrElse(0L))
  }
}
