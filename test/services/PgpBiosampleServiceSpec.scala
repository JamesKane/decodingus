package services

import models.domain.genomics.{Biosample, BiosampleType, SpecimenDonor}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import repositories.{BiosampleRepository, SpecimenDonorRepository} // AccessionNumberGenerator removed

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class PgpBiosampleServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "PgpBiosampleService" should {

    "create a new PGP biosample successfully" in {
      // Mocks
      val mockBiosampleRepo = mock[BiosampleRepository]
      val mockAccessionGen = mock[AccessionNumberGenerator]
      val mockBiosampleService = mock[BiosampleService] // Changed from mockDonorRepo

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

      // Mock BiosampleService.createOrUpdateSpecimenDonor - it should return None here
      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(None))

      // Mock BiosampleService.createBiosample - it should return a Biosample with the given sampleGuid
      when(mockBiosampleService.createBiosample(any[UUID], anyString, anyString, any, anyString, any, any))
        .thenAnswer(new Answer[Future[Biosample]] {
          override def answer(invocation: InvocationOnMock): Future[Biosample] = {
            val sGuid = invocation.getArgument[UUID](0)
            val b = Biosample(id = Some(100), sampleGuid = sGuid, sampleAccession = generatedAccession, description = description, alias = Some(participantId), centerName = centerName, specimenDonorId = None, locked = false, sourcePlatform = Some("PGP"))
            Future.successful(b.copy(id = Some(100)))
          }
        })

      val service = new PgpBiosampleService(mockBiosampleRepo, mockAccessionGen, mockBiosampleService) // Changed

      // Execute
      val resultFuture = service.createPgpBiosample(participantId, description, centerName)

      // Verify
      whenReady(resultFuture) { guid =>
        guid mustBe a [UUID]
        verify(mockBiosampleService).createBiosample(any[UUID], anyString, anyString, any, anyString, any, any)
        verify(mockBiosampleService, never).createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any)
      }
    }

    "create a donor and biosample when metadata is provided" in {
       // Mocks
      val mockBiosampleRepo = mock[BiosampleRepository]
      val mockAccessionGen = mock[AccessionNumberGenerator]
      val mockBiosampleService = mock[BiosampleService] // Changed from mockDonorRepo

      val participantId = "hu987654"
      val lat = Some(42.0)
      val lon = Some(-71.0)
      val donorId = Some(50)

      when(mockBiosampleRepo.findByAliasOrAccession(participantId))
        .thenReturn(Future.successful(None))
      
      when(mockAccessionGen.generateAccession(any[BiosampleType], any[AccessionMetadata]))
        .thenReturn(Future.successful("SAMEA999"))

      // Mock BiosampleService.createOrUpdateSpecimenDonor
      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(donorId))

      // Mock BiosampleService.createBiosample
      when(mockBiosampleService.createBiosample(any[UUID], anyString, anyString, any, anyString, any, any))
        .thenAnswer(new Answer[Future[Biosample]] {
          override def answer(invocation: InvocationOnMock): Future[Biosample] = {
            val sGuid = invocation.getArgument[UUID](0)
            val b = Biosample(id = Some(100), sampleGuid = sGuid, sampleAccession = "SAMEA999", description = "Desc", alias = Some(participantId), centerName = "PGP", specimenDonorId = donorId, locked = false, sourcePlatform = Some("PGP"))
            Future.successful(b)
          }
        })

      val service = new PgpBiosampleService(mockBiosampleRepo, mockAccessionGen, mockBiosampleService) // Changed

      val resultFuture = service.createPgpBiosample(participantId, "Desc", "PGP", latitude = lat, longitude = lon)

      whenReady(resultFuture) { guid =>
        verify(mockBiosampleService).createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any)
        verify(mockBiosampleService).createBiosample(any[UUID], anyString, anyString, any, anyString, any, any)
      }
    }

    "fail when participant already exists" in {
      val mockBiosampleRepo = mock[BiosampleRepository]
      val mockAccessionGen = mock[AccessionNumberGenerator]
      val mockBiosampleService = mock[BiosampleService] // Changed from mockDonorRepo
      val participantId = "huExisting"

      // Mock finding an existing one
      val existingBiosample = Biosample(Some(1), UUID.randomUUID(), "ACC1", "Desc", Some(participantId), "Center", None, false, None)
      when(mockBiosampleRepo.findByAliasOrAccession(participantId))
        .thenReturn(Future.successful(Some((existingBiosample, None))))

      val service = new PgpBiosampleService(mockBiosampleRepo, mockAccessionGen, mockBiosampleService) // Changed

      val resultFuture = service.createPgpBiosample(participantId, "Desc", "Center")

      whenReady(resultFuture.failed) { e =>
        e mustBe a [DuplicateParticipantException]
        e.getMessage must include ("already has a biosample")
      }
    }
  }
}
