package models.domain.genomics

/**
 * Represents a genetic population group or demographic population with a unique name and identifier.
 *
 * @param id             An optional unique identifier for the population, used for internal purposes.
 * @param populationName The name of the population, which serves as a primary identifier.
 */
case class Population(
                       id: Option[Int],
                       populationName: String
                       // parentPopulationId: Option[Long]
                     )
