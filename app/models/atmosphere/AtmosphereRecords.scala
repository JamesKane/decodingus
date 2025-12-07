package models.atmosphere

import play.api.libs.json._
import java.time.Instant

// --- Common Definitions ---

case class RecordMeta(
  version: Int,
  createdAt: Instant,
  updatedAt: Option[Instant],
  lastModifiedField: Option[String]
)

object RecordMeta {
  implicit val format: OFormat[RecordMeta] = Json.format[RecordMeta]
}

case class FileInfo(
  fileName: String,
  fileSizeBytes: Option[Long],
  fileFormat: String,
  checksum: Option[String],
  checksumAlgorithm: Option[String],
  location: Option[String]
)

object FileInfo {
  implicit val format: OFormat[FileInfo] = Json.format[FileInfo]
}

case class VariantCall(
  contigAccession: String,
  position: Int,
  referenceAllele: String,
  alternateAllele: String,
  rsId: Option[String],
  variantName: Option[String],
  genotype: Option[String],
  quality: Option[Double],
  depth: Option[Int]
)

object VariantCall {
  implicit val format: OFormat[VariantCall] = Json.format[VariantCall]
}

case class PrivateVariantData(
  variants: Option[Seq[VariantCall]],
  analysisVersion: Option[String],
  referenceTree: Option[String]
)

object PrivateVariantData {
  implicit val format: OFormat[PrivateVariantData] = Json.format[PrivateVariantData]
}

case class HaplogroupResult(
  haplogroupName: String,
  score: Double,
  matchingSnps: Option[Int],
  mismatchingSnps: Option[Int],
  ancestralMatches: Option[Int],
  treeDepth: Option[Int],
  lineagePath: Option[Seq[String]],
  privateVariants: Option[PrivateVariantData]
)

object HaplogroupResult {
  implicit val format: OFormat[HaplogroupResult] = Json.format[HaplogroupResult]
}

case class HaplogroupAssignments(
  yDna: Option[HaplogroupResult],
  mtDna: Option[HaplogroupResult]
)

object HaplogroupAssignments {
  implicit val format: OFormat[HaplogroupAssignments] = Json.format[HaplogroupAssignments]
}

case class ContigMetrics(
  contigName: String,
  callableBases: Int,
  meanCoverage: Option[Double],
  poorMappingQuality: Option[Int],
  lowCoverage: Option[Int],
  noCoverage: Option[Int]
)

object ContigMetrics {
  implicit val format: OFormat[ContigMetrics] = Json.format[ContigMetrics]
}

case class AlignmentMetrics(
  genomeTerritory: Option[Long],
  meanCoverage: Option[Double],
  medianCoverage: Option[Double],
  sdCoverage: Option[Double],
  pctExcDupe: Option[Double],
  pctExcMapq: Option[Double],
  pct10x: Option[Double],
  pct20x: Option[Double],
  pct30x: Option[Double],
  hetSnpSensitivity: Option[Double],
  contigs: Option[Seq[ContigMetrics]]
)

object AlignmentMetrics {
  implicit val format: OFormat[AlignmentMetrics] = Json.format[AlignmentMetrics]
}

case class PopulationComponent(
  populationCode: String,
  populationName: Option[String],
  percentage: Double,
  confidenceInterval: Option[Map[String, Double]] // "lower", "upper"
)

object PopulationComponent {
  implicit val format: OFormat[PopulationComponent] = Json.format[PopulationComponent]
}

case class IbdSegment(
  chromosome: String,
  startPosition: Int,
  endPosition: Int,
  lengthCm: Double,
  snpCount: Option[Int],
  isHalfIdentical: Option[Boolean]
)

object IbdSegment {
  implicit val format: OFormat[IbdSegment] = Json.format[IbdSegment]
}

// STR structures are complex (Unions). Simplified for now or handling as JsValue if too complex.
// The lexicon defines strValue as a union of simple, multiCopy, complex.
// We can use a sealed trait.

sealed trait StrValue
case class SimpleStrValue(`type`: String = "simple", repeats: Int) extends StrValue
case class MultiCopyStrValue(`type`: String = "multiCopy", copies: Seq[Int]) extends StrValue

case class StrAllele(repeats: Double, count: Int, designation: Option[String])
object StrAllele { implicit val format: OFormat[StrAllele] = Json.format[StrAllele] }

case class ComplexStrValue(`type`: String = "complex", alleles: Seq[StrAllele], rawNotation: Option[String]) extends StrValue

object StrValue {
  implicit val simpleFormat: OFormat[SimpleStrValue] = Json.format[SimpleStrValue]
  implicit val multiCopyFormat: OFormat[MultiCopyStrValue] = Json.format[MultiCopyStrValue]
  implicit val complexFormat: OFormat[ComplexStrValue] = Json.format[ComplexStrValue]

  implicit val reads: Reads[StrValue] = (json: JsValue) => {
    (json \ "type").asOpt[String] match {
      case Some("simple") => simpleFormat.reads(json)
      case Some("multiCopy") => multiCopyFormat.reads(json)
      case Some("complex") => complexFormat.reads(json)
      case _ => JsError("Unknown or missing StrValue type")
    }
  }

  implicit val writes: Writes[StrValue] = {
    case s: SimpleStrValue => simpleFormat.writes(s)
    case m: MultiCopyStrValue => multiCopyFormat.writes(m)
    case c: ComplexStrValue => complexFormat.writes(c)
  }
}

case class StrMarkerValue(
  marker: String,
  value: StrValue,
  panel: Option[String],
  quality: Option[String],
  readDepth: Option[Int]
)

object StrMarkerValue {
  implicit val format: OFormat[StrMarkerValue] = Json.format[StrMarkerValue]
}

case class StrPanel(
  panelName: String,
  markerCount: Int,
  provider: Option[String],
  testDate: Option[Instant]
)

object StrPanel {
  implicit val format: OFormat[StrPanel] = Json.format[StrPanel]
}

case class AncestralStrState(
  marker: String,
  ancestralValue: StrValue,
  confidence: Option[Double],
  supportingSamples: Option[Int],
  variance: Option[Double],
  method: Option[String]
)

object AncestralStrState {
  implicit val format: OFormat[AncestralStrState] = Json.format[AncestralStrState]
}

case class StrBranchMutation(
  marker: String,
  fromValue: StrValue,
  toValue: StrValue,
  stepChange: Option[Int],
  confidence: Option[Double]
)

object StrBranchMutation {
  implicit val format: OFormat[StrBranchMutation] = Json.format[StrBranchMutation]
}

// --- Core Records ---

case class WorkspaceRecord(
  atUri: Option[String], // Not in schema but good to have for event handling
  meta: RecordMeta,
  sampleRefs: Seq[String],
  projectRefs: Seq[String]
)

object WorkspaceRecord {
  implicit val format: OFormat[WorkspaceRecord] = Json.format[WorkspaceRecord]
}

case class BiosampleRecord(
  atUri: String,
  meta: RecordMeta,
  sampleAccession: Option[String],
  donorIdentifier: String,
  citizenDid: String,
  description: Option[String],
  centerName: String,
  sex: Option[String],
  haplogroups: Option[HaplogroupAssignments],
  sequenceRunRefs: Option[Seq[String]],
  genotypeRefs: Option[Seq[String]],
  populationBreakdownRef: Option[String],
  strProfileRef: Option[String]
)

object BiosampleRecord {
  implicit val format: OFormat[BiosampleRecord] = Json.format[BiosampleRecord]
}

case class SequenceRunRecord(
  atUri: String,
  meta: RecordMeta,
  biosampleRef: String,
  platformName: String,
  instrumentModel: Option[String],
  instrumentId: Option[String],
  testType: String,
  libraryLayout: Option[String],
  totalReads: Option[Int],
  readLength: Option[Int],
  meanInsertSize: Option[Double],
  flowcellId: Option[String],
  runDate: Option[Instant],
  files: Option[Seq[FileInfo]],
  alignmentRefs: Option[Seq[String]]
)

object SequenceRunRecord {
  implicit val format: OFormat[SequenceRunRecord] = Json.format[SequenceRunRecord]
}

case class AlignmentRecord(
  atUri: String,
  meta: RecordMeta,
  sequenceRunRef: String,
  biosampleRef: Option[String],
  referenceBuild: String,
  aligner: String,
  variantCaller: Option[String],
  files: Option[Seq[FileInfo]],
  metrics: Option[AlignmentMetrics]
)

object AlignmentRecord {
  implicit val format: OFormat[AlignmentRecord] = Json.format[AlignmentRecord]
}

case class GenotypeRecord(
  atUri: String,
  meta: RecordMeta,
  biosampleRef: String,
  provider: String,
  chipType: String,
  chipVersion: Option[String],
  snpCount: Option[Int],
  callRate: Option[Double],
  testDate: Option[Instant],
  buildVersion: Option[String],
  files: Option[Seq[FileInfo]],
  imputationRef: Option[String]
)

object GenotypeRecord {
  implicit val format: OFormat[GenotypeRecord] = Json.format[GenotypeRecord]
}

case class ImputationRecord(
  atUri: String,
  meta: RecordMeta,
  genotypeRef: String,
  biosampleRef: Option[String],
  referencePanel: String,
  imputationTool: String,
  imputedVariantCount: Option[Int],
  averageInfoScore: Option[Double],
  files: Option[Seq[FileInfo]]
)

object ImputationRecord {
  implicit val format: OFormat[ImputationRecord] = Json.format[ImputationRecord]
}

case class ProjectRecord(
  atUri: String,
  meta: RecordMeta,
  projectName: String,
  description: Option[String],
  administrator: String,
  memberRefs: Seq[String]
)

object ProjectRecord {
  implicit val format: OFormat[ProjectRecord] = Json.format[ProjectRecord]
}

case class PopulationBreakdownRecord(
  atUri: String,
  meta: RecordMeta,
  biosampleRef: String,
  analysisMethod: String,
  referencePopulations: Option[String],
  kValue: Option[Int],
  components: Seq[PopulationComponent],
  analysisDate: Option[Instant],
  pipelineVersion: Option[String]
)

object PopulationBreakdownRecord {
  implicit val format: OFormat[PopulationBreakdownRecord] = Json.format[PopulationBreakdownRecord]
}

case class InstrumentObservationRecord(
  atUri: String,
  meta: RecordMeta,
  instrumentId: String,
  labName: String,
  biosampleRef: String,
  sequenceRunRef: Option[String],
  platform: Option[String],
  instrumentModel: Option[String],
  flowcellId: Option[String],
  runDate: Option[Instant],
  confidence: Option[String] // KNOWN, INFERRED, GUESSED
)

object InstrumentObservationRecord {
  implicit val format: OFormat[InstrumentObservationRecord] = Json.format[InstrumentObservationRecord]
}

case class MatchConsentRecord(
  atUri: String,
  meta: RecordMeta,
  biosampleRef: String,
  consentLevel: String, // FULL, ANONYMOUS, PROJECT_ONLY
  allowedMatchTypes: Option[Seq[String]],
  minimumSegmentCm: Option[Double],
  shareContactInfo: Option[Boolean],
  consentedAt: Option[Instant],
  expiresAt: Option[Instant]
)

object MatchConsentRecord {
  implicit val format: OFormat[MatchConsentRecord] = Json.format[MatchConsentRecord]
}

case class MatchEntry(
  matchedBiosampleRef: String,
  matchedCitizenDid: Option[String],
  relationshipEstimate: Option[String],
  totalSharedCm: Double,
  longestSegmentCm: Option[Double],
  segmentCount: Int,
  sharedSegments: Option[Seq[IbdSegment]],
  matchedAt: Option[Instant],
  xMatchSharedCm: Option[Double]
)

object MatchEntry {
  implicit val format: OFormat[MatchEntry] = Json.format[MatchEntry]
}

case class MatchListRecord(
  atUri: String,
  meta: RecordMeta,
  biosampleRef: String,
  matchCount: Int,
  lastCalculatedAt: Option[Instant],
  matches: Seq[MatchEntry],
  continuationToken: Option[String]
)

object MatchListRecord {
  implicit val format: OFormat[MatchListRecord] = Json.format[MatchListRecord]
}

case class MatchRequestRecord(
  atUri: String,
  meta: RecordMeta,
  fromBiosampleRef: String,
  toBiosampleRef: String,
  status: String, // PENDING, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN
  message: Option[String],
  sharedAncestorHint: Option[String],
  expiresAt: Option[Instant],
  respondedAt: Option[Instant]
)

object MatchRequestRecord {
  implicit val format: OFormat[MatchRequestRecord] = Json.format[MatchRequestRecord]
}

case class StrProfileRecord(
  atUri: String,
  meta: RecordMeta,
  biosampleRef: String,
  sequenceRunRef: Option[String],
  panels: Option[Seq[StrPanel]],
  markers: Seq[StrMarkerValue],
  totalMarkers: Option[Int],
  source: Option[String],
  importedFrom: Option[String],
  derivationMethod: Option[String],
  files: Option[Seq[FileInfo]]
)

object StrProfileRecord {
  implicit val format: OFormat[StrProfileRecord] = Json.format[StrProfileRecord]
}

case class HaplogroupAncestralStrRecord(
  atUri: String,
  meta: RecordMeta,
  haplogroup: String,
  haplogroupTreeRef: Option[String],
  parentHaplogroup: Option[String],
  ancestralMarkers: Seq[AncestralStrState],
  sampleCount: Option[Int],
  computedAt: Instant,
  method: Option[String],
  softwareVersion: Option[String],
  mutationRateModel: Option[String],
  tmrcaEstimate: Option[Map[String, Double]], // Simplified for now
  branchMutations: Option[Seq[StrBranchMutation]]
)

object HaplogroupAncestralStrRecord {
  implicit val format: OFormat[HaplogroupAncestralStrRecord] = Json.format[HaplogroupAncestralStrRecord]
}
