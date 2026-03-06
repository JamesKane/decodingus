package controllers

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Injecting}
import models.api.ExternalBiosampleRequest
import services.*

import java.util.UUID
import scala.concurrent.Future

class ExternalBiosampleControllerSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with Injecting
  with MockitoSugar
  with ScalaFutures
  with BeforeAndAfterEach {

  val mockDomainService: BiosampleDomainService = mock[BiosampleDomainService]

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure(
        "play.evolutions.enabled" -> false,
        "play.modules.disabled.0" -> "modules.StartupModule",
        "api.key.enabled" -> false,
        "slick.dbs.default.profile" -> "slick.jdbc.H2Profile$",
        "slick.dbs.default.db.driver" -> "org.h2.Driver",
        "slick.dbs.default.db.url" -> "jdbc:h2:mem:test_ctrl;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1",
        "slick.dbs.default.db.username" -> "sa",
        "slick.dbs.default.db.password" -> "",
        "slick.dbs.metadata.profile" -> "slick.jdbc.H2Profile$",
        "slick.dbs.metadata.db.driver" -> "org.h2.Driver",
        "slick.dbs.metadata.db.url" -> "jdbc:h2:mem:test_ctrl_meta;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1",
        "slick.dbs.metadata.db.username" -> "sa",
        "slick.dbs.metadata.db.password" -> "",
        "pekko.quartz.schedules" -> Map.empty
      )
      .overrides(
        bind[BiosampleDomainService].toInstance(mockDomainService)
      )
      .build()
  }

  override def beforeEach(): Unit = {
    reset(mockDomainService)
  }

  val testGuid: UUID = UUID.randomUUID()

  val validRequestJson = Json.obj(
    "sampleAccession" -> "SAMEA001",
    "sourceSystem" -> "test",
    "description" -> "Test sample",
    "alias" -> "alias1",
    "centerName" -> "TestCenter",
    "sequenceData" -> Json.obj(
      "reads" -> 1000,
      "readLength" -> 150,
      "coverage" -> 30.0,
      "platformName" -> "Illumina",
      "testType" -> "WGS",
      "files" -> Json.arr()
    )
  )

  "ExternalBiosampleController" should {

    "return 201 Created on successful biosample creation" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.successful(testGuid))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "success"
      (json \ "guid").as[String] mustBe testGuid.toString
    }

    "return 409 Conflict for duplicate accession" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.failed(DuplicateAccessionException("SAMEA001")))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe CONFLICT
      val json = contentAsJson(result)
      (json \ "error").as[String] mustBe "Duplicate accession"
    }

    "return 400 for invalid coordinates" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.failed(InvalidCoordinatesException(999.0, 999.0)))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      val json = contentAsJson(result)
      (json \ "error").as[String] mustBe "Invalid coordinates"
    }

    "return 400 for sequence data validation error" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.failed(SequenceDataValidationException("Invalid format")))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      val json = contentAsJson(result)
      (json \ "error").as[String] mustBe "Invalid sequence data"
    }

    "return 400 for publication linkage error" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.failed(PublicationLinkageException("DOI not found")))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
      val json = contentAsJson(result)
      (json \ "error").as[String] mustBe "Publication linkage failed"
    }

    "return 500 for unexpected exceptions" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.failed(new RuntimeException("unexpected")))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe INTERNAL_SERVER_ERROR
      val json = contentAsJson(result)
      (json \ "message").as[String] must include("unexpected error")
    }

    "return 400 for missing required fields" in {
      val incomplete = Json.obj(
        "sampleAccession" -> "SAMEA001"
        // Missing sourceSystem, description, centerName, sequenceData
      )

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(incomplete)

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
    }

    "return 400 for malformed JSON" in {
      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withBody("{invalid json")

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
    }

    "return 415 for non-JSON content type" in {
      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "text/plain")
        .withBody("not json")

      val result = route(app, request).get

      status(result) mustBe UNSUPPORTED_MEDIA_TYPE
    }

    "pass request body to domain service" in {
      when(mockDomainService.createExternalBiosample(any[ExternalBiosampleRequest]))
        .thenReturn(Future.successful(testGuid))

      val request = FakeRequest(POST, "/api/private/external/biosamples")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(validRequestJson)

      val result = route(app, request).get

      status(result) mustBe CREATED
      verify(mockDomainService).createExternalBiosample(any[ExternalBiosampleRequest])
    }
  }
}
