package models.domain.genomics

import com.vividsolutions.jts.geom.Point
import java.time.{LocalDate, LocalDateTime}
import java.util.UUID

/**
 * Represents a biosample of type "Citizen", typically ingested from external sources/Firehose.
 * Maps to the `citizen_biosample` table.
 */
case class CitizenBiosample(
                             id: Option[Int] = None,
                             atUri: Option[String],
                             accession: Option[String],
                             alias: Option[String],
                             sourcePlatform: Option[String],
                             collectionDate: Option[LocalDate],
                             sex: Option[BiologicalSex],
                             geocoord: Option[Point],
                             description: Option[String],
                             yHaplogroup: Option[HaplogroupResult] = None,
                             mtHaplogroup: Option[HaplogroupResult] = None,
                             sampleGuid: UUID,
                             deleted: Boolean = false,
                             atCid: Option[String] = None,
                             createdAt: LocalDateTime = LocalDateTime.now(),
                             updatedAt: LocalDateTime = LocalDateTime.now()
                           )
