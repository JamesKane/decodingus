package controllers

import jakarta.inject.Singleton
import models.domain.genomics.CoverageBenchmark
import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.*
import repositories.CoverageRepository

import javax.inject.Inject
import scala.concurrent.ExecutionContext

/**
 * Controller responsible for managing endpoints related to coverage information and benchmarks.
 *
 * @param controllerComponents Controller components used for handling HTTP-related functionality.
 * @param coverageRepository   Repository for accessing coverage benchmark data.
 * @param webJarsUtil          Utility for managing WebJars in Play framework.
 * @param ec                   Execution context for asynchronous operations.
 */
@Singleton
class CoverageController @Inject()(
                                    val controllerComponents: ControllerComponents,
                                    coverageRepository: CoverageRepository
                                  )(using webJarsUtil: WebJarsUtil, ec: ExecutionContext) extends BaseController {

  /**
   * Handles an HTTP GET request to render the coverage benchmarks page.
   *
   * @return an Action that, when executed, returns an HTTP OK response containing the rendered HTML for the
   *         coverage benchmarks view.
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.coverage())
  }

  /**
   * Handles an HTTP GET request to return benchmark information in JSON format.
   *
   * Returns aggregated coverage statistics grouped by lab, test type, and contig.
   * The standard deviation values are provided to calculate 95% confidence intervals
   * when there is more than one sample in the group.
   *
   * @return An HTTP OK response containing a JSON array of coverage benchmark objects.
   */
  def apiBenchmarks(): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    coverageRepository.getBenchmarkStatistics.map { benchmarks =>
      Ok(Json.toJson(benchmarks))
    }
  }

  /**
   * Handles an HTTP GET request to retrieve a list of sequencing labs in JSON format.
   *
   * This method queries the `coverageRepository` to fetch all sequencing labs,
   * sorts them by their name, and maps their details into a JSON response.
   * It returns an HTTP OK response containing a JSON array of sequencing lab objects.
   *
   * @return An asynchronous Action that produces an HTTP OK response with the lab data serialized into JSON.
   */
  def labs = Action.async { implicit request =>
    coverageRepository.getAllLabs.map { labs =>
      Ok(Json.toJson(labs))
    }
  }

  /**
   * Handles an HTTP GET request to fetch benchmarks for a specific lab.
   * The method queries the `coverageRepository` to retrieve the coverage benchmark data
   * corresponding to the given lab and renders it as an HTML fragment.
   *
   * @param labId The unique identifier of the sequencing lab for which coverage benchmark data is requested.
   * @return An asynchronous action that produces an HTTP OK response containing the rendered HTML fragment
   *         with the benchmarks data.
   */
  def benchmarksByLab(labId: Int) = Action.async { implicit request =>
    coverageRepository.getBenchmarksByLab(labId).map { benchmarks =>
      Ok(views.html.fragments.coverageBenchmarks(benchmarks, None))
    }
  }

  /**
   * Handles an HTTP GET request to fetch benchmark details for a specific lab.
   * The method queries the `coverageRepository` to retrieve all labs and the specific
   * benchmarks corresponding to the provided lab ID, and renders it as an HTML fragment
   * displaying the benchmark details along with lab information.
   *
   * @param labId The unique identifier of the sequencing lab for which benchmark details are requested.
   * @return An asynchronous action that produces an HTTP OK response containing the rendered HTML fragment
   *         with detailed benchmark data and lab information.
   */
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