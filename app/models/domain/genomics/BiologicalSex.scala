package models.domain.genomics

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue}

/**
 * Represents the biological sex of an individual or specimen. 
 *
 * The available enumeration values are:
 * - `Male`: Represents male biological sex.
 * - `Female`: Represents female biological sex.
 * - `Unknown`: Represents an undefined or unstated biological sex.
 * - `Intersex`: Represents intersex biological sex.
 */
enum BiologicalSex {
  case Male, Female, Unknown, Intersex

  def toLowerCase: String = this match {
    case Male => "male"
    case Female => "female"
    case Unknown => "unknown"
    case Intersex => "intersex" 
  }
}

/**
 * Provides functionality for interpreting and converting string values into 
 * the corresponding `BiologicalSex` enumeration values. It also includes 
 * implicit JSON formatting support for working with `BiologicalSex` in JSON 
 * serialization and deserialization.
 *
 * This object supports mapping well-known string representations of 
 * biological sexes (e.g., "male", "female", "intersex") to their respective 
 * enumeration values, as well as defaulting to `Unknown` for unsupported or
 * undefined values.
 *
 * - `fromString`: Converts a string value into a `BiologicalSex` instance 
 * based on a case-insensitive match. Defaults to `Unknown` if no match is found.
 * - `format`: An implicit `Format` instance for handling JSON reads and writes
 * for `BiologicalSex` enumeration values. Assumes `String` values for both 
 * serialization and deserialization.
 */
object BiologicalSex {
  def fromString(s: String): BiologicalSex = Option(s).map(_.trim.toLowerCase) match {
    case Some("male") => Male
    case Some("female") => Female
    case Some("intersex") => Intersex
    case _ => Unknown
  }

  implicit val format: Format[BiologicalSex] = new Format[BiologicalSex] {
    def reads(json: JsValue): JsResult[BiologicalSex] = json match {
      case JsString(s) => JsSuccess(fromString(s))
      case _ => JsError("String value expected")
    }

    def writes(sex: BiologicalSex): JsValue = JsString(sex.toLowerCase)
  }
}