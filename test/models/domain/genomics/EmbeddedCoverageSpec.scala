package models.domain.genomics

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json

import java.time.LocalDateTime

class EmbeddedCoverageSpec extends PlaySpec {

  "EmbeddedCoverage" should {

    "serialize to JSON" in {
      val coverage = EmbeddedCoverage(
        meanDepth = Some(30.5),
        medianDepth = Some(29.0),
        percentCoverageAt1x = Some(0.99),
        percentCoverageAt5x = Some(0.97),
        percentCoverageAt10x = Some(0.95),
        percentCoverageAt20x = Some(0.90),
        percentCoverageAt30x = Some(0.75),
        basesNoCoverage = Some(1000L),
        basesLowQualityMapping = Some(500L),
        basesCallable = Some(50000000L),
        meanMappingQuality = Some(59.5)
      )

      val json = Json.toJson(coverage)
      (json \ "meanDepth").as[Double] mustBe 30.5
      (json \ "basesCallable").as[Long] mustBe 50000000L
      (json \ "meanMappingQuality").as[Double] mustBe 59.5
    }

    "deserialize from JSON" in {
      val json = Json.parse("""{
        "meanDepth": 30.5,
        "medianDepth": 29.0,
        "percentCoverageAt1x": 0.99,
        "basesCallable": 50000000,
        "meanMappingQuality": 59.5
      }""")

      val coverage = json.as[EmbeddedCoverage]
      coverage.meanDepth mustBe Some(30.5)
      coverage.basesCallable mustBe Some(50000000L)
      coverage.percentCoverageAt5x mustBe None
    }

    "handle all-None fields" in {
      val coverage = EmbeddedCoverage()
      val json = Json.toJson(coverage)
      val roundTripped = json.as[EmbeddedCoverage]
      roundTripped mustBe coverage
    }

    "round-trip through JSON" in {
      val coverage = EmbeddedCoverage(
        meanDepth = Some(25.0),
        percentCoverageAt10x = Some(0.93),
        basesCallable = Some(48000000L)
      )
      val roundTripped = Json.toJson(coverage).as[EmbeddedCoverage]
      roundTripped mustBe coverage
    }
  }

  "AlignmentMetadata.embeddedCoverage" should {

    val baseMetadata = AlignmentMetadata(
      id = Some(1L),
      sequenceFileId = 100L,
      genbankContigId = 1,
      metricLevel = MetricLevel.CONTIG_OVERALL,
      analysisTool = "mosdepth"
    )

    "return None when coverage is None" in {
      baseMetadata.embeddedCoverage mustBe None
    }

    "parse embedded coverage from JSONB" in {
      val coverageJson = Json.parse("""{"meanDepth": 30.5, "basesCallable": 50000000}""")
      val metadata = baseMetadata.copy(coverage = Some(coverageJson))

      val ec = metadata.embeddedCoverage
      ec mustBe defined
      ec.get.meanDepth mustBe Some(30.5)
      ec.get.basesCallable mustBe Some(50000000L)
    }

    "set coverage via withCoverage" in {
      val ec = EmbeddedCoverage(meanDepth = Some(30.5), basesCallable = Some(50000000L))
      val metadata = baseMetadata.withCoverage(ec)

      metadata.coverage mustBe defined
      metadata.embeddedCoverage mustBe Some(ec)
    }
  }
}
