package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.VariantV2
import models.domain.haplogroups.Haplogroup
import repositories.{HaplogroupVariantRepository, VariantV2Repository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for variant browser functionality.
 * Abstracts repository access and provides business logic for the variant browser UI.
 */
@Singleton
class VariantBrowserService @Inject()(
  variantV2Repository: VariantV2Repository,
  haplogroupVariantRepository: HaplogroupVariantRepository
)(implicit ec: ExecutionContext) {

  /**
   * Search variants with pagination.
   *
   * @param query      Search query string
   * @param offset     Pagination offset
   * @param limit      Page size
   * @return Tuple of (variants, total count)
   */
  def searchPaginated(query: String, offset: Int, limit: Int): Future[(Seq[VariantV2], Int)] = {
    variantV2Repository.searchPaginated(query, offset, limit)
  }

  /**
   * Get variant detail with associated haplogroups.
   * Fetches variant and haplogroups in a single method call.
   *
   * @param variantId The variant ID
   * @return Option of (variant, haplogroups) tuple, None if variant not found
   */
  def getVariantWithHaplogroups(variantId: Int): Future[Option[(VariantV2, Seq[Haplogroup])]] = {
    variantV2Repository.findById(variantId).flatMap {
      case Some(variant) =>
        haplogroupVariantRepository.getHaplogroupsByVariant(variantId).map { haplogroups =>
          Some((variant, haplogroups))
        }
      case None =>
        Future.successful(None)
    }
  }
}
