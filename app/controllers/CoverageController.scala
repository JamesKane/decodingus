package controllers

import actions.ApiSecurityAction
import jakarta.inject.Singleton
import models.api.LibraryStatsRequest
import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}
import repositories.CoverageRepository
import services.CoverageService

import javax.inject.Inject
import scala.concurrent.ExecutionContext

@Singleton
class CoverageController @Inject()(
                                    val controllerComponents: ControllerComponents,
                                    coverageService: CoverageService,
                                    coverageRepository: CoverageRepository,
                                    secureApi: ApiSecurityAction
                                  )(using webJarsUtil: WebJarsUtil, ec: ExecutionContext) extends BaseController {

  def addCoverage(): Action[LibraryStatsRequest] = secureApi.jsonAction[LibraryStatsRequest].async { implicit request =>
    coverageService.addCoverage(request.body).map { _ =>
      Ok(Json.obj("message" -> "Coverage data added successfully."))
    }
  }

  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.coverage())
  }

  def apiBenchmarks(): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    coverageRepository.getBenchmarkStatistics.map { benchmarks =>
      Ok(Json.toJson(benchmarks))
    }
  }

  def labs = Action.async { implicit request =>
    coverageRepository.getAllLabs.map { labs =>
      Ok(Json.toJson(labs))
    }
  }

  def benchmarksByLab(labId: Int) = Action.async { implicit request =>
    coverageRepository.getBenchmarksByLab(labId).map { benchmarks =>
      Ok(views.html.fragments.coverageBenchmarks(benchmarks, None))
    }
  }

  def benchmarksByLabWithDetails(labId: Int) = Action.async { implicit request =>
    for {
      labs <- coverageRepository.getAllLabs
      benchmarks <- coverageRepository.getBenchmarksByLab(labId)
    } yield {
      val lab = labs.find(_.id.contains(labId))
      Ok(views.html.fragments.coverageBenchmarks(benchmarks, lab))
    }
  }
}
