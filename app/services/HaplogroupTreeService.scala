package services

import jakarta.inject.Inject
import models.api.*
import models.HaplogroupType
import models.HaplogroupType.{MT, Y}
import play.api.mvc.Call
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository}

import java.time.ZoneId
import scala.concurrent.{ExecutionContext, Future}

sealed trait RouteType
case object ApiRoute extends RouteType
case object FragmentRoute extends RouteType

class HaplogroupTreeService @Inject()(coreRepository: HaplogroupCoreRepository, variantRepository: HaplogroupVariantRepository)(implicit ec: ExecutionContext) {

  def buildTreeResponse(haplogroupName: String, haplogroupType: HaplogroupType, routeType: RouteType): Future[TreeDTO] = {
    for {
      rootHaplogroupOpt <- coreRepository.getHaplogroupByName(haplogroupName, haplogroupType)
      rootHaplogroup = rootHaplogroupOpt.getOrElse(throw new IllegalArgumentException(s"Haplogroup $haplogroupName not found"))

      ancestors <- coreRepository.getAncestors(rootHaplogroup.id.get)
      crumbs = buildCrumbs(ancestors :+ rootHaplogroup, haplogroupType, routeType)

      subtree <- buildSubtree(rootHaplogroup)

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

  private def buildCrumbs(haplogroups: Seq[models.Haplogroup], haplogroupType: HaplogroupType, routeType: RouteType): List[CrumbDTO] = {
    haplogroups.map { haplogroup =>
      CrumbDTO(
        label = haplogroup.name,
        url = getRoute(haplogroup.name, haplogroupType, routeType).url
      )
    }.toList
  }


  private def buildSubtree(haplogroup: models.Haplogroup): Future[TreeNodeDTO] = {
    for {
      // Get variants for this haplogroup
      variants <- variantRepository.getHaplogroupVariants(haplogroup.id.get)
      variantDTOs = variants.map { case (variant, contig) =>
        VariantDTO(
          name = variant.rsId.getOrElse(s"${contig.accession}:${variant.position}"),
          coordinates = Map(
            contig.accession -> GenomicCoordinate(
              variant.position,
              variant.position,
              variant.referenceAllele,
              variant.alternateAllele
            )
          ),
          variantType = variant.variantType
        )
      }

      // Get and process children
      children <- coreRepository.getDirectChildren(haplogroup.id.get)
      childNodes <- Future.sequence(children.map(buildSubtree))

    } yield TreeNodeDTO(
      name = haplogroup.name,
      variants = variantDTOs,
      children = childNodes.toList,
      updated = haplogroup.validFrom.atZone(ZoneId.systemDefault()),
      isBackbone = haplogroup.source == "backbone" // Assuming we have this field or similar logic
    )
  }

  def buildTreeFromVariant(variantId: String, haplogroupType: HaplogroupType, routeType: RouteType): Future[Option[TreeDTO]] = {
    for {
      // First find the haplogroup(s) defined by this variant
      haplogroups <- variantRepository.findHaplogroupsByDefiningVariant(variantId, haplogroupType)

      // If we found any haplogroups, build the tree from the most recent one
      // (assuming more recent haplogroups are more specific/detailed)
      treeOpt <- haplogroups.sortBy(_.validFrom).lastOption match {
        case Some(haplogroup) => buildTreeResponse(haplogroup.name, haplogroupType, routeType).map(Some(_))
        case None => Future.successful(None)
      }
    } yield treeOpt
  }

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

  private def normalizeVariantId(query: String): String = {
    // Handle different formats:
    // - rsID (rs1234)
    // - chr:pos (Y:2728456)
    // - chr:pos:ref:alt (Y:2728456:A:G)
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
}