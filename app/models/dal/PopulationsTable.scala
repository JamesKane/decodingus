package models.dal

import models.Population
import slick.jdbc.PostgresProfile.api.*

class PopulationsTable(tag: Tag) extends Table[Population](tag, "population") {
  def populationId = column[Int]("population_id", O.PrimaryKey, O.AutoInc)
  def populationName = column[String]("population_name", O.Unique)

  def * = (populationId.?, populationName).mapTo[Population]
}
