package models

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID

case class Biosample(
                      id: Option[Long],
                      sampleAccession: String, // Must be unique
                      description: String,
                      alias: Option[String],
                      centerName: String,
                      sex: Option[String],
                      coord: Option[Coord],
                      specimanDonorId: Option[Long],
                      sampleGuid: UUID, // U
                    )

case class SpecimanDonor(id: Option[Long], donorIdentifier: String, originBiobank: String)

case class CitizenBiosample(
                             citizenBiosampleDid: String,
                             sourcePlatform: Option[String],
                             collectionDate: Option[LocalDate],
                             coord: Option[Coord],
                             description: Option[String],
                             sampleGuid: UUID // Added GUID
                           )

case class PgpBiosample(
                         pgpBiosampleId: Long,
                         pgpParticipantId: String,
                         // ... other PGP metadata
                         sampleGuid: UUID
                       )

case class Coord(lat: Double, lon: Double)

case class RunAccession(
                         id: Option[Long],
                         runAccession: String, // Must be unique
                         sampleAccessionId: Long, // Foreign key
                         libraryName: String,
                         libraryLayout: String,
                         libraryStrategy: String,
                         librarySource: String,
                         librarySelection: String,
                         libraryConstructionMethod: String,
                         libraryConstructionProtocol: String,
                       )

case class Haplogroup(
                       haplogroupId: Long,
                       name: String,
                       lineage: Option[String],
                       description: Option[String],
                       haplogroupType: String,
                       revisionId: Int,
                       source: String,
                       confidenceLevel: String,
                       validFrom: LocalDateTime,
                       validUntil: Option[LocalDateTime]
                     )

case class HaplogroupRelationship(
                                   haplogroupRelationshipId: Long,
                                   childHaplogroupId: Long,
                                   parentHaplogroupId: Long,
                                   revision_id: Int,
                                   validFrom: LocalDateTime,
                                   validUntil: Option[LocalDateTime],
                                   source: String,
                                 )

case class GenbankContig(
                          genbankContigId: Long,
                          accession: String,
                          commonName: Option[String],
                          referenceGenome: Option[String]
                          // other metadata
                        )

case class Variant(
                    variantId: Long,
                    genbankContigId: Long,
                    position: Int,
                    referenceAllele: String,
                    alternateAllele: String,
                    variantType: String,
                    rsid: Option[String],
                    commonName: Option[String]
                  )

case class HaplogroupDefiningVariant(
                                      haplogroupDefiningVariantId: Long,
                                      haplogroupId: Long,
                                      variantId: Long
                                    )

case class BiosampleHaplogroup(
                                biosampleId: Long,
                                yHaplogroupId: Option[Long],
                                mtHaplogroupId: Option[Long]
                              )

case class AnalysisMethod(
                           analysisMethodId: Long,
                           methodName: String
                         )

case class Population(
                       populationId: Long,
                       populationName: String
                       // parentPopulationId: Option[Long]
                     )

case class AncestryAnalysis(
                             ancestryAnalysisId: Long,
                             sampleGuid: UUID,
                             analysisMethodId: Long,
                             populationId: Long,
                             probability: Double
                           )

case class SequenceFile(
                         id: Long,
                         fileName: String,
                         fileSizeBytes: Long,
                         fileMd5: String,
                         fileFormat: String,
                         aligner: String,
                         targetReference: String,
                         reads: Long,
                         readLength: Int,
                         pairedEnd: Boolean,
                         insertSize: Option[Int],
                         created_at: LocalDateTime,
                         updated_at: Option[LocalDateTime],
                       )

case class SequenceHttpLocation(
                                 id: Long,
                                 sequenceFileId: Long,
                                 fileUrl: String,
                                 fileIndexUrl: Option[String],
                               )

case class SequenceAtpLocation(
                                id: Long,
                                sequenceFileId: Long,
                                repoDID: String,
                                recordCID: String,
                                recordPath: String,
                                indexDID: Option[String],
                                indexCID: Option[String],
                              )

case class BiosampleSequenceFile(
                                  id: Long,
                                  biosampleId: Long,
                                  sequenceFileId: Long,
                                )

case class CitizenBiosampleFile(
                                 citizenBiosampleFileId: Long,
                                 citizenBiosampleDid: String,
                                 sequenceFileId: Long,
                               )

case class QualityMetrics(
                           id: Long,
                           contig: String,
                           startPos: Long,
                           endPos: Long,
                           numReads: Long,
                           refN: Long,
                           noCov: Long,
                           lowCov: Long,
                           excessiveCov: Long,
                           poorMq: Long,
                           callable: Long,
                           covPercent: Double,
                           meanDepth: Double,
                           meanMq: Double,
                           sequenceFileId: Long,
                         )