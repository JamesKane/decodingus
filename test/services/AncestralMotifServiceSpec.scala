package services

import helpers.ServiceSpec
import models.domain.haplogroups.{HaplogroupAncestralStr, MotifMethod}
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito.{reset, verify, when}
import repositories.{BiosampleVariantCallRepository, HaplogroupAncestralStrRepository}

import scala.concurrent.Future

class AncestralMotifServiceSpec extends ServiceSpec {

  val mockAncestralRepo: HaplogroupAncestralStrRepository = mock[HaplogroupAncestralStrRepository]
  val mockVariantCallRepo: BiosampleVariantCallRepository = mock[BiosampleVariantCallRepository]

  val service = new AncestralMotifService(mockAncestralRepo, mockVariantCallRepo)

  override def beforeEach(): Unit = {
    reset(mockAncestralRepo, mockVariantCallRepo)
  }

  "AncestralMotifService" should {

    "getMotifForHaplogroup" should {

      "return motif map from stored ancestral STRs" in {
        val motifs = Seq(
          HaplogroupAncestralStr(Some(1), 100, "DYS456", Some(15), None, Some(BigDecimal(0.9)), Some(10), None),
          HaplogroupAncestralStr(Some(2), 100, "DYS389I", Some(13), None, Some(BigDecimal(0.8)), Some(8), None),
          HaplogroupAncestralStr(Some(3), 100, "DYS19", Some(14), None, Some(BigDecimal(0.95)), Some(12), None)
        )
        when(mockAncestralRepo.findByHaplogroup(100)).thenReturn(Future.successful(motifs))

        whenReady(service.getMotifForHaplogroup(100)) { result =>
          result must have size 3
          result("DYS456") mustBe 15
          result("DYS389I") mustBe 13
          result("DYS19") mustBe 14
        }
      }

      "return empty map for haplogroup with no motifs" in {
        when(mockAncestralRepo.findByHaplogroup(999)).thenReturn(Future.successful(Seq.empty))

        whenReady(service.getMotifForHaplogroup(999)) { result =>
          result mustBe empty
        }
      }

      "skip markers with no ancestral value" in {
        val motifs = Seq(
          HaplogroupAncestralStr(Some(1), 100, "DYS456", Some(15), None, None, None, None),
          HaplogroupAncestralStr(Some(2), 100, "DYS389I", None, None, None, None, None)
        )
        when(mockAncestralRepo.findByHaplogroup(100)).thenReturn(Future.successful(motifs))

        whenReady(service.getMotifForHaplogroup(100)) { result =>
          result must have size 1
          result("DYS456") mustBe 15
        }
      }
    }

    "computeModalHaplotype" should {

      "compute mode from sample observations" in {
        val observations = Seq(
          MarkerObservation("DYS456", 15, 1),
          MarkerObservation("DYS456", 15, 2),
          MarkerObservation("DYS456", 16, 3),
          MarkerObservation("DYS456", 15, 4),
          MarkerObservation("DYS389I", 13, 1),
          MarkerObservation("DYS389I", 13, 2),
          MarkerObservation("DYS389I", 14, 3)
        )

        val result = service.computeModalHaplotype(observations, 100)

        result must have size 2
        val dys456 = result.find(_.markerName == "DYS456").get
        dys456.ancestralValue mustBe Some(15) // mode: 3 out of 4
        dys456.supportingSamples mustBe Some(4)
        dys456.confidence.get.toDouble mustBe 0.75 +- 0.01
        dys456.method mustBe MotifMethod.Modal

        val dys389 = result.find(_.markerName == "DYS389I").get
        dys389.ancestralValue mustBe Some(13)
        dys389.supportingSamples mustBe Some(3)
      }

      "compute variance correctly" in {
        // All same value → variance 0
        val sameObs = Seq(
          MarkerObservation("DYS456", 15, 1),
          MarkerObservation("DYS456", 15, 2),
          MarkerObservation("DYS456", 15, 3)
        )
        val sameResult = service.computeModalHaplotype(sameObs, 100)
        sameResult.head.variance mustBe Some(BigDecimal(0))

        // Mixed values → positive variance
        val mixedObs = Seq(
          MarkerObservation("DYS456", 14, 1),
          MarkerObservation("DYS456", 15, 2),
          MarkerObservation("DYS456", 16, 3)
        )
        val mixedResult = service.computeModalHaplotype(mixedObs, 100)
        mixedResult.head.variance.get must be > BigDecimal(0)
      }

      "identify alternative modal values" in {
        val observations = Seq(
          MarkerObservation("DYS456", 15, 1),
          MarkerObservation("DYS456", 15, 2),
          MarkerObservation("DYS456", 15, 3),
          MarkerObservation("DYS456", 16, 4),
          MarkerObservation("DYS456", 16, 5), // alt with count >= 2
          MarkerObservation("DYS456", 17, 6)  // singleton, not alt
        )

        val result = service.computeModalHaplotype(observations, 100)
        val motif = result.head
        motif.ancestralValue mustBe Some(15)
        motif.ancestralValueAlt mustBe Some(List(16)) // 16 appears 2x, 17 only 1x
      }

      "return no alternatives when none qualify" in {
        val observations = Seq(
          MarkerObservation("DYS456", 15, 1),
          MarkerObservation("DYS456", 15, 2),
          MarkerObservation("DYS456", 16, 3) // singleton
        )

        val result = service.computeModalHaplotype(observations, 100)
        result.head.ancestralValueAlt mustBe None
      }

      "handle single sample" in {
        val observations = Seq(MarkerObservation("DYS456", 15, 1))

        val result = service.computeModalHaplotype(observations, 100)
        result must have size 1
        result.head.ancestralValue mustBe Some(15)
        result.head.confidence mustBe Some(BigDecimal(1.0))
        result.head.supportingSamples mustBe Some(1)
        result.head.variance mustBe Some(BigDecimal(0))
      }

      "return empty for no observations" in {
        val result = service.computeModalHaplotype(Seq.empty, 100)
        result mustBe empty
      }
    }

    "computeAndSaveMotif" should {

      "compute and persist motifs" in {
        val observations = Seq(
          MarkerObservation("DYS456", 15, 1),
          MarkerObservation("DYS456", 15, 2),
          MarkerObservation("DYS389I", 13, 1),
          MarkerObservation("DYS389I", 13, 2)
        )

        when(mockAncestralRepo.upsertBatch(any[Seq[HaplogroupAncestralStr]]))
          .thenReturn(Future.successful(Seq(1, 1)))

        whenReady(service.computeAndSaveMotif(observations, 100)) { count =>
          count mustBe 2
          verify(mockAncestralRepo).upsertBatch(any[Seq[HaplogroupAncestralStr]])
        }
      }

      "return 0 for empty observations" in {
        whenReady(service.computeAndSaveMotif(Seq.empty, 100)) { count =>
          count mustBe 0
        }
      }
    }

    "setManualMotif" should {

      "create a manual motif entry" in {
        when(mockAncestralRepo.upsert(any[HaplogroupAncestralStr]))
          .thenReturn(Future.successful(1))

        whenReady(service.setManualMotif(100, "DYS456", 15, Some(BigDecimal(0.99)))) { result =>
          result mustBe 1
          verify(mockAncestralRepo).upsert(any[HaplogroupAncestralStr])
        }
      }
    }

    "clearMotifs" should {

      "delete all motifs for a haplogroup" in {
        when(mockAncestralRepo.deleteByHaplogroup(100)).thenReturn(Future.successful(5))

        whenReady(service.clearMotifs(100)) { count =>
          count mustBe 5
        }
      }
    }
  }

  "MotifMethod" should {

    "round-trip through string conversion" in {
      MotifMethod.fromString("MODAL") mustBe MotifMethod.Modal
      MotifMethod.fromString("PHYLOGENETIC") mustBe MotifMethod.Phylogenetic
      MotifMethod.fromString("MANUAL") mustBe MotifMethod.Manual
    }

    "reject unknown method" in {
      an[IllegalArgumentException] must be thrownBy {
        MotifMethod.fromString("UNKNOWN")
      }
    }
  }
}
