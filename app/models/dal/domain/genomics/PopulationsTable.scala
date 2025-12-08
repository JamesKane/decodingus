package models.dal.domain.genomics

import models.domain.genomics.Population
import models.dal.MyPostgresProfile.api.*

/**
 * Represents the `PopulationsTable` database table definition.
 *
 * This class defines the schema for the `population` table, mapping database columns
 * to the corresponding attributes of the `Population` case class. The table is used
 * to store information about various population groups or demographics.
 *
 * @constructor Initializes a new instance of the `PopulationsTable` class.
 * @param tag A Slick `Tag` object used for referencing the table within a database schema.
 *
 *            Columns:
 *            - `populationId`: An optional unique identifier for the population (primary key, auto-incremented).
 *            - `populationName`: The unique name of the population, serving as a primary identifier (unique constraint).
 *
 *            Primary Key:
 *            - `populationId`: The primary key for the table, automatically incremented.
 *
 *            Mapping:
 *            - Defines a mapping to the `Population` case class via the `*` projection.
 */
class PopulationsTable(tag: Tag) extends Table[Population](tag, "population") {
  def populationId = column[Int]("population_id", O.PrimaryKey, O.AutoInc)

  def populationName = column[String]("population_name", O.Unique)

  def * = (populationId.?, populationName).mapTo[Population]
}
