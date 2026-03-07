package services.ibd

import jakarta.inject.{Inject, Singleton}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Source}
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import play.api.{Configuration, Logging}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

case class RelaySession(
  sessionId: String,
  matchRequestUri: String,
  participantA: String,
  participantB: String,
  createdAt: Instant,
  expiresAt: Instant,
  bus: RelayMessageBus
)

case class RelayMessage(
  fromDid: String,
  payload: String,
  timestamp: Instant = Instant.now()
)

class RelayMessageBus(implicit mat: Materializer) {
  private val (sink, source) =
    MergeHub.source[RelayMessage](perProducerBufferSize = 16)
      .toMat(BroadcastHub.sink[RelayMessage](bufferSize = 256))(Keep.both)
      .run()

  def publishSink = sink
  def subscribeTo(forDid: String): Source[RelayMessage, ?] =
    source.filter(_.fromDid != forDid)
}

@Singleton
class IbdRelaySessionManager @Inject()(
  system: ActorSystem,
  configuration: Configuration
)(implicit ec: ExecutionContext, mat: Materializer) extends Logging {

  private val sessionTimeoutMinutes: Long =
    configuration.getOptional[Long]("decodingus.matching.relay.session-timeout-minutes").getOrElse(10)
  private val maxConcurrentSessions: Int =
    configuration.getOptional[Int]("decodingus.matching.relay.max-concurrent-sessions").getOrElse(100)
  private val cleanupIntervalSeconds: Long =
    configuration.getOptional[Long]("decodingus.matching.relay.stale-cleanup-interval-seconds").getOrElse(60)

  private val sessions = new ConcurrentHashMap[String, RelaySession]()

  // Schedule periodic cleanup
  system.scheduler.scheduleWithFixedDelay(
    cleanupIntervalSeconds.seconds,
    cleanupIntervalSeconds.seconds
  )(() => cleanupStaleSessions())

  def createSession(matchRequestUri: String, participantA: String, participantB: String): Option[RelaySession] = {
    if (sessions.size() >= maxConcurrentSessions) {
      logger.warn(s"Max concurrent sessions ($maxConcurrentSessions) reached, rejecting new session")
      return None
    }

    // Check if a session already exists for this match request
    val existing = sessions.values().asScala.find(_.matchRequestUri == matchRequestUri)
    if (existing.isDefined) {
      logger.debug(s"Session already exists for match request $matchRequestUri")
      return existing
    }

    val sessionId = UUID.randomUUID().toString
    val now = Instant.now()
    val session = RelaySession(
      sessionId = sessionId,
      matchRequestUri = matchRequestUri,
      participantA = participantA,
      participantB = participantB,
      createdAt = now,
      expiresAt = now.plusSeconds(sessionTimeoutMinutes * 60),
      bus = new RelayMessageBus()
    )

    sessions.put(sessionId, session)
    logger.info(s"Created relay session $sessionId for match request $matchRequestUri between $participantA and $participantB")
    Some(session)
  }

  def getSession(sessionId: String): Option[RelaySession] = {
    Option(sessions.get(sessionId)).filter(s => Instant.now().isBefore(s.expiresAt))
  }

  def findSessionForRequest(matchRequestUri: String): Option[RelaySession] = {
    sessions.values().asScala.find(s =>
      s.matchRequestUri == matchRequestUri && Instant.now().isBefore(s.expiresAt)
    )
  }

  def isAuthorizedParticipant(sessionId: String, did: String): Boolean = {
    getSession(sessionId).exists(s => s.participantA == did || s.participantB == did)
  }

  def removeSession(sessionId: String): Boolean = {
    Option(sessions.remove(sessionId)).isDefined
  }

  def activeSessions: Int = sessions.size()

  private def cleanupStaleSessions(): Unit = {
    val now = Instant.now()
    val expired = sessions.entrySet().asScala.filter(e => now.isAfter(e.getValue.expiresAt))
    expired.foreach { e =>
      sessions.remove(e.getKey)
      logger.info(s"Cleaned up expired relay session ${e.getKey}")
    }
    if (expired.nonEmpty) {
      logger.info(s"Cleaned up ${expired.size} expired relay sessions, ${sessions.size()} active")
    }
  }
}
