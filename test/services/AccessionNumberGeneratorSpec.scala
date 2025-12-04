package services

import models.domain.genomics.BiosampleType
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import repositories.CitizenSequenceRepository

import scala.concurrent.{ExecutionContext, Future}

class AccessionNumberGeneratorSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "BiosampleAccessionGenerator" should {

    val mockSequenceRepo = mock[CitizenSequenceRepository]
    val mockConfig = mock[Configuration]

    // Setup mock config
    when(mockConfig.get[String]("biosample.hash.salt")).thenReturn("test-salt")
    
    val generator = new BiosampleAccessionGenerator(mockSequenceRepo, mockConfig)

    "generate accession for Standard biosample" in {
      val metadata = AccessionMetadata(existingAccession = Some("SAMEA123"))
      val result = generator.generateAccession(BiosampleType.Standard, metadata)
      whenReady(result) { acc =>
        acc mustBe "SAMEA123"
      }
    }

    "fail for Standard biosample without existing accession" in {
      val metadata = AccessionMetadata(existingAccession = None)
      val result = generator.generateAccession(BiosampleType.Standard, metadata)
      whenReady(result.failed) { e =>
        e mustBe a [IllegalArgumentException]
      }
    }

    "generate accession for PGP biosample" in {
      val metadata = AccessionMetadata(pgpParticipantId = Some("hu123"))
      val result = generator.generateAccession(BiosampleType.PGP, metadata)
      whenReady(result) { acc =>
        acc mustBe "PGP-hu123"
      }
    }

    "fail for PGP biosample without participant ID" in {
      val metadata = AccessionMetadata(pgpParticipantId = None)
      val result = generator.generateAccession(BiosampleType.PGP, metadata)
      whenReady(result.failed) { e =>
        e mustBe a [IllegalArgumentException]
      }
    }
    
    "generate accession for Citizen biosample" in {
       when(mockSequenceRepo.getNextSequence()).thenReturn(Future.successful(12345L))
       val result = generator.generateAccession(BiosampleType.Citizen, AccessionMetadata())
       whenReady(result) { acc =>
         acc must startWith("DU-")
       }
    }

    "decode a valid citizen accession" in {
      val result = generator.decodeAccession("INVALID-FORMAT")
      whenReady(result) { res =>
        res mustBe None
      }
    }
  }
}
