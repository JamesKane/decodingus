package controllers

import jakarta.inject.{Inject, Singleton}
import org.webjars.play.WebJarsUtil
import play.api.i18n.I18nSupport
import play.api.mvc.*
import repositories.{HaplogroupVariantRepository, VariantAliasRepository, VariantRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Public controller for browsing variants (read-only).
 * Provides a searchable variant database for researchers.
 */
@Singleton
class VariantBrowserController @Inject()(
                                          val controllerComponents: ControllerComponents,
                                          variantRepository: VariantRepository,
                                          variantAliasRepository: VariantAliasRepository,
                                          haplogroupVariantRepository: HaplogroupVariantRepository
                                        )(using webJarsUtil: WebJarsUtil, ec: ExecutionContext)
  extends BaseController with I18nSupport {

  private val DefaultPageSize = 25

  /**
   * Main variant browser page with search functionality.
   */
  def index(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] = Action.async {
    implicit request: Request[AnyContent] =>
      val offset = (page - 1) * pageSize
      for {
        (variantGroups, totalCount) <- variantRepository.searchGroupedPaginated(query.getOrElse(""), offset, pageSize)
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.variants.browser(variantGroups, query, page, totalPages, pageSize, totalCount))
      }
  }

  /**
   * HTMX fragment for variant list updates (search/pagination).
   */
  def listFragment(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] = Action.async {
    implicit request: Request[AnyContent] =>
      val offset = (page - 1) * pageSize
      for {
        (variantGroups, totalCount) <- variantRepository.searchGroupedPaginated(query.getOrElse(""), offset, pageSize)
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.variants.listFragment(variantGroups, query, page, totalPages, pageSize, totalCount))
      }
  }

  /**
   * HTMX fragment for variant detail panel (read-only).
   */
  def detailPanel(id: Int): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    for {
      variantOpt <- variantRepository.findByIdWithContig(id)
      allVariantsInGroup <- variantOpt match {
        case Some(vwc) =>
          val groupKey = vwc.variant.commonName.orElse(vwc.variant.rsId).getOrElse(s"variant_${id}")
          variantRepository.getVariantsByGroupKey(groupKey)
        case None => Future.successful(Seq.empty)
      }
      aliases <- variantAliasRepository.findByVariantId(id)
      haplogroups <- haplogroupVariantRepository.getHaplogroupsByVariant(id)
    } yield {
      variantOpt match {
        case Some(variantWithContig) =>
          val variantGroup = variantRepository.groupVariants(allVariantsInGroup).headOption
          Ok(views.html.variants.detailPanel(variantWithContig, variantGroup, aliases, haplogroups))
        case None =>
          NotFound("Variant not found")
      }
    }
  }
}
