package services

import helpers.ServiceSpec
import models.domain.haplogroups.{AgeEstimate, AnchorType, GenealogicalAnchor}
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito.{reset, verify, when}
import repositories.GenealogicalAnchorRepository

import java.time.LocalDateTime
import scala.concurrent.Future

class GenealogicalAnchorServiceSpec extends ServiceSpec {

  val mockRepo: GenealogicalAnchorRepository = mock[GenealogicalAnchorRepository]
  val service = new GenealogicalAnchorService(mockRepo)

  override def beforeEach(): Unit = {
    reset(mockRepo)
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 6, 1, 12, 0)

  def makeAnchor(
    id: Int,
    haplogroupId: Int,
    anchorType: AnchorType,
    dateCe: Int,
    uncertainty: Int = 50,
    confidence: Double = 0.8,
    carbonDateBp: Option[Int] = None,
    carbonDateSigma: Option[Int] = None
  ): GenealogicalAnchor =
    GenealogicalAnchor(
      id = Some(id), haplogroupId = haplogroupId, anchorType = anchorType,
      dateCe = dateCe, dateUncertaintyYears = Some(uncertainty),
      confidence = Some(BigDecimal(confidence)),
      description = Some("Test anchor"), source = Some("Test"),
      carbonDateBp = carbonDateBp, carbonDateSigma = carbonDateSigma,
      createdBy = Some("curator@test"), createdAt = now
    )

  "GenealogicalAnchorService" should {

    "getAnchorsForHaplogroup" should {

      "return anchors sorted by confidence descending" in {
        val anchors = Seq(
          makeAnchor(1, 100, AnchorType.Mdka, 1200, confidence = 0.5),
          makeAnchor(2, 100, AnchorType.KnownMrca, 1400, confidence = 0.9),
          makeAnchor(3, 100, AnchorType.AncientDna, -500, confidence = 0.7,
            carbonDateBp = Some(2500), carbonDateSigma = Some(50))
        )
        when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(anchors))

        whenReady(service.getAnchorsForHaplogroup(100)) { result =>
          result must have size 3
          result.head.id mustBe Some(2) // highest confidence
          result.last.id mustBe Some(1) // lowest confidence
        }
      }

      "return empty for haplogroup with no anchors" in {
        when(mockRepo.findByHaplogroup(999)).thenReturn(Future.successful(Seq.empty))

        whenReady(service.getAnchorsForHaplogroup(999)) { result =>
          result mustBe empty
        }
      }
    }

    "createAnchor" should {

      "create a valid anchor" in {
        val anchor = makeAnchor(0, 100, AnchorType.KnownMrca, 1400).copy(id = None)
        when(mockRepo.create(any[GenealogicalAnchor]))
          .thenReturn(Future.successful(anchor.copy(id = Some(1))))

        whenReady(service.createAnchor(anchor)) { result =>
          result.id mustBe Some(1)
          verify(mockRepo).create(any[GenealogicalAnchor])
        }
      }

      "reject anchor with invalid confidence" in {
        val anchor = makeAnchor(0, 100, AnchorType.KnownMrca, 1400, confidence = 1.5)
        an[IllegalArgumentException] must be thrownBy {
          service.createAnchor(anchor)
        }
      }

      "reject ancient DNA anchor without carbon date or valid date_ce" in {
        val anchor = GenealogicalAnchor(
          haplogroupId = 100, anchorType = AnchorType.AncientDna,
          dateCe = 0, dateUncertaintyYears = None,
          confidence = None, description = None, source = None,
          carbonDateBp = None, carbonDateSigma = None,
          createdBy = None
        )
        an[IllegalArgumentException] must be thrownBy {
          service.createAnchor(anchor)
        }
      }
    }

    "deleteAnchor" should {

      "delete an existing anchor" in {
        when(mockRepo.delete(1)).thenReturn(Future.successful(true))

        whenReady(service.deleteAnchor(1)) { result =>
          result mustBe true
        }
      }
    }
  }

  "applyAnchorConstraints" should {

    "return unconstrained estimate when no anchors exist" in {
      when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(Seq.empty))

      val estimate = AgeEstimate(1000, Some(700), Some(1300))
      whenReady(service.applyAnchorConstraints(100, estimate)) { result =>
        result.constrained mustBe false
        result.estimate mustBe estimate
      }
    }

    "apply KNOWN_MRCA as lower bound on age" in {
      // Known MRCA from 1400 CE = 550 YBP, uncertainty ±50 → lower bound 500 YBP
      val anchor = makeAnchor(1, 100, AnchorType.KnownMrca, 1400, uncertainty = 50)
      when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(Seq(anchor)))

      // Estimate is too young (300 YBP)
      val estimate = AgeEstimate(300, Some(200), Some(400))
      whenReady(service.applyAnchorConstraints(100, estimate)) { result =>
        result.constrained mustBe true
        result.estimate.ybp must be >= 500 // adjusted to at least anchor lower bound
        result.estimate.ybpLower.get must be >= 500
      }
    }

    "not constrain when estimate already satisfies anchor" in {
      // Known MRCA from 1400 CE = 550 YBP
      val anchor = makeAnchor(1, 100, AnchorType.KnownMrca, 1400, uncertainty = 50)
      when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(Seq(anchor)))

      // Estimate is already older (2000 YBP) than anchor
      val estimate = AgeEstimate(2000, Some(1500), Some(2500))
      whenReady(service.applyAnchorConstraints(100, estimate)) { result =>
        result.constrained mustBe false
        result.estimate.ybp mustBe 2000
      }
    }

    "apply MDKA as lower bound" in {
      // MDKA from 1700 CE = 250 YBP, uncertainty ±30 → lower bound 220 YBP
      val anchor = makeAnchor(1, 100, AnchorType.Mdka, 1700, uncertainty = 30)
      when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(Seq(anchor)))

      val estimate = AgeEstimate(100, Some(50), Some(150))
      whenReady(service.applyAnchorConstraints(100, estimate)) { result =>
        result.constrained mustBe true
        result.estimate.ybp must be >= 220
      }
    }

    "apply ANCIENT_DNA with carbon dating as hard lower bound" in {
      // Carbon date: 4500 BP ± 50 → 2-sigma lower bound = 4400 YBP
      val anchor = makeAnchor(1, 100, AnchorType.AncientDna, -2550,
        carbonDateBp = Some(4500), carbonDateSigma = Some(50))
      when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(Seq(anchor)))

      val estimate = AgeEstimate(3000, Some(2500), Some(3500))
      whenReady(service.applyAnchorConstraints(100, estimate)) { result =>
        result.constrained mustBe true
        result.estimate.ybp must be >= 4400
        result.estimate.ybpLower.get must be >= 4400
      }
    }

    "apply multiple anchors taking the strictest constraint" in {
      val anchor1 = makeAnchor(1, 100, AnchorType.KnownMrca, 1400, uncertainty = 50) // 500 YBP lower
      val anchor2 = makeAnchor(2, 100, AnchorType.Mdka, 1200, uncertainty = 50)       // 700 YBP lower
      when(mockRepo.findByHaplogroup(100)).thenReturn(Future.successful(Seq(anchor1, anchor2)))

      val estimate = AgeEstimate(300, Some(200), Some(400))
      whenReady(service.applyAnchorConstraints(100, estimate)) { result =>
        result.constrained mustBe true
        // The MDKA at 700 YBP is stricter than KNOWN_MRCA at 500 YBP
        result.estimate.ybp must be >= 700
        result.estimate.ybpLower.get must be >= 700
      }
    }
  }

  "GenealogicalAnchor" should {

    "convert date_ce to YBP correctly" in {
      makeAnchor(1, 100, AnchorType.KnownMrca, 1400).toYbp mustBe 550
      makeAnchor(1, 100, AnchorType.KnownMrca, -500).toYbp mustBe 2450
      makeAnchor(1, 100, AnchorType.KnownMrca, 1950).toYbp mustBe 0
    }

    "convert to AgeEstimate" in {
      val anchor = makeAnchor(1, 100, AnchorType.KnownMrca, 1400, uncertainty = 50)
      val est = anchor.toAgeEstimate
      est.ybp mustBe 550
      est.ybpLower mustBe Some(500)
      est.ybpUpper mustBe Some(600)
    }
  }

  "validateAnchor" should {

    "accept valid KNOWN_MRCA anchor" in {
      noException must be thrownBy {
        service.validateAnchor(makeAnchor(0, 100, AnchorType.KnownMrca, 1400))
      }
    }

    "accept valid ANCIENT_DNA with carbon date" in {
      noException must be thrownBy {
        service.validateAnchor(makeAnchor(0, 100, AnchorType.AncientDna, -2500,
          carbonDateBp = Some(4500), carbonDateSigma = Some(50)))
      }
    }

    "reject zero haplogroup ID" in {
      an[IllegalArgumentException] must be thrownBy {
        service.validateAnchor(makeAnchor(0, 0, AnchorType.KnownMrca, 1400))
      }
    }
  }
}
