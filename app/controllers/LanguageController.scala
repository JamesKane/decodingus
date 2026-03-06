package controllers

import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc.*

import javax.inject.*

@Singleton
class LanguageController @Inject()(val controllerComponents: ControllerComponents)
  extends BaseController with I18nSupport {

  def switchLanguage(lang: String): Action[AnyContent] = Action { implicit request =>
    val referer = request.headers.get(REFERER).getOrElse("/")
    val supportedLangs = messagesApi.messages.keys.filter(_ != "default").toSet

    if (supportedLangs.contains(lang)) {
      Redirect(referer).withLang(Lang(lang))
    } else {
      Redirect(referer)
    }
  }
}
