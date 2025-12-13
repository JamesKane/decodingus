package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.HaplogroupType.{MT, Y}
import models.api.*
import models.domain.genomics.VariantV2
import models.domain.haplogroups.Haplogroup
import play.api.Logging
import play.api.libs.json.JsObject
import play.api.mvc.Call
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository}

import java.time.ZoneId
import scala.concurrent.{ExecutionContext, Future}

sealed trait RouteType
case object ApiRoute extends RouteType
case object FragmentRoute extends RouteType

/**
 * Service for building and managing haplogroup trees, providing capabilities for constructing tree responses,
 * processing ancestral and descendant relationships, and querying haplogroups by variants.
 */
class HaplogroupTreeService @Inject()(
  coreRepository: HaplogroupCoreRepository,
  variantRepository: HaplogroupVariantRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Builds a TreeDTO representation for a specified haplogroup with related breadcrumbs and subtree.
   */
  def buildTreeResponse(haplogroupName: String, haplogroupType: HaplogroupType, routeType: RouteType): Future[TreeDTO] = {
    for {
      rootHaplogroupOpt <- coreRepository.getHaplogroupByName(haplogroupName, haplogroupType)
      rootHaplogroup = rootHaplogroupOpt.getOrElse(throw new IllegalArgumentException(s"Haplogroup $haplogroupName not found"))

      ancestors <- coreRepository.getAncestors(rootHaplogroup.id.get)
      crumbs = buildCrumbs(ancestors, haplogroupType, routeType)

      subtree <- routeType match {
        case ApiRoute => buildSubtree(rootHaplogroup)
        case FragmentRoute => buildSubtreeWithoutVariants(rootHaplogroup)
      }

    } yield TreeDTO(
      name = rootHaplogroup.name,
      crumbs = crumbs,
      subclade = Some(subtree)
    )
  }

  private def getRoute(name: String, haplogroupType: HaplogroupType, routeType: RouteType): Call = {
    (haplogroupType, routeType) match {
      case (Y, FragmentRoute) => controllers.routes.TreeController.yTreeFragment(Some(name))
      case (MT, FragmentRoute) => controllers.routes.TreeController.mTreeFragment(Some(name))
      case (Y, ApiRoute) => controllers.routes.TreeController.apiYTree(Some(name))
      case (MT, ApiRoute) => controllers.routes.TreeController.apiMTree(Some(name))
    }
  }

  private def buildCrumbs(haplogroups: Seq[Haplogroup], haplogroupType: HaplogroupType, routeType: RouteType): List[CrumbDTO] = {
    haplogroups.map { haplogroup =>
      CrumbDTO(
        label = haplogroup.name,
        url = getRoute(haplogroup.name, haplogroupType, routeType).url
      )
    }.toList
  }

  /**
   * Recursively builds a TreeNodeDTO representation of a haplogroup and its subtree.
   */
  private def buildSubtree(haplogroup: Haplogroup): Future[TreeNodeDTO] = {
    for {
      // Get variants for this haplogroup (now returns Seq[VariantV2])
      variants <- variantRepository.getHaplogroupVariants(haplogroup.id.get)
      variantDTOs = mapVariants(variants)

      // Get and process children
      children <- coreRepository.getDirectChildren(haplogroup.id.get)
      childNodes <- Future.sequence(children.map(buildSubtree))

    } yield TreeNodeDTO(
      name = haplogroup.name,
      variants = variantDTOs,
      children = childNodes.toList,
      updated = haplogroup.validFrom.atZone(ZoneId.systemDefault()),
      isBackbone = haplogroup.source == "backbone",
      formedYbp = haplogroup.formedYbp,
      tmrcaYbp = haplogroup.tmrcaYbp
    )
  }

  private def buildSubtreeWithoutVariants(haplogroup: Haplogroup): Future[TreeNodeDTO] = {
    for {
      variantCount <- variantRepository.countHaplogroupVariants(haplogroup.id.get)
      children <- coreRepository.getDirectChildren(haplogroup.id.get)
      childNodes <- Future.sequence(children.map(buildSubtreeWithoutVariants))
    } yield TreeNodeDTO(
      name = haplogroup.name,
      variants = Seq.empty,
      variantCount = Some(variantCount),
      children = childNodes.toList,
      updated = haplogroup.validFrom.atZone(ZoneId.systemDefault()),
      isBackbone = haplogroup.source == "backbone",
      formedYbp = haplogroup.formedYbp,
      tmrcaYbp = haplogroup.tmrcaYbp
    )
  }

  /**
   * Maps VariantV2 instances to VariantDTO.
   * With VariantV2, aliases and coordinates are embedded in JSONB.
   */
  private def mapVariants(variants: Seq[VariantV2]): Seq[VariantDTO] = {
    variants.map { variant =>
      // Extract coordinates from JSONB
      val coordinates = extractCoordinates(variant)

      // Extract aliases from JSONB
      val aliases = extractAliases(variant)

      VariantDTO(
        name = variant.displayName,
        coordinates = coordinates,
        variantType = variant.mutationType.dbValue,
        aliases = aliases
      )
    }
  }

  /**
   * Extract coordinates from VariantV2 JSONB into Map[String, GenomicCoordinate]
   */
  private def extractCoordinates(variant: VariantV2): Map[String, GenomicCoordinate] = {
    variant.coordinates.asOpt[Map[String, JsObject]].map { coordsMap =>
      coordsMap.flatMap { case (refGenome, coords) =>
        for {
          contig <- (coords \ "contig").asOpt[String]
          position <- (coords \ "position").asOpt[Int]
          ref <- (coords \ "ref").asOpt[String]
          alt <- (coords \ "alt").asOpt[String]
        } yield {
          val coordKey = s"$contig [${shortRefGenome(refGenome)}]"
          coordKey -> GenomicCoordinate(
            start = position,
            stop = position,
            anc = ref,
            der = alt
          )
        }
      }
    }.getOrElse(Map.empty)
  }

  /**
   * Extract aliases from VariantV2 JSONB into Map[String, Seq[String]]
   */
  private def extractAliases(variant: VariantV2): Map[String, Seq[String]] = {
    val aliases = variant.aliases
    val rsIds = (aliases \ "rs_ids").asOpt[Seq[String]].getOrElse(Seq.empty)
    val commonNames = (aliases \ "common_names").asOpt[Seq[String]].getOrElse(Seq.empty)

    Map(
      "rsId" -> rsIds,
      "commonName" -> commonNames
    ).filter(_._2.nonEmpty)
  }

  private def shortRefGenome(ref: String): String = ref match {
    case r if r.contains("GRCh37") || r.contains("hg19") => "b37"
    case r if r.contains("GRCh38") || r.contains("hg38") => "b38"
    case r if r.contains("T2T") || r.contains("CHM13") || r == "hs1" => "hs1"
    case other => other
  }

  /**
   * Builds a TreeDTO representation by constructing a haplogroup tree structure
   * for the haplogroup(s) defined by the given genetic variant.
   */
  def buildTreeFromVariant(variantId: String, haplogroupType: HaplogroupType, routeType: RouteType): Future[Option[TreeDTO]] = {
    for {
      // First find the haplogroup(s) defined by this variant
      haplogroups <- variantRepository.findHaplogroupsByDefiningVariant(variantId, haplogroupType)

      // If we found any haplogroups, build the tree from the most recent one
      treeOpt <- haplogroups.sortBy(_.validFrom).lastOption match {
        case Some(haplogroup) => buildTreeResponse(haplogroup.name, haplogroupType, routeType).map(Some(_))
        case None => Future.successful(None)
      }
    } yield treeOpt
  }

  /**
   * Constructs a sequence of TreeDTO objects representing tree structures for all haplogroups
   * associated with a specific genetic variant.
   */
  def buildTreesFromVariant(variantId: String, haplogroupType: HaplogroupType, routeType: RouteType): Future[Seq[TreeDTO]] = {
    for {
      // Find all haplogroups that have this variant as defining
      haplogroups <- variantRepository.findHaplogroupsByDefiningVariant(variantId, haplogroupType)

      // Build trees for each haplogroup
      trees <- Future.sequence(
        haplogroups.map(h => buildTreeResponse(h.name, haplogroupType, routeType))
      )
    } yield trees
  }

  // Helper method to search by different variant identifier formats
  def findVariantTrees(query: String, haplogroupType: HaplogroupType, routeType: RouteType): Future[Seq[TreeDTO]] = {
    // Normalize the query
    val normalizedQuery = normalizeVariantId(query)

    for {
      // Search by different formats (rsID, position-based, etc)
      variants <- variantRepository.findVariants(normalizedQuery)

      // Get all trees for each variant
      treeLists <- Future.sequence(
        variants.map(v => buildTreesFromVariant(v.variantId.get.toString, haplogroupType, routeType))
      )
    } yield treeLists.flatten
  }

  /**
   * Finds and retrieves haplogroup details with all associated genomic variants.
   */
  def findHaplogroupWithVariants(haplogroupName: String, haplogroupType: HaplogroupType): Future[(Option[Haplogroup], Seq[VariantDTO])] = {
    for {
      haplogroup <- coreRepository.getHaplogroupByName(haplogroupName, haplogroupType)
      variants <- findVariantsForHaplogroup(haplogroupName, haplogroupType)
    } yield (haplogroup, variants)
  }

  /**
   * Finds and retrieves all genomic variants associated with a specified haplogroup.
   * Now uses VariantV2 with embedded aliases in JSONB.
   */
  def findVariantsForHaplogroup(haplogroupName: String, haplogroupType: HaplogroupType): Future[Seq[VariantDTO]] = {
    for {
      haplogroup <- coreRepository.getHaplogroupByName(haplogroupName, haplogroupType)
      variants <- variantRepository.getHaplogroupVariants(haplogroup.flatMap(_.id).getOrElse(0))
    } yield {
      val variantDTOs = mapVariants(variants)
      TreeNodeDTO.sortVariants(variantDTOs)
    }
  }

  private def normalizeVariantId(query: String): String = {
    query.trim.toLowerCase match {
      case rsid if rsid.startsWith("rs") => rsid
      case chrPos if chrPos.contains(":") =>
        val parts = chrPos.split(":")
        parts.length match {
          case 2 => s"${parts(0)}:${parts(1)}" // chr:pos format
          case 4 => s"${parts(0)}:${parts(1)}:${parts(2)}:${parts(3)}" // chr:pos:ref:alt format
          case _ => query
        }
      case _ => query
    }
  }

  /**
   * Transforms a recursive tree structure of TreeNodeDTO into a flat sequence of SubcladeDTO.
   */
  def mapApiResponse(root: Option[TreeNodeDTO]): Seq[SubcladeDTO] = {
    def map(node: TreeNodeDTO, parent: Option[TreeNodeDTO]): Seq[SubcladeDTO] = {
      SubcladeDTO(node.name, parent.map(_.name), node.variants, node.updated, node.isBackbone) +: node.children.flatMap(c => map(c, Option(node)))
    }

    root.map(x => map(x, None))
      .getOrElse(Seq())
  }
}
