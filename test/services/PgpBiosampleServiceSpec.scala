package services

import models.domain.genomics.{Biosample, BiosampleType, SpecimenDonor}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import repositories.{BiosampleRepository, SpecimenDonorRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class PgpBiosampleServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "PgpBiosampleService" should {

    "create a new PGP biosample successfully" in {
      // Mocks
      val mockBiosampleRepo = mock[BiosampleRepository]
      val mockAccessionGen = mock[AccessionNumberGenerator]
      val mockDonorRepo = mock[SpecimenDonorRepository]

      // Test Data
      val participantId = "hu123456"
      val description = "Test Sample"
      val centerName = "PGP-Harvard"
      val generatedAccession = "SAMEA123456"
      val sampleGuid = UUID.randomUUID()
      
      // Mock Behavior
      when(mockBiosampleRepo.findByAliasOrAccession(participantId))
        .thenReturn(Future.successful(None))

      when(mockAccessionGen.generateAccession(any[BiosampleType], any[AccessionMetadata]))
        .thenReturn(Future.successful(generatedAccession))

      when(mockBiosampleRepo.create(any[Biosample]))
        .thenAnswer(new Answer[Future[Biosample]] {
          override def answer(invocation: InvocationOnMock): Future[Biosample] = {
            val b = invocation.getArgument[Biosample](0)
            Future.successful(b.copy(id = Some(100)))
          }
        })

      val service = new PgpBiosampleService(mockBiosampleRepo, mockAccessionGen, mockDonorRepo)

      // Execute
      val resultFuture = service.createPgpBiosample(participantId, description, centerName)

      // Verify
      whenReady(resultFuture) { guid =>
        guid mustBe a [UUID]
        verify(mockBiosampleRepo).create(any[Biosample])
        verify(mockDonorRepo, never).create(any[SpecimenDonor]) 
      }
    }

    "create a donor and biosample when metadata is provided" in {
       // Mocks
      val mockBiosampleRepo = mock[BiosampleRepository]
      val mockAccessionGen = mock[AccessionNumberGenerator]
      val mockDonorRepo = mock[SpecimenDonorRepository]

      val participantId = "hu987654"
      val lat = Some(42.0)
      val lon = Some(-71.0)

      when(mockBiosampleRepo.findByAliasOrAccession(participantId))
        .thenReturn(Future.successful(None))
      
      when(mockAccessionGen.generateAccession(any[BiosampleType], any[AccessionMetadata]))
        .thenReturn(Future.successful("SAMEA999"))

      when(mockDonorRepo.create(any[SpecimenDonor]))
        .thenReturn(Future.successful(SpecimenDonor(Some(50), participantId, "PGP", BiosampleType.PGP, None, None, Some(participantId), None, None, None)))

      when(mockBiosampleRepo.create(any[Biosample]))
         .thenAnswer(new Answer[Future[Biosample]] {
          override def answer(invocation: InvocationOnMock): Future[Biosample] = {
            val b = invocation.getArgument[Biosample](0)
            Future.successful(b)
          }
        })

      val service = new PgpBiosampleService(mockBiosampleRepo, mockAccessionGen, mockDonorRepo)

      val resultFuture = service.createPgpBiosample(participantId, "Desc", "PGP", latitude = lat, longitude = lon)

      whenReady(resultFuture) { guid =>
        verify(mockDonorRepo).create(any[SpecimenDonor])
        verify(mockBiosampleRepo).create(any[Biosample])
      }
    }

    "fail when participant already exists" in {
      val mockBiosampleRepo = mock[BiosampleRepository]
      val mockAccessionGen = mock[AccessionNumberGenerator]
      val mockDonorRepo = mock[SpecimenDonorRepository]
      val participantId = "huExisting"

      // Mock finding an existing one
      val existingBiosample = Biosample(Some(1), UUID.randomUUID(), "ACC1", "Desc", Some(participantId), "Center", None, false, None)
      when(mockBiosampleRepo.findByAliasOrAccession(participantId))
        .thenReturn(Future.successful(Some((existingBiosample, None))))

      val service = new PgpBiosampleService(mockBiosampleRepo, mockAccessionGen, mockDonorRepo)

      val resultFuture = service.createPgpBiosample(participantId, "Desc", "Center")

      whenReady(resultFuture.failed) { e =>
        e mustBe a [DuplicateParticipantException]
        e.getMessage must include ("already has a biosample")
      }
    }
  }
}
