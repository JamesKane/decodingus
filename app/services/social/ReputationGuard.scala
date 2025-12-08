package services.social

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID

@Singleton
class ReputationGuard @Inject()(
                                 reputationService: ReputationService
                               )(implicit ec: ExecutionContext) {

  private val POST_FEED_THRESHOLD = 10
  private val INITIATE_DM_THRESHOLD = 20
  private val CREATE_GROUP_THRESHOLD = 50

  def canPostToFeed(userId: UUID): Future[Boolean] = {
    reputationService.getScore(userId).map(_ >= POST_FEED_THRESHOLD)
  }

  def canInitiateDM(userId: UUID): Future[Boolean] = {
    reputationService.getScore(userId).map(_ >= INITIATE_DM_THRESHOLD)
  }

  def canCreateGroup(userId: UUID): Future[Boolean] = {
    reputationService.getScore(userId).map(_ >= CREATE_GROUP_THRESHOLD)
  }
}
