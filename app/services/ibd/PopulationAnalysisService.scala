package services.ibd

import jakarta.inject.{Inject, Singleton}
import models.domain.ibd.{PopulationBreakdownCache, PopulationOverlapScore}
import play.api.libs.json.{JsValue, Json}
import play.api.Logging
import repositories.{PopulationBreakdownCacheRepository, PopulationOverlapScoreRepository}

import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PopulationAnalysisService {
  def cacheBreakdown(sampleGuid: UUID, breakdown: JsValue, sourceAtUri: Option[String]): Future[PopulationBreakdownCache]
  def getBreakdown(sampleGuid: UUID): Future[Option[PopulationBreakdownCache]]
  def computeOverlap(guid1: UUID, guid2: UUID): Future[Option[Double]]
  def getOverlapScore(guid1: UUID, guid2: UUID): Future[Option[PopulationOverlapScore]]
  def computeAllOverlapScores(): Future[Int]
  def removeBreakdown(sampleGuid: UUID): Future[Boolean]
}

@Singleton
class PopulationAnalysisServiceImpl @Inject()(
  breakdownCacheRepo: PopulationBreakdownCacheRepository,
  overlapScoreRepo: PopulationOverlapScoreRepository
)(implicit ec: ExecutionContext) extends PopulationAnalysisService with Logging {

  override def cacheBreakdown(sampleGuid: UUID, breakdown: JsValue, sourceAtUri: Option[String]): Future[PopulationBreakdownCache] = {
    val hash = sha256(Json.stringify(breakdown))
    val entry = PopulationBreakdownCache(
      id = None,
      sampleGuid = sampleGuid,
      breakdown = breakdown,
      breakdownHash = hash,
      cachedAt = ZonedDateTime.now(),
      sourceAtUri = sourceAtUri
    )
    breakdownCacheRepo.upsert(entry)
  }

  override def getBreakdown(sampleGuid: UUID): Future[Option[PopulationBreakdownCache]] =
    breakdownCacheRepo.findBySampleGuid(sampleGuid)

  override def computeOverlap(guid1: UUID, guid2: UUID): Future[Option[Double]] = {
    for {
      bd1 <- breakdownCacheRepo.findBySampleGuid(guid1)
      bd2 <- breakdownCacheRepo.findBySampleGuid(guid2)
    } yield {
      for {
        b1 <- bd1
        b2 <- bd2
      } yield calculateOverlapScore(b1.breakdown, b2.breakdown)
    }
  }

  override def getOverlapScore(guid1: UUID, guid2: UUID): Future[Option[PopulationOverlapScore]] =
    overlapScoreRepo.findByPair(guid1, guid2)

  override def computeAllOverlapScores(): Future[Int] = {
    breakdownCacheRepo.findAll().flatMap { allBreakdowns =>
      val pairs = for {
        i <- allBreakdowns.indices
        j <- (i + 1) until allBreakdowns.size
      } yield (allBreakdowns(i), allBreakdowns(j))

      Future.traverse(pairs) { case (bd1, bd2) =>
        val score = calculateOverlapScore(bd1.breakdown, bd2.breakdown)
        val overlapScore = PopulationOverlapScore(
          id = None,
          sampleGuid1 = bd1.sampleGuid,
          sampleGuid2 = bd2.sampleGuid,
          overlapScore = score,
          computedAt = ZonedDateTime.now()
        )
        overlapScoreRepo.upsert(overlapScore)
      }.map(_.size)
    }
  }

  override def removeBreakdown(sampleGuid: UUID): Future[Boolean] =
    breakdownCacheRepo.deleteBySampleGuid(sampleGuid)

  private[ibd] def calculateOverlapScore(breakdown1: JsValue, breakdown2: JsValue): Double = {
    val map1 = breakdownToMap(breakdown1)
    val map2 = breakdownToMap(breakdown2)
    val allPops = map1.keySet ++ map2.keySet
    allPops.toSeq.map { pop =>
      math.min(map1.getOrElse(pop, 0.0), map2.getOrElse(pop, 0.0))
    }.sum
  }

  private def breakdownToMap(breakdown: JsValue): Map[String, Double] = {
    breakdown.asOpt[Map[String, Double]].getOrElse {
      breakdown.asOpt[Seq[Map[String, JsValue]]].map { components =>
        components.flatMap { comp =>
          for {
            pop <- (comp.get("population").orElse(comp.get("name"))).flatMap(_.asOpt[String])
            pct <- (comp.get("percentage").orElse(comp.get("fraction"))).flatMap(_.asOpt[Double])
          } yield pop -> pct
        }.toMap
      }.getOrElse(Map.empty)
    }
  }

  private def sha256(input: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }
}
