package models.api

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

case class ExternalBiosampleRequest(
                                     sampleAccession: String,  // Client provides their native identifier
                                     sourceSystem: String,     // e.g., "evolbio", "pgp", etc.
                                     description: String,
                                     alias: Option[String],
                                     centerName: String,
                                     sex: Option[String],
                                     latitude: Option[Double],
                                     longitude: Option[Double],
                                     publication: Option[PublicationInfo],
                                     sequenceData: SequenceDataInfo
                                   )

object ExternalBiosampleRequest {
  implicit val externalBiosampleRequest: OFormat[ExternalBiosampleRequest] = Json.format
}

case class PublicationInfo(
                            doi: Option[String],
                            pubmedId: Option[String],
                            originalHaplogroups: Option[HaplogroupInfo]
                          )

object PublicationInfo {
  implicit val publicationInfo: OFormat[PublicationInfo] = Json.format
}

case class HaplogroupInfo(
                           yHaplogroup: Option[String],
                           mtHaplogroup: Option[String],
                           notes: Option[String]
                         )

object HaplogroupInfo {
  implicit val haplogroupInfo: OFormat[HaplogroupInfo] = Json.format
}

case class SequenceDataInfo(
                             reads: Option[Int],
                             readLength: Option[Int],
                             coverage: Option[Double],
                             platformName: String,
                             testType: String,
                             files: Seq[FileInfo]  // Changed from SequenceFileInfo to FileInfo to match existing model
                           )


object SequenceDataInfo {
  implicit val sequenceDataInfo: OFormat[SequenceDataInfo] = Json.format
}

case class LibraryInfo(
                        lab: String,
                        testType: String,
                        runDate: LocalDateTime,
                        instrument: String,
                        reads: Long,
                        readLength: Int,
                        pairedEnd: Boolean,
                        insertSize: Option[Int]
                      )

object LibraryInfo {
  implicit val libraryInfo: OFormat[LibraryInfo] = Json.format
}

case class LocationInfo(
                         fileUrl: String,
                         fileIndexUrl: Option[String]
                       )

object LocationInfo {
  implicit val libraryInfo: OFormat[LocationInfo] = Json.format
}

case class ChecksumInfo(
                         checksum: String,
                         algorithm: String
                       )

object ChecksumInfo {
  implicit val checksumInfo: OFormat[ChecksumInfo] = Json.format
}

case class FileInfo(
                     fileName: String,
                     fileSizeBytes: Long,
                     fileFormat: String,
                     aligner: String,
                     targetReference: String,
                     checksums: Seq[ChecksumInfo],
                     location: LocationInfo
                   )

object FileInfo {
  implicit val fileInfo: OFormat[FileInfo] = Json.format
}



