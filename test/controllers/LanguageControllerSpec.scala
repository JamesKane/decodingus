package controllers

import helpers.ServiceSpec
import play.api.i18n.{Lang, MessagesApi}
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers.*

class LanguageControllerSpec extends ServiceSpec {

  val controller = new LanguageController(Helpers.stubControllerComponents())

  "LanguageController.switchLanguage" should {

    "redirect to referer with language cookie for English" in {
      val request = FakeRequest("GET", "/language/en")
        .withHeaders("Referer" -> "/ytree")
      val result = controller.switchLanguage("en").apply(request)
      status(result) mustBe SEE_OTHER
      redirectLocation(result) mustBe Some("/ytree")
    }

    "redirect to referer with language cookie for French" in {
      val request = FakeRequest("GET", "/language/fr")
        .withHeaders("Referer" -> "/references")
      val result = controller.switchLanguage("fr").apply(request)
      status(result) mustBe SEE_OTHER
      redirectLocation(result) mustBe Some("/references")
    }

    "redirect to referer with language cookie for Spanish" in {
      val request = FakeRequest("GET", "/language/es")
        .withHeaders("Referer" -> "/")
      val result = controller.switchLanguage("es").apply(request)
      status(result) mustBe SEE_OTHER
      redirectLocation(result) mustBe Some("/")
    }

    "redirect to root when no referer is present" in {
      val request = FakeRequest("GET", "/language/en")
      val result = controller.switchLanguage("en").apply(request)
      status(result) mustBe SEE_OTHER
      redirectLocation(result) mustBe Some("/")
    }

    "redirect without error for unsupported language" in {
      val request = FakeRequest("GET", "/language/de")
        .withHeaders("Referer" -> "/ytree")
      val result = controller.switchLanguage("de").apply(request)
      status(result) mustBe SEE_OTHER
      redirectLocation(result) mustBe Some("/ytree")
    }
  }
}
