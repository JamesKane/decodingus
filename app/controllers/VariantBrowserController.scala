package controllers

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.VariantV2
import models.domain.haplogroups.Haplogroup
import org.webjars.play.WebJarsUtil
import play.api.cache.AsyncCacheApi
import play.api.i18n.I18nSupport
import play.api.mvc.*
import repositories.{HaplogroupVariantRepository, VariantV2Repository}

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
    variantRepository: VariantV2Repository,
    haplogroupVariantRepository: HaplogroupVariantRepository,
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
      val offset = (page - 1) * pageSize
      for {
        (variants, totalCount) <- getCachedSearchResults(query.getOrElse(""), offset, pageSize)
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.variants.listFragment(variants, query, page, totalPages, pageSize, totalCount))
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
   * Get cached search results or fetch from database.
   */
  private def getCachedSearchResults(query: String, offset: Int, limit: Int): Future[(Seq[VariantV2], Int)] = {
    val cacheKey = s"variant-browser:${query.toLowerCase.trim}:$offset:$limit"
    cache.getOrElseUpdate(cacheKey, SearchCacheDuration) {
      variantRepository.searchPaginated(query, offset, limit)
    }
  }

  /**
   * Get cached detail panel or fetch from database.
   */
  private def getCachedDetailPanel(id: Int)(implicit request: Request[AnyContent]): Future[Result] = {
    val cacheKey = s"variant-browser-detail:$id"
    cache.getOrElseUpdate(cacheKey, DetailCacheDuration) {
      for {
        variantOpt <- variantRepository.findById(id)
        haplogroups <- haplogroupVariantRepository.getHaplogroupsByVariant(id)
      } yield {
        variantOpt match {
          case Some(variant) =>
            Ok(views.html.variants.detailPanel(variant, haplogroups))
          case None =>
            NotFound("Variant not found")
        }
      }
    }
  }
}
