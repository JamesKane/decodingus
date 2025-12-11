package services

import jakarta.inject.{Inject, Singleton}
import models.api.*
import models.domain.genomics.VariantGroup
import play.api.cache.AsyncCacheApi
import repositories.{HaplogroupVariantRepository, VariantAliasRepository, VariantRepository}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for the public Variant API.
 * Transforms internal data models to forward-compatible API DTOs.
 * Results are cached for performance.
 */
@Singleton
class VariantPublicApiService @Inject()(
                                         variantRepository: VariantRepository,
                                         variantAliasRepository: VariantAliasRepository,
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
        (groups, totalCount) <- variantRepository.searchGroupedPaginated(query.getOrElse(""), offset, pageSize)
        dtos <- Future.traverse(groups)(groupToDto)
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
        variantOpt <- variantRepository.findByIdWithContig(variantId)
        result <- variantOpt match {
          case Some(vwc) =>
            // Get all variants in the same group (different builds)
            val groupKey = vwc.variant.commonName.orElse(vwc.variant.rsId).getOrElse(s"variant_$variantId")
            for {
              allBuilds <- variantRepository.getVariantsByGroupKey(groupKey)
              aliases <- variantAliasRepository.findByVariantId(variantId)
              haplogroups <- haplogroupVariantRepository.getHaplogroupsByVariant(variantId)
            } yield {
              val group = variantRepository.groupVariants(allBuilds).headOption
              group.map(g => buildDto(g, aliases, haplogroups.headOption))
            }
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
      for {
        variants <- haplogroupVariantRepository.getVariantsByHaplogroupName(haplogroupName)
        dtos <- Future.traverse(variants) { vwc =>
          for {
            aliases <- variantAliasRepository.findByVariantId(vwc.variant.variantId.get)
          } yield {
            // Single build variant - create a minimal group
            val singleGroup = VariantGroup(
              groupKey = vwc.variant.commonName.orElse(vwc.variant.rsId).getOrElse(s"variant_${vwc.variant.variantId.get}"),
              variants = Seq(vwc),
              rsId = vwc.variant.rsId,
              commonName = vwc.variant.commonName
            )
            buildDto(singleGroup, aliases, None) // Haplogroup already known from context
          }
        }
      } yield dtos
    }
  }

  /**
   * Transform a VariantGroup to a PublicVariantDTO.
   */
  private def groupToDto(group: VariantGroup): Future[PublicVariantDTO] = {
    val primaryVariantId = group.variants.headOption.flatMap(_.variant.variantId).getOrElse(0)

    for {
      aliases <- if (primaryVariantId > 0) variantAliasRepository.findByVariantId(primaryVariantId) else Future.successful(Seq.empty)
      haplogroups <- if (primaryVariantId > 0) haplogroupVariantRepository.getHaplogroupsByVariant(primaryVariantId) else Future.successful(Seq.empty)
    } yield buildDto(group, aliases, haplogroups.headOption)
  }

  /**
   * Build the DTO from domain objects.
   */
  private def buildDto(
                        group: VariantGroup,
                        aliases: Seq[models.dal.domain.genomics.VariantAlias],
                        definingHaplogroup: Option[models.domain.haplogroups.Haplogroup]
                      ): PublicVariantDTO = {

    val primaryVariant = group.variants.headOption.map(_.variant)
    val primaryVariantId = primaryVariant.flatMap(_.variantId).getOrElse(0)

    // Build coordinates map from all builds
    val coordinates: Map[String, VariantCoordinateDTO] = group.variants.flatMap { vwc =>
      vwc.contig.referenceGenome.map { refGenome =>
        // Normalize reference genome name (e.g., "GRCh38.p14" -> "GRCh38")
        val shortRef = refGenome.split("\\.").head
        shortRef -> VariantCoordinateDTO(
          contig = vwc.contig.commonName.getOrElse(vwc.contig.accession),
          position = vwc.variant.position,
          ref = vwc.variant.referenceAllele,
          alt = vwc.variant.alternateAllele
        )
      }
    }.toMap

    // Build aliases DTO
    val aliasesDto = buildAliasesDto(aliases, primaryVariant)

    // Determine naming status based on current data
    val namingStatus = (group.commonName, group.rsId) match {
      case (Some(_), _) => "NAMED"
      case (None, Some(_)) => "NAMED"  // rsId counts as named
      case (None, None) => "UNNAMED"
    }

    // Build defining haplogroup DTO
    val definingHaplogroupDto = definingHaplogroup.map { hg =>
      DefiningHaplogroupDTO(
        haplogroupId = hg.id.get,
        haplogroupName = hg.name
      )
    }

    PublicVariantDTO(
      variantId = primaryVariantId,
      canonicalName = group.commonName.orElse(group.rsId),
      variantType = primaryVariant.map(_.variantType).getOrElse("SNP"),
      namingStatus = namingStatus,
      coordinates = coordinates,
      aliases = aliasesDto,
      definingHaplogroup = definingHaplogroupDto
    )
  }

  /**
   * Build aliases DTO from alias records.
   */
  private def buildAliasesDto(
                               aliases: Seq[models.dal.domain.genomics.VariantAlias],
                               primaryVariant: Option[models.dal.domain.genomics.Variant]
                             ): VariantAliasesDTO = {
    // Group aliases by type
    val byType = aliases.groupBy(_.aliasType)

    val commonNames = byType.getOrElse("common_name", Seq.empty).map(_.aliasValue).distinct
    val rsIds = byType.getOrElse("rs_id", Seq.empty).map(_.aliasValue).distinct

    // Group by source
    val bySource = aliases.groupBy(_.source.getOrElse("unknown")).map {
      case (source, sourceAliases) => source -> sourceAliases.map(_.aliasValue).distinct
    }

    // Include primary variant names if not already in aliases
    val allCommonNames = (commonNames ++ primaryVariant.flatMap(_.commonName).toSeq).distinct
    val allRsIds = (rsIds ++ primaryVariant.flatMap(_.rsId).toSeq).distinct

    VariantAliasesDTO(
      commonNames = allCommonNames,
      rsIds = allRsIds,
      sources = bySource
    )
  }
}
