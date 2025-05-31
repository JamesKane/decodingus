package actions

import filters.ApiKeyFilter
import play.api.libs.json.{JsError, Json}
import play.api.mvc.*
import play.api.mvc.Results.BadRequest
import play.mvc.Action

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SecureApiAction @Inject()(
                                 apiKeyFilter: ApiKeyFilter,
                                 val defaultParser: BodyParsers.Default,
                                 val controllerComponents: ControllerComponents
                               )(implicit val executionContext: ExecutionContext) extends ActionBuilder[Request, AnyContent] {

  // Implement required parser member
  override def parser: BodyParser[AnyContent] = defaultParser

  // Implement required executionContext member
  // We can use the implicit ec directly since we made it a val

  def jsonAction[A](implicit reader: play.api.libs.json.Reads[A]): ActionBuilder[Request, A] = {
    new ActionBuilder[Request, A] {
      override def parser: BodyParser[A] = controllerComponents.parsers.json.validate(
        _.validate[A].asEither.left.map(e => BadRequest(Json.obj("message" -> JsError.toJson(e))))
      )

      override protected def executionContext: ExecutionContext = SecureApiAction.this.executionContext

      override def invokeBlock[B](request: Request[B], block: Request[B] => Future[Result]): Future[Result] = {
        apiKeyFilter.filter(request).flatMap {
          case Some(result) => Future.successful(result)
          case None => block(request)
        }
      }
    }
  }


  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    apiKeyFilter.filter(request).flatMap {
      case Some(result) => Future.successful(result)
      case None => block(request)
    }
  }
}
