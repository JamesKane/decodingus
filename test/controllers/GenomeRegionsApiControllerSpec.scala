package controllers

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.test.*
import play.api.test.Helpers.*
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json

class GenomeRegionsApiControllerSpec extends PlaySpec with GuiceOneAppPerSuite with Injecting {

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure("play.evolutions.enabled" -> false)
      .build()
  }

  "GenomeRegionsApiController" should {

    "return list of supported builds" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions")
      val result = route(app, request).get

      status(result) mustBe OK
      contentType(result) mustBe Some("application/json")

      val json = contentAsJson(result)
      (json \ "supportedBuilds").as[Seq[String]] must contain allOf ("GRCh37", "GRCh38", "hs1")
    }

    "return 404 for unknown build" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/unknown_build")
      val result = route(app, request).get

      status(result) mustBe NOT_FOUND
      contentType(result) mustBe Some("application/json")

      val json = contentAsJson(result)
      (json \ "error").as[String] mustBe "Unknown build"
      (json \ "supportedBuilds").as[Seq[String]] must contain allOf ("GRCh37", "GRCh38", "hs1")
    }

    "return genome regions for GRCh38" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/GRCh38")
      val result = route(app, request).get

      status(result) mustBe OK
      contentType(result) mustBe Some("application/json")

      val json = contentAsJson(result)
      (json \ "build").as[String] mustBe "GRCh38"
      (json \ "version").asOpt[String] mustBe defined
      (json \ "generatedAt").asOpt[String] mustBe defined
      // chromosomes is present as an object
      (json \ "chromosomes").toOption mustBe defined
    }

    "resolve hg38 alias to GRCh38" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/hg38")
      val result = route(app, request).get

      status(result) mustBe OK

      val json = contentAsJson(result)
      (json \ "build").as[String] mustBe "GRCh38"
    }

    "resolve hg19 alias to GRCh37" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/hg19")
      val result = route(app, request).get

      status(result) mustBe OK

      val json = contentAsJson(result)
      (json \ "build").as[String] mustBe "GRCh37"
    }

    "resolve chm13 alias to hs1" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/chm13")
      val result = route(app, request).get

      status(result) mustBe OK

      val json = contentAsJson(result)
      (json \ "build").as[String] mustBe "hs1"
    }

    "include Cache-Control header" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/GRCh38")
      val result = route(app, request).get

      status(result) mustBe OK
      val responseHeaders = headers(result)
      responseHeaders.get("Cache-Control") mustBe defined
      responseHeaders("Cache-Control") must include("max-age=")
    }

    "include Vary header for content negotiation" in {
      val request = FakeRequest(GET, "/api/v1/genome-regions/GRCh38")
      val result = route(app, request).get

      status(result) mustBe OK
      val responseHeaders = headers(result)
      responseHeaders.get("Vary") mustBe defined
      responseHeaders("Vary") must include("Accept-Encoding")
    }
  }
}
