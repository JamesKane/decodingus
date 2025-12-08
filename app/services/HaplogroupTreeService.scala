package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.HaplogroupType.{MT, Y}
import models.api.*
import models.dal.domain.genomics.Variant
import models.domain.genomics.GenbankContig
import models.domain.haplogroups.Haplogroup
import play.api.Logging
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
 *
 * @constructor Creates a new instance of `HaplogroupTreeService`.
 * @param coreRepository    repository for accessing core haplogroup data
 * @param variantRepository repository for accessing variant-related haplogroup data
 * @param ec                implicit execution context for handling asynchronous operations
 */
class HaplogroupTreeService @Inject()(
                                       coreRepository: HaplogroupCoreRepository,
                                       variantRepository: HaplogroupVariantRepository)(implicit ec: ExecutionContext)
  extends Logging {

  /**
   * Builds a TreeDTO representation for a specified haplogroup with related breadcrumbs and subtree.
   *
   * @param haplogroupName The name of the haplogroup to build the tree response for.
   * @param haplogroupType The type of haplogroup (e.g., Y-DNA or mtDNA) being processed.
   * @param routeType      The type of route to construct for breadcrumb navigation.
   * @return A Future containing the constructed TreeDTO, representing the haplogroup tree structure
   *         with breadcrumbs and an optional subtree.
   * @throws IllegalArgumentException if the specified haplogroup is not found.
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


  /**
   * Returns the route for a given combination of haplogroup type and route type.
   *
   * @param name           The name of the haplogroup.
   * @param haplogroupType The type of haplogroup, representing Y-DNA or mtDNA.
   * @param routeType      The type of route, representing fragment or API endpoints.
   * @return A `Call` object representing the constructed route for the specified parameters.
   */
  private def getRoute(name: String, haplogroupType: HaplogroupType, routeType: RouteType): Call = {
    (haplogroupType, routeType) match {
      case (Y, FragmentRoute) => controllers.routes.TreeController.yTreeFragment(Some(name))
      case (MT, FragmentRoute) => controllers.routes.TreeController.mTreeFragment(Some(name))
      case (Y, ApiRoute) => controllers.routes.TreeController.apiYTree(Some(name))
      case (MT, ApiRoute) => controllers.routes.TreeController.apiMTree(Some(name))
    }
  }

  /**
   * Constructs a list of breadcrumb DTOs based on the provided haplogroups, haplogroup type, and route type.
   *
   * @param haplogroups    A sequence of haplogroups used to generate breadcrumb data.
   * @param haplogroupType The type of haplogroups (e.g., Y-DNA or mtDNA) to use in the breadcrumb context.
   * @param routeType      The type of route (e.g., fragment or API endpoint) to create for breadcrumb navigation.
   * @return A list of `CrumbDTO` objects representing the breadcrumbs for the provided parameters.
   */
  private def buildCrumbs(haplogroups: Seq[Haplogroup], haplogroupType: HaplogroupType, routeType: RouteType): List[CrumbDTO] = {
    haplogroups.map { haplogroup =>
      CrumbDTO(
        label = haplogroup.name,
        url = getRoute(haplogroup.name, haplogroupType, routeType).url
      )
    }.toList
  }


  /**
   * Recursively builds a `TreeNodeDTO` representation of a haplogroup and its subtree.
   *
   * This method constructs a tree structure for a given haplogroup by retrieving its associated variants and 
   * processing its child haplogroups. The result is encapsulated in a `TreeNodeDTO` object, which contains
   * information about the haplogroup name, variants, children, last update timestamp, and whether it belongs 
   * to the backbone structure.
   *
   * @param haplogroup The `Haplogroup` object for which the subtree is being built. This contains metadata
   *                   such as the haplogroup's name, lineage, and additional information.
   * @return A `Future` containing the constructed `TreeNodeDTO`, which includes the haplogroup's metadata,
   *         associated variants, and recursive child tree nodes.
   */
  private def buildSubtree(haplogroup: Haplogroup): Future[TreeNodeDTO] = {
    for {
      // Get variants for this haplogroup
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
      isBackbone = haplogroup.source == "backbone" // Assuming we have this field or similar logic
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
      variantCount = Some(variantCount), // Add this field to TreeNodeDTO
      children = childNodes.toList,
      updated = haplogroup.validFrom.atZone(ZoneId.systemDefault()),
      isBackbone = haplogroup.source == "backbone"
    )
  }

  private def mapVariants(variants: Seq[(Variant, GenbankContig)]) = {
    variants.map { case (variant, contig) =>
      VariantDTO(
        name = variant.commonName.getOrElse(s"${contig.accession}:${variant.position}"),
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
  }

  /**
   * Builds a TreeDTO representation by constructing a haplogroup tree structure
   * for the haplogroup(s) defined by the given genetic variant.
   *
   * @param variantId      The identifier of the genetic variant defining one or more haplogroups.
   * @param haplogroupType The type of haplogroup (e.g., Y-DNA or mtDNA) to be processed.
   * @param routeType      The type of route to construct for breadcrumb navigation in the tree.
   * @return A Future containing an Option of TreeDTO. The Option will contain the TreeDTO if
   *         a corresponding haplogroup is found; otherwise, it will be None.
   */
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

  /**
   * Constructs a sequence of TreeDTO objects representing tree structures for all haplogroups
   * associated with a specific genetic variant.
   *
   * @param variantId      The identifier of the genetic variant used to find associated haplogroups.
   * @param haplogroupType The type of haplogroup (e.g., Y-DNA or mtDNA) being processed.
   * @param routeType      The type of route to construct for navigational purposes.
   * @return A Future containing a sequence of TreeDTO objects, where each represents the tree structure
   *         for a haplogroup associated with the provided variant.
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
   * Finds and retrieves all genomic variants associated with a specified haplogroup.
   *
   * This method fetches the variants linked to a haplogroup identified by its name and type.
   * It interacts with the core repository to locate the haplogroup and then queries the variant repository
   * to obtain the list of associated variants, which are finally converted into `VariantDTO` objects.
   *
   * @param haplogroupName The name of the haplogroup for which variants are to be retrieved.
   * @param haplogroupType The type of haplogroup (e.g., Y-DNA or mtDNA).
   * @return A Future containing a sequence of `VariantDTO` objects representing the variants
   *         associated with the specified haplogroup. If the haplogroup is not found, the sequence will be empty.
   */
  def findVariantsForHaplogroup(haplogroupName: String, haplogroupType: HaplogroupType): Future[Seq[VariantDTO]] = {
    val sortedVariantsFuture: Future[Seq[VariantDTO]] = for {
      haplogroup <- coreRepository.getHaplogroupByName(haplogroupName, haplogroupType)
      variants <- variantRepository.getHaplogroupVariants(haplogroup.flatMap(_.id).getOrElse(0))
    } yield TreeNodeDTO.sortVariants(mapVariants(variants))

    sortedVariantsFuture.map { sortedVariants =>
      val grouped = sortedVariants
        .groupBy(dto => dto.name)
        .map { case (k, locations) =>
          val first = locations.head

          // Combine the coordinates for all VariantDTOs in this group
          val coordinates: Seq[Map[String, GenomicCoordinate]] = locations.map(dto => dto.coordinates)
          val combined: Map[String, GenomicCoordinate] = coordinates.foldLeft(Map.empty[String, GenomicCoordinate]) {
            case (acc, currentMap) => acc ++ currentMap
          }

          // Create a new VariantDTO for the combined result
          VariantDTO(first.name, combined, first.variantType)
        }.toSeq

      TreeNodeDTO.sortVariants(grouped)
    }
  }

  /**
   * Normalizes the given variant identifier by formatting it consistently based on its structure.
   *
   * The method supports the following formats:
   * - rsID (e.g., rs1234): Returned as-is, converted to lowercase.
   * - chr:pos (e.g., Y:2728456): Returned in the same structure after conversion to lowercase.
   * - chr:pos:ref:alt (e.g., Y:2728456:A:G): Returned in the same structure after conversion to lowercase.
   *
   * Any unrecognized format is returned unchanged after trimming and converting to lowercase.
   *
   * @param query The genetic variant identifier to be normalized. It may be in rsID, chr:pos, or chr:pos:ref:alt format.
   * @return The normalized variant identifier, based on the recognized format.
   */
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
   * Transforms a recursive tree structure of `TreeNodeDTO` into a flat sequence of `SubcladeDTO`
   * suitable for API responses. This flattens the hierarchical data into a list where each subclade
   * explicitly references its parent.
   *
   * @param root An `Option` containing the root `TreeNodeDTO` of the tree to be transformed.
   * @return A `Seq` of `SubcladeDTO` representing the flattened tree structure.
   */
  def mapApiResponse(root: Option[TreeNodeDTO]): Seq[SubcladeDTO] = {
    def map(node: TreeNodeDTO, parent: Option[TreeNodeDTO]): Seq[SubcladeDTO] = {
      SubcladeDTO(node.name, parent.map(_.name), node.variants, node.updated, node.isBackbone) +: node.children.flatMap(c => map(c, Option(node)))
    }

    root.map(x => map(x, None))
      .getOrElse(Seq())
  }
}