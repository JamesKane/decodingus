package models.dal.domain.genomics

import com.vividsolutions.jts.geom.Point
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.CitizenBiosample

import java.time.LocalDate
import java.util.UUID

/**
 * Represents the database table definition for storing citizen biosample records.
 *
 * This table is specifically designed to handle biosample data linked to individuals (citizens), 
 * including metadata such as unique identifiers, collection details, and location information. 
 * Each row corresponds to a specific biosample associated with a citizen.
 *
 * Columns:
 *  - `id`: Unique identifier for the citizen biosample (primary key, auto-increment).
 *  - `citizenBiosampleDid`: A unique decentralized identifier (DID) for the citizen's biosample.
 *  - `sourcePlatform`: An optional field indicating the platform or system where the biosample originated.
 *  - `collectionDate`: An optional field specifying the date on which the sample was collected.
 *  - `geocoord`: An optional geographic coordinate (latitude and longitude) representing the collection location of the sample.
 *  - `description`: An optional textual description of the sample, providing additional narrative or metadata.
 *  - `sampleGuid`: A globally unique identifier (UUID) for the sample, ensuring interoperability across systems and datasets.
 *
 * Primary key:
 *  - `id`: This serves as the unique identifier for each citizen biosample record and is auto-generated.
 *
 * Mapping:
 *  - The table is mapped to the `CitizenBiosample` case class, aligning database columns with case class attributes.
 *
 * Table name:
 *  - The table is named "citizen_biosample" in the database.
 */
class CitizenBiosamplesTable(tag: Tag) extends Table[CitizenBiosample](tag, "citizen_biosample") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def citizenBiosampleDid = column[String]("citizen_biosample_did", O.Unique)

  def sourcePlatform = column[Option[String]]("source_platform")

  def collectionDate = column[Option[LocalDate]]("collection_date")

  def geocoord = column[Option[Point]]("geocoord")

  def description = column[Option[String]]("description")

  def sampleGuid = column[UUID]("sample_guid")

  def * = (id.?, citizenBiosampleDid, sourcePlatform, collectionDate, geocoord, description, sampleGuid).mapTo[CitizenBiosample]
}
