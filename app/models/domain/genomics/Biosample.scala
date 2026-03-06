package models.domain.genomics

import com.vividsolutions.jts.geom.Point
import play.api.libs.json.{JsValue, Json, OFormat}

import java.util.UUID

case class Biosample(
                      id: Option[Int] = None,
                      sampleGuid: UUID,
                      sampleAccession: String,
                      description: String,
                      alias: Option[String],
                      centerName: String,
                      specimenDonorId: Option[Int],
                      locked: Boolean = false,
                      sourcePlatform: Option[String] = None,
                      originalHaplogroups: Option[JsValue] = None
                    ) {

  def getOriginalHaplogroupEntries: Seq[OriginalHaplogroupEntry] =
    originalHaplogroups.flatMap(_.asOpt[Seq[OriginalHaplogroupEntry]]).getOrElse(Seq.empty)

  def findHaplogroupByPublication(publicationId: Int): Option[OriginalHaplogroupEntry] =
    getOriginalHaplogroupEntries.find(_.publicationId == publicationId)

  def withHaplogroupEntry(entry: OriginalHaplogroupEntry): Biosample = {
    val existing = getOriginalHaplogroupEntries.filterNot(_.publicationId == entry.publicationId)
    copy(originalHaplogroups = Some(Json.toJson(existing :+ entry)))
  }

  def withoutHaplogroupForPublication(publicationId: Int): Biosample = {
    val remaining = getOriginalHaplogroupEntries.filterNot(_.publicationId == publicationId)
    copy(originalHaplogroups = Some(Json.toJson(remaining)))
  }
}

case class OriginalHaplogroupEntry(
                                    publicationId: Int,
                                    yHaplogroupResult: Option[HaplogroupResult] = None,
                                    mtHaplogroupResult: Option[HaplogroupResult] = None,
                                    notes: Option[String] = None
                                  )

object OriginalHaplogroupEntry {
  implicit val format: OFormat[OriginalHaplogroupEntry] = Json.format[OriginalHaplogroupEntry]
}
