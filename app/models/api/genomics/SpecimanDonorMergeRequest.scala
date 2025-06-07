package models.api.genomics

import play.api.libs.json._

enum MergeStrategy derives CanEqual {
  case PreferTarget, PreferSource, MostComplete
}

object MergeStrategy {
  implicit val format: Format[MergeStrategy] = new Format[MergeStrategy] {
    def reads(json: JsValue): JsResult[MergeStrategy] = json.validate[String].map {
      case "PreferTarget" => PreferTarget
      case "PreferSource" => PreferSource
      case "MostComplete" => MostComplete
      case other => throw new IllegalArgumentException(s"Unknown merge strategy: $other")
    }

    def writes(strategy: MergeStrategy): JsValue = JsString(strategy.toString)
  }
}

case class SpecimenDonorMergeRequest(
                                      targetId: Int,
                                      sourceIds: List[Int],
                                      mergeStrategy: MergeStrategy
                                    )

object SpecimenDonorMergeRequest {
  implicit val format: OFormat[SpecimenDonorMergeRequest] = Json.format[SpecimenDonorMergeRequest]
}

case class SpecimenDonorMergeResult(
                                     mergedDonorId: Int,
                                     updatedBiosamples: Int,
                                     removedDonors: List[Int],
                                     conflicts: List[MergeConflict]
                                   )

object SpecimenDonorMergeResult {
  implicit val format: OFormat[SpecimenDonorMergeResult] = Json.format[SpecimenDonorMergeResult]
}

case class MergeConflict(
                          field: String,
                          targetValue: String,
                          sourceValue: String,
                          resolution: String
                        )

object MergeConflict {
  implicit val format: OFormat[MergeConflict] = Json.format[MergeConflict]
}