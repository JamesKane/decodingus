package models

import com.vividsolutions.jts.geom.Point

import java.time.LocalDate
import java.util.UUID

/**
 * Represents a biological sample specifically linked to a citizen participant, with metadata and identifiers.
 *
 * @param id                  An optional unique identifier for the citizen biosample.
 * @param citizenBiosampleDid A unique identifier for the citizen biosample in decentralized systems.
 * @param sourcePlatform      An optional description of the platform or source responsible for collecting the biosample data.
 * @param collectionDate      An optional date indicating when the sample was collected.
 * @param coord               An optional geographic coordinate (latitude and longitude) representing the collection location of the sample.
 * @param description         An optional textual description providing additional narrative about the biosample.
 * @param sampleGuid          A universally unique identifier (UUID) that identifies the sample across systems and datasets.
 */
case class CitizenBiosample(
                             id: Option[Int], 
                             citizenBiosampleDid: String,
                             sourcePlatform: Option[String],
                             collectionDate: Option[LocalDate],
                             coord: Option[Point],
                             description: Option[String],
                             sampleGuid: UUID
                           )
