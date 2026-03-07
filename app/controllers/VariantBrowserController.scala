package controllers

import jakarta.inject.{Inject, Singleton}
import org.webjars.play.WebJarsUtil
import play.api.cache.AsyncCacheApi
import play.api.i18n.I18nSupport
import play.api.mvc.*
import services.VariantBrowserService

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * Public controller for browsing variants (read-only).
 * Provides a searchable variant database for researchers.
 * Results are cached to improve performance for the public view.
 */
@Singleton
class VariantBrowserController @Inject()(
    val controllerComponents: ControllerComponents,
    variantBrowserService: VariantBrowserService,
    cache: AsyncCacheApi
)(using webJarsUtil: WebJarsUtil, ec: ExecutionContext)
  extends BaseController with I18nSupport {

  private val DefaultPageSize = 25

  // Cache durations - public view can be stale
  private val SearchCacheDuration = 15.minutes
  private val DetailCacheDuration = 1.hour

  /**
   * Main variant browser page with search functionality.
   */
  def index(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] = Action {
    implicit request: Request[AnyContent] =>
      Ok(views.html.variants.browser(query, page, pageSize))
  }

  /**
   * HTMX fragment for variant list updates (search/pagination).
   */
  def listFragment(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] = Action.async {
    implicit request: Request[AnyContent] =>
      val safePage = Math.max(1, page)
      val safePageSize = Math.max(1, Math.min(pageSize, 100))
      val offset = (safePage - 1) * safePageSize
      for {
        (variants, totalCount) <- getCachedSearchResults(query.getOrElse(""), offset, safePageSize)
      } yield {
        val totalPages = Math.max(1, (totalCount + safePageSize - 1) / safePageSize)
        Ok(views.html.variants.listFragment(variants, query, safePage, totalPages, safePageSize, totalCount))
      }
  }

  /**
   * HTMX fragment for variant detail panel (read-only).
   */
  def detailPanel(id: Int): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    getCachedDetailPanel(id)
  }

  // === Caching helpers ===

  /**
   * Get cached search results or fetch from service.
   */
  private def getCachedSearchResults(query: String, offset: Int, limit: Int): Future[(Seq[models.domain.genomics.VariantV2], Int)] = {
    val cacheKey = s"variant-browser:${query.toLowerCase.trim}:$offset:$limit"
    cache.getOrElseUpdate(cacheKey, SearchCacheDuration) {
      variantBrowserService.searchPaginated(query, offset, limit)
    }
  }

  /**
   * Get cached detail panel or fetch from service.
   */
  private def getCachedDetailPanel(id: Int)(implicit request: Request[AnyContent]): Future[Result] = {
    val cacheKey = s"variant-browser-detail:$id"
    cache.getOrElseUpdate(cacheKey, DetailCacheDuration) {
      variantBrowserService.getVariantWithHaplogroups(id).map {
        case Some((variant, haplogroups)) =>
          Ok(views.html.variants.detailPanel(variant, haplogroups))
        case None =>
          NotFound("Variant not found")
      }
    }
  }
}
