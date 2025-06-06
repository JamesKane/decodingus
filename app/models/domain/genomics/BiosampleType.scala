package models.domain.genomics

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue}


enum BiosampleType {
  case Standard, PGP, Citizen, Ancient
}

object BiosampleType {
  implicit val format: Format[BiosampleType] = new Format[BiosampleType] {
    def reads(json: JsValue): JsResult[BiosampleType] = json match {
      case JsString(s) => BiosampleType.valueOf(s) match {
        case bt: BiosampleType => JsSuccess(bt)
        case _ => JsError(s"Unknown BiosampleType: $s")
      }
      case _ => JsError("String value expected")
    }

    def writes(bt: BiosampleType): JsValue = JsString(bt.toString)
  }
}
