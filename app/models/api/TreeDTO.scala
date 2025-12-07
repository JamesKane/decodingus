package models.api

import play.api.libs.json.{Json, OFormat}

import java.time.ZonedDateTime

/**
 * Represents a tree structure with a name, breadcrumbs, and an optional subclade.
 *
 * @param name     The name of the tree.
 * @param crumbs   A list of breadcrumbs (navigational links) associated with the tree.
 * @param subclade An optional subclade represented as another tree node within the hierarchy.
 */
case class TreeDTO(name: String, crumbs: List[CrumbDTO], subclade: Option[TreeNodeDTO])

case class SubcladeDTO(name: String, parentName: Option[String], variants: Seq[VariantDTO], lastUpdated: ZonedDateTime, isBackbone: Boolean = false)

/**
 * Represents a breadcrumb with a label and a URL.
 *
 * This class is used to define navigational links in the form of breadcrumbs, providing a label
 * for display purposes and a corresponding URL for navigation.
 *
 * @param label The text displayed for the breadcrumb.
 * @param url   The URL associated with the breadcrumb, representing the navigation target.
 */
case class CrumbDTO(label: String, url: String)

/**
 * Represents a tree node data transfer object (DTO) used to model hierarchical structures with metadata and variants.
 *
 * @param name       The name of the tree node.
 * @param variants   A sequence of associated `VariantDTO` objects representing different variations related to this tree node.
 * @param children   A list of child `TreeNodeDTO` representing the hierarchical relationship of the tree structure.
 * @param updated    The timestamp at which the node or its content was last updated.
 * @param isBackbone A boolean flag indicating whether this node is part of the backbone structure of the tree. Defaults to `false`.
 */
case class TreeNodeDTO(name: String, variants: Seq[VariantDTO], children: List[TreeNodeDTO], updated: ZonedDateTime, isBackbone: Boolean = false, variantCount: Option[Int] = None) {
  /**
   * Calculates the weight of the current tree node.
   *
   * The weight is determined by adding 1 (representing the current node) to the sum
   * of the weights of all child nodes in the hierarchical structure.
   *
   * @return The total weight as an integer, which is the sum of the weight
   *         of the current node and the weights of all child nodes.
   */
  def weight: Int = 1 + children.map(_.weight).sum

  def sortedVariants: Seq[VariantDTO] = TreeNodeDTO.sortVariants(this.variants)
}

/**
 * Represents a genomic coordinate and mutation details for a specific region.
 *
 * @constructor Creates an instance of `GenomicCoordinate` with the specified start and stop positions,
 *              ancestral allele, and derived allele.
 * @param start The start position of the genomic region.
 * @param stop  The stop position of the genomic region.
 * @param anc   The ancestral allele at this genomic coordinate.
 * @param der   The derived allele at this genomic coordinate.
 *
 *              The class defines a genomic region along with the transition details from an ancestral allele (`anc`)
 *              to a derived allele (`der`) and allows for string representation of the coordinate in the format:
 *              `"<start> <anc>-><der>"`.
 */
case class GenomicCoordinate(start: Int, stop: Int, anc: String, der: String) {
  /**
   * Converts the object into its string representation.
   *
   * @return A string combining the `start`, `anc`, and `der` values in the format "start anc->der".
   */
  @Override
  override def toString: String = s"$start $anc->$der"
}

/**
 * Represents a genomic variant along with its name, coordinates, and variant type.
 *
 * @param name        The name of the variant.
 * @param coordinates A mapping of chromosomes or regions to their respective genomic coordinates.
 *                    Each `GenomicCoordinate` represents the specific start and stop positions
 *                    along with the ancestral and derived alleles for the region.
 * @param variantType The type of the variant, indicating the nature or classification of the mutation.
 */
case class VariantDTO(
                       name: String,
                       coordinates: Map[String, GenomicCoordinate],
                       variantType: String
                     )

/**
 * Companion object for the `GenomicCoordinate` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * `GenomicCoordinate` instances using the Play Framework's JSON library.
 *
 * The formatter enables seamless transformation of `GenomicCoordinate` objects
 * to their JSON representations and vice versa. This is particularly useful
 * for applications that involve API communication or storage in a JSON-based format.
 */
object GenomicCoordinate {
  implicit val featureCoordFormats: OFormat[GenomicCoordinate] = Json.format[GenomicCoordinate]
}

/**
 * Companion object for the `VariantDTO` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing `VariantDTO` instances
 * using the Play Framework's JSON library. This formatter allows seamless conversion between
 * `VariantDTO` objects and their JSON representation for APIs or other JSON-based integrations.
 */
object VariantDTO {
  implicit val treeFeatureFormats: OFormat[VariantDTO] = Json.format[VariantDTO]
}

/**
 * Companion object for the `TreeNodeDTO` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * `TreeNodeDTO` instances using the Play Framework's JSON library.
 *
 * This formatter enables seamless conversion of `TreeNodeDTO` objects
 * to and from their JSON representation, simplifying data interchange
 * in applications that utilize hierarchical tree structures.
 */
object TreeNodeDTO {
  implicit val treeNodeFormats: OFormat[TreeNodeDTO] = Json.format[TreeNodeDTO]

  private def extractComponents(s: String): (String, Option[Long]) = {
    val pattern = """([A-Za-z]+)?(\d+)""".r
    pattern.findFirstMatchIn(s) match
      case Some(m) =>
        val prefix = Option(m.group(1)).getOrElse("")
        val number = Some(m.group(2).toLong)
        (prefix, number)
      case None => (s, None)
  }

  def sortVariants(variants: Seq[VariantDTO]): Seq[VariantDTO] =
    variants.sortWith { (a, b) =>
      (a.name.contains(":"), b.name.contains(":")) match
        case (true, false) => false // a has chrY: prefix, b doesn't -> a comes after
        case (false, true) => true // b has chrY: prefix, a doesn't -> a comes before
        case _ => // both have or both don't have chrY: prefix
          val (prefixA, numA) = extractComponents(a.name)
          val (prefixB, numB) = extractComponents(b.name)
          if prefixA != prefixB then
            prefixA < prefixB
          else
            (numA, numB) match
              case (Some(n1), Some(n2)) => n1 < n2
              case _ => a.name < b.name
    }

}

/**
 * Companion object for the `CrumbDTO` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * `CrumbDTO` instances using the Play Framework's JSON library.
 *
 * This formatter enables seamless conversion of `CrumbDTO` objects to and from
 * their JSON representation, facilitating integration with APIs or systems that
 * utilize JSON data.
 */
object CrumbDTO {
  implicit val crumbFormats: OFormat[CrumbDTO] = Json.format[CrumbDTO]
}

/**
 * Companion object for the `TreeDTO` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * instances of `TreeDTO` using the Play Framework's JSON library.
 *
 * This formatter enables automatic conversion of `TreeDTO` objects
 * to and from their JSON representation, facilitating seamless integration
 * with APIs or systems that utilize JSON.
 */
object TreeDTO {
  implicit val treeFormats: OFormat[TreeDTO] = Json.format[TreeDTO]
}

object SubcladeDTO {
  implicit val subcladeFormats: OFormat[SubcladeDTO] = Json.format[SubcladeDTO]
}