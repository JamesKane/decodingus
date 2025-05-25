package models.domain

/**
 * Represents the association between a haplogroup and a genetic variant.
 *
 * @param id           An optional unique identifier for the HaplogroupVariant record.
 * @param haplogroupId The identifier of the haplogroup associated with this variant.
 * @param variantId    The identifier of the genetic variant associated with this haplogroup.
 */
case class HaplogroupVariant(id: Option[Int] = None, haplogroupId: Int, variantId: Int)
