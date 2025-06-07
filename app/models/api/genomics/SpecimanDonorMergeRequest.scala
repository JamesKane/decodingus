package models.api.genomics

import play.api.libs.json._

/**
 * Represents the strategy for resolving conflicts or handling decisions during a merge operation
 * involving multiple specimen donors.
 *
 * The `MergeStrategy` enum provides the following options:
 * - `PreferTarget`: Conflicts are resolved by preferring the data already present in the target donor.
 * - `PreferSource`: Conflicts are resolved by preferring the data from the source donors over the target donor.
 * - `MostComplete`: Conflicts are resolved by selecting the most complete data based on the aggregation of
 * values from all donors involved in the merge.
 */
enum MergeStrategy derives CanEqual {
  case PreferTarget, PreferSource, MostComplete
}

/**
 * Object providing JSON format support for the `MergeStrategy` enumeration.
 *
 * The implicit `Format` instance handles serialization and deserialization
 * of `MergeStrategy` values to and from JSON. The JSON representation of a
 * `MergeStrategy` is a string corresponding to the enumeration values:
 * - `PreferTarget`
 * - `PreferSource`
 * - `MostComplete`
 *
 * Reads operations interpret the JSON string and convert it into the appropriate
 * `MergeStrategy` value. If an unrecognized string is encountered, an
 * `IllegalArgumentException` is thrown.
 *
 * Writes operations serialize a `MergeStrategy` value into its corresponding
 * string representation for JSON output.
 */
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

/**
 * Represents a request to merge specimen donors in a data management system.
 *
 * This case class encapsulates the details necessary to perform a donor merge operation,
 * including the target donor ID, source donor IDs, and the strategy to handle conflicts or
 * determine how data should be aggregated during the merge process.
 *
 * @param targetId      the unique identifier of the target donor into which the source donors will be merged
 * @param sourceIds     a list of unique identifiers of the source donors to be merged into the target donor
 * @param mergeStrategy specifies the strategy to use when resolving conflicts or combining data during the merge
 */
case class SpecimenDonorMergeRequest(
                                      targetId: Int,
                                      sourceIds: List[Int],
                                      mergeStrategy: MergeStrategy
                                    )

/**
 * Companion object for the `SpecimenDonorMergeRequest` case class.
 *
 * This object provides the implicit JSON format needed for serializing and
 * deserializing instances of `SpecimenDonorMergeRequest`.
 */
object SpecimenDonorMergeRequest {
  implicit val format: OFormat[SpecimenDonorMergeRequest] = Json.format[SpecimenDonorMergeRequest]
}

/**
 * Represents the result of a donor merge operation in a specimen management system.
 *
 * This case class captures the details of the outcome when multiple donor records are merged
 * into a single record. It contains information about the resulting merged donor ID,
 * the number of biosamples updated, the IDs of removed donors, and a list of any conflicts
 * encountered during the merge operation.
 *
 * @param mergedDonorId     the unique identifier of the donor record that results from the merge operation
 * @param updatedBiosamples the number of biosamples that were selected or reassigned to the merged donor
 * @param removedDonors     a list of unique identifiers of source donors that were removed as part of the merge process
 * @param conflicts         a list of conflicts encountered during the merge process, with details about the conflicting fields and resolutions
 */
case class SpecimenDonorMergeResult(
                                     mergedDonorId: Int,
                                     updatedBiosamples: Int,
                                     removedDonors: List[Int],
                                     conflicts: List[MergeConflict]
                                   )

/**
 * Companion object for the `SpecimenDonorMergeResult` case class.
 *
 * Provides JSON serialization and deserialization support for `SpecimenDonorMergeResult`.
 * This is achieved using Play's JSON library to define the implicit `OFormat`.
 *
 * The `SpecimenDonorMergeResult` class represents the outcome of merging multiple donor
 * records into a unified donor record. It includes details such as the merged donor ID,
 * updated biosample count, removed donor IDs, and any merge conflicts encountered.
 */
object SpecimenDonorMergeResult {
  implicit val format: OFormat[SpecimenDonorMergeResult] = Json.format[SpecimenDonorMergeResult]
}

/**
 * Represents a conflict encountered during a merge operation between a target donor
 * and one or more source donors. A merge conflict occurs when the values of a specific
 * field differ between the target and source donors.
 *
 * @param field       the name of the field where the conflict was detected
 * @param targetValue the value of the field in the target donor
 * @param sourceValue the value of the field in the source donor
 * @param resolution  the resolved value for the conflicting field, derived from the merge strategy
 */
case class MergeConflict(
                          field: String,
                          targetValue: String,
                          sourceValue: String,
                          resolution: String
                        )

/**
 * Companion object for the `MergeConflict` case class. This object provides
 * implicit JSON serialization and deserialization functionality for `MergeConflict`
 * instances.
 *
 * The `MergeConflict` case class represents a conflict that occurs during the
 * process of merging donor data, with details about the conflicting field,
 * the values in the target and source donors, and the resolved value.
 *
 * The implicit `OFormat` provided by this object is used to convert `MergeConflict`
 * instances to and from JSON, facilitating their handling in APIs or persistent storage.
 */
object MergeConflict {
  implicit val format: OFormat[MergeConflict] = Json.format[MergeConflict]
}