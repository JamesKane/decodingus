package models.api

import models.domain.genomics.BiologicalSex
import play.api.libs.json.{Json, Reads}

/**
 * Represents an update to a biobanking biosample, allowing specific properties to be modified.
 *
 * @param sex            An optional sex of the biosample source (e.g., "male", "female").
 * @param geoCoord       An optional geographical coordinate representing the location associated with the biosample.
 * @param alias          An optional alias or alternative identifier for the biosample.
 * @param locked         An optional flag indicating whether the biosample metadata is locked for updates.
 * @param dateRangeStart An optional start date for the date range associated with the biosample. Typically represented as a year (e.g., 1980).
 * @param dateRangeEnd   An optional end date for the date range associated with the biosample. Typically represented as a year (e.g., 1990).
 * @param yHaplogroup    An optional Y-chromosomal haplogroup assignment for the source of the biosample.
 * @param mtHaplogroup   An optional mitochondrial haplogroup assignment for the source of the biosample.
 */
case class BiosampleUpdate(
                            sex: Option[BiologicalSex] = None,
                            geoCoord: Option[GeoCoord] = None,
                            alias: Option[String] = None,
                            locked: Option[Boolean] = None,
                            dateRangeStart: Option[Int] = None,
                            dateRangeEnd: Option[Int] = None,
                            yHaplogroup: Option[String] = None,
                            mtHaplogroup: Option[String] = None
                          ) {
  def hasUpdates: Boolean = {
    sex.isDefined || geoCoord.isDefined || alias.isDefined || locked.isDefined ||
      dateRangeStart.isDefined || dateRangeEnd.isDefined || yHaplogroup.isDefined ||
      mtHaplogroup.isDefined
  }
}

object BiosampleUpdate {
  implicit val reads: Reads[BiosampleUpdate] = Json.reads[BiosampleUpdate]
}