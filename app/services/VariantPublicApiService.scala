package services

import jakarta.inject.{Inject, Singleton}
import models.api.*
import models.domain.genomics.VariantV2
import play.api.cache.AsyncCacheApi
import play.api.libs.json.JsObject
import repositories.{HaplogroupVariantRepository, VariantV2Repository}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for the public Variant API.
 * Transforms internal VariantV2 models to forward-compatible API DTOs.
 * Results are cached for performance.
 *
 * With the consolidated VariantV2 schema, this service is much simpler:
 * - No grouping logic needed (variants are already consolidated)
 * - Aliases are embedded in JSONB (no separate repository)
 * - Coordinates for all assemblies are in one row
 */
@Singleton
class VariantPublicApiService @Inject()(
  variantV2Repository: VariantV2Repository,
  haplogroupVariantRepository: HaplogroupVariantRepository,
  cache: AsyncCacheApi
)(implicit ec: ExecutionContext) {

  private val SearchCacheDuration = 10.minutes
  private val DetailCacheDuration = 30.minutes

  /**
   * Search variants with pagination, returning API DTOs.
   */
  def searchVariants(query: Option[String], page: Int, pageSize: Int): Future[VariantSearchResponse] = {
    val cacheKey = s"api-variant-search:${query.getOrElse("").toLowerCase.trim}:$page:$pageSize"

    cache.getOrElseUpdate(cacheKey, SearchCacheDuration) {
      val offset = (page - 1) * pageSize

      for {
        (variants, totalCount) <- variantV2Repository.searchPaginated(query.getOrElse(""), offset, pageSize)
        dtos <- Future.traverse(variants)(variantToDto)
      } yield {
        val totalPages = Math.max(1, ((totalCount + pageSize - 1) / pageSize))
        VariantSearchResponse(
          items = dtos,
          currentPage = page,
          pageSize = pageSize,
          totalItems = totalCount,
          totalPages = totalPages
        )
      }
    }
  }

  /**
   * Get a single variant by ID.
   */
  def getVariantById(variantId: Int): Future[Option[PublicVariantDTO]] = {
    val cacheKey = s"api-variant-detail:$variantId"

    cache.getOrElseUpdate(cacheKey, DetailCacheDuration) {
      for {
        variantOpt <- variantV2Repository.findById(variantId)
        result <- variantOpt match {
          case Some(variant) =>
            for {
              haplogroups <- haplogroupVariantRepository.getHaplogroupsByVariant(variantId)
            } yield Some(buildDto(variant, haplogroups.headOption))
          case None =>
            Future.successful(None)
        }
      } yield result
    }
  }

  /**
   * Get variants defining a specific haplogroup.
   */
  def getVariantsByHaplogroup(haplogroupName: String): Future[Seq[PublicVariantDTO]] = {
    val cacheKey = s"api-variants-by-haplogroup:$haplogroupName"

    cache.getOrElseUpdate(cacheKey, DetailCacheDuration) {
      haplogroupVariantRepository.getVariantsByHaplogroupName(haplogroupName).map { variants =>
        variants.map(v => buildDto(v, None)) // Haplogroup already known from context
      }
    }
  }

  /**
   * Transform a VariantV2 to a PublicVariantDTO.
   */
  private def variantToDto(variant: VariantV2): Future[PublicVariantDTO] = {
    val variantId = variant.variantId.getOrElse(0)

    for {
      haplogroups <- if (variantId > 0) haplogroupVariantRepository.getHaplogroupsByVariant(variantId) else Future.successful(Seq.empty)
    } yield buildDto(variant, haplogroups.headOption)
  }

  /**
   * Build the DTO from a VariantV2.
   *
   * With VariantV2, the transformation is straightforward:
   * - Coordinates come directly from JSONB
   * - Aliases come directly from JSONB
   * - No grouping or joining needed
   */
  private def buildDto(
    variant: VariantV2,
    definingHaplogroup: Option[models.domain.haplogroups.Haplogroup]
  ): PublicVariantDTO = {

    // Extract coordinates from JSONB - one entry per reference genome
    val coordinates: Map[String, VariantCoordinateDTO] = variant.coordinates.asOpt[Map[String, JsObject]].map { coordsMap =>
      coordsMap.flatMap { case (refGenome, coords) =>
        for {
          contig <- (coords \ "contig").asOpt[String]
          position <- (coords \ "position").asOpt[Int]
          ref <- (coords \ "ref").asOpt[String]
          alt <- (coords \ "alt").asOpt[String]
        } yield refGenome -> VariantCoordinateDTO(
          contig = contig,
          position = position,
          ref = ref,
          alt = alt
        )
      }
    }.getOrElse(Map.empty)

    // Extract aliases from JSONB
    val aliasesDto = buildAliasesDto(variant)

    // Build defining haplogroup DTO
    val definingHaplogroupDto = definingHaplogroup.map { hg =>
      DefiningHaplogroupDTO(
        haplogroupId = hg.id.get,
        haplogroupName = hg.name
      )
    }

    PublicVariantDTO(
      variantId = variant.variantId.getOrElse(0),
      canonicalName = variant.canonicalName,
      variantType = variant.mutationType.dbValue,
      namingStatus = variant.namingStatus.dbValue,
      coordinates = coordinates,
      aliases = aliasesDto,
      definingHaplogroup = definingHaplogroupDto
    )
  }

  /**
   * Build aliases DTO from VariantV2 JSONB aliases field.
   */
  private def buildAliasesDto(variant: VariantV2): VariantAliasesDTO = {
    val aliases = variant.aliases

    // Extract common_names array
    val commonNames = (aliases \ "common_names").asOpt[Seq[String]].getOrElse(Seq.empty)

    // Extract rs_ids array
    val rsIds = (aliases \ "rs_ids").asOpt[Seq[String]].getOrElse(Seq.empty)

    // Extract sources map
    val sources = (aliases \ "sources").asOpt[Map[String, Seq[String]]].getOrElse(Map.empty)

    // Include canonical name if not already in common_names
    val allCommonNames = variant.canonicalName match {
      case Some(name) if !commonNames.contains(name) => name +: commonNames
      case _ => commonNames
    }

    VariantAliasesDTO(
      commonNames = allCommonNames,
      rsIds = rsIds,
      sources = sources
    )
  }
}
