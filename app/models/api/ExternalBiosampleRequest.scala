package models.api

import models.domain.genomics.{BiologicalSex, BiosampleType, HaplogroupResult}
import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

/**
 * Represents a request for an external biosample, containing the metadata and associated information
 * related to the sample and its sequencing data.
 *
 * @param sampleAccession Native identifier provided by the client for the biosample.
 * @param sourceSystem    Origin system or data source associated with the biosample (e.g., "evolbio", "pgp").
 * @param description     A textual description of the biosample.
 * @param alias           Optional alias for the biosample, provided by the client.
 * @param centerName      Name of the institution or center handling the biosample.
 * @param sex             Optional biological sex information for the biosample.
 * @param latitude        Optional geographical latitude information related to the biosample.
 * @param longitude       Optional geographical longitude information related to the biosample.
 * @param citizenDid      Optional decentralized identifier (DID) for linking to a citizen/PDS user.
 * @param donorType       Optional type of the donor (e.g., Citizen, PGP, Standard).
 * @param publication     Optional publication information related to the biosample, represented by the `PublicationInfo` structure.
 * @param sequenceData    Information regarding the sequencing data associated with the biosample, represented by the `SequenceDataInfo` structure.
 */
case class ExternalBiosampleRequest(
                                     sampleAccession: String, // Client provides their native identifier
                                     sourceSystem: String, // e.g., "evolbio", "pgp", etc.
                                     description: String,
                                     alias: Option[String],
                                     centerName: String,
                                     sex: Option[BiologicalSex],
                                     latitude: Option[Double],
                                     longitude: Option[Double],
                                     citizenDid: Option[String],
                                     atUri: Option[String],
                                     donorIdentifier: Option[String],
                                     donorType: Option[BiosampleType],
                                     publication: Option[PublicationInfo],
                                     sequenceData: SequenceDataInfo,
                                     atCid: Option[String] = None
                                   )

object ExternalBiosampleRequest {
  implicit val externalBiosampleRequest: OFormat[ExternalBiosampleRequest] = Json.format
}

/**
 * Represents publication-related information, including details such as DOI, PubMed ID, 
 * and original haplogroup data.
 *
 * @constructor Creates an instance of `PublicationInfo` to encapsulate key publication 
 *              identifiers and data related to haplogroups.
 * @param doi                 An optional DOI (Digital Object Identifier) for the publication.
 * @param pubmedId            An optional PubMed ID associated with the publication.
 * @param originalHaplogroups Optionally represents original haplogroup information, 
 *                            encapsulated in a `HaplogroupInfo` instance.
 */
case class PublicationInfo(
                            doi: Option[String],
                            pubmedId: Option[String],
                            originalHaplogroups: Option[HaplogroupInfo]
                          )

object PublicationInfo {
  implicit val publicationInfo: OFormat[PublicationInfo] = Json.format
}

/**
 * Represents information about Y-DNA and mitochondrial DNA (mtDNA) haplogroups, 
 * along with optional notes for additional context.
 *
 * @param yHaplogroup  An optional string representing the Y-DNA haplogroup.
 *                     This is typically associated with paternal lineage.
 * @param mtHaplogroup An optional string representing the mitochondrial DNA (mtDNA) haplogroup.
 *                     This is typically associated with maternal lineage.
 * @param notes        An optional string for any additional notes or descriptive information 
 *                     about the haplogroup or its context.
 */
case class HaplogroupInfo(
                           yHaplogroup: Option[HaplogroupResult],
                           mtHaplogroup: Option[HaplogroupResult],
                           notes: Option[String]
                         )

object HaplogroupInfo {
  implicit val haplogroupInfo: OFormat[HaplogroupInfo] = Json.format
}

/**
 * Represents metadata and related information about a sequence dataset.
 *
 * This case class encapsulates information about sequencing data, including
 * details such as the number of reads, read length, coverage, sequencing platform,
 * test type, and associated files.
 *
 * @param reads        An optional number of reads in the sequencing data.
 * @param readLength   An optional read length, indicating the length of individual reads.
 * @param coverage     An optional coverage value representing the depth of sequencing.
 * @param platformName The name of the sequencing platform used to generate the data.
 * @param testType     The type of sequencing test performed.
 * @param files        A sequence of file metadata, represented by `FileInfo`, containing information
 *                     about the files associated with the sequencing data.
 */
case class SequenceDataInfo(
                             reads: Option[Int],
                             readLength: Option[Int],
                             coverage: Option[Double],
                             platformName: String,
                             testType: String,
                             files: Seq[FileInfo]
                           )


object SequenceDataInfo {
  implicit val sequenceDataInfo: OFormat[SequenceDataInfo] = Json.format
}

/**
 * Represents the information of a library, typically used in a laboratory or sequencing context.
 *
 * @param lab        The name of the laboratory or site where sequencing or processing occurred.
 * @param testType   The type of test or sequencing performed.
 * @param runDate    The timestamp for when the sequencing or test run took place.
 * @param instrument The identifier or name of the instrument used in the sequencing process.
 * @param reads      The total number of reads generated during the sequencing.
 * @param readLength The length of each read in base pairs.
 * @param pairedEnd  Indicates whether the sequencing was performed using paired-end reads.
 * @param insertSize Optional parameter specifying the insert size for paired-end reads, if applicable.
 */
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

/**
 * Represents location information including a file URL and an optional file index URL.
 *
 * This class is useful for storing and managing metadata related to file locations,
 * such as a primary file's URL and its associated index file's URL, if available. The
 * `fileIndexUrl` is optional to accommodate cases where an index file is not provided.
 *
 * @param fileUrl      The URL pointing to the primary file location.
 * @param fileIndexUrl An optional URL pointing to the index file associated with the primary file.
 */
case class LocationInfo(
                         fileUrl: String,
                         fileIndexUrl: Option[String]
                       )

object LocationInfo {
  implicit val libraryInfo: OFormat[LocationInfo] = Json.format
}

/**
 * Represents checksum information including the checksum value and the algorithm used.
 *
 * @param checksum  The checksum value as a string.
 * @param algorithm The algorithm used to generate the checksum.
 */
case class ChecksumInfo(
                         checksum: String,
                         algorithm: String
                       )

object ChecksumInfo {
  implicit val checksumInfo: OFormat[ChecksumInfo] = Json.format
}

/**
 * Represents metadata for a file, including its name, size, format, aligner used, target reference,
 * associated checksums, and its location.
 *
 * @param fileName        The name of the file.
 * @param fileSizeBytes   The size of the file in bytes.
 * @param fileFormat      The format of the file, indicating the file type or extension.
 * @param aligner         The aligner used for processing or generating the file.
 * @param targetReference The reference target associated with the file.
 * @param checksums       A sequence of checksum information objects associated with the file.
 * @param location        Information about the file's location, including its URL and optional index URL.
 */
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



