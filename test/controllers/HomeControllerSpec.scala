package controllers

import org.scalatestplus.play.*
import org.scalatestplus.play.guice.*
import play.api.test.*
import play.api.test.Helpers.*
import org.scalatest.BeforeAndAfterAll
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

class HomeControllerSpec extends PlaySpec with GuiceOneAppPerSuite with Injecting with BeforeAndAfterAll {

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure("play.evolutions.enabled" -> false)
      .build()
  }

  "HomeController" should {

    "render the index page" in {
      val request = FakeRequest(GET, "/")
      val home = route(app, request).get

      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include ("Decoding Us")
    }

    "render the cookie usage page" in {
      val request = FakeRequest(GET, "/cookie-usage")
      val page = route(app, request).get

      status(page) mustBe OK
      contentType(page) mustBe Some("text/html")
      contentAsString(page) must include ("Cookie Usage")
    }

    "render the privacy policy page" in {
      val request = FakeRequest(GET, "/privacy")
      val page = route(app, request).get

      status(page) mustBe OK
      contentType(page) mustBe Some("text/html")
      contentAsString(page) must include ("Privacy Policy")
    }

    "render the terms of use page" in {
      val request = FakeRequest(GET, "/terms")
      val page = route(app, request).get

      status(page) mustBe OK
      contentType(page) mustBe Some("text/html")
      contentAsString(page) must include ("Terms of Use")
    }

    "render the faq page" in {
      val request = FakeRequest(GET, "/faq")
      val page = route(app, request).get

      status(page) mustBe OK
      contentType(page) mustBe Some("text/html")
      contentAsString(page) must include ("Frequently Asked Questions")
    }

    "generate a sitemap" in {
      val request = FakeRequest(GET, "/sitemap.xml").withHeaders("X-Forwarded-Proto" -> "https")
      val sitemap = route(app, request).get

      status(sitemap) mustBe OK
      contentType(sitemap) mustBe Some("application/xml")
      val content = contentAsString(sitemap)
      content must startWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
      content must include ("<urlset")
      content must include (s"<loc>https://${request.host}/</loc>")
      content must include ("</urlset>")
    }

    "generate a robots.txt" in {
      val request = FakeRequest(GET, "/robots.txt").withHeaders("X-Forwarded-Proto" -> "https")
      val robots = route(app, request).get

      status(robots) mustBe OK
      contentType(robots) mustBe Some("text/plain")
      val content = contentAsString(robots)
      content must include ("User-agent: *")
      content must include (s"Sitemap: https://${request.host}/sitemap.xml")
    }
  }
}
