package models.domain.genomics

import play.api.libs.json.{__, JsValue, Json, OFormat, Format, Reads, Writes}

import java.time.Instant

/**
 * Consolidated variant with JSONB coordinates and aliases.
 * One row per logical variant across all reference genomes.
 *
 * @param variantId            Unique identifier (auto-generated)
 * @param canonicalName        Primary name (e.g., "M269", "DYS456"); None for unnamed variants
 * @param mutationType         Variant type (SNP, INDEL, MNP, STR, DEL, DUP, INS, INV, CNV, TRANS)
 * @param namingStatus         Naming status (Unnamed, PendingReview, Named)
 * @param aliases              JSONB: {common_names: [], rs_ids: [], sources: {source: [names]}}
 * @param coordinates          JSONB: Per-assembly coordinates (structure varies by mutationType)
 * @param definingHaplogroupId FK to haplogroup for parallel mutation disambiguation
 * @param evidence             JSONB: Evidence metadata (e.g., YSEQ test counts)
 * @param primers              JSONB: PCR primer information
 * @param notes                Free-text notes
 * @param createdAt            Creation timestamp
 * @param updatedAt            Last update timestamp
 */
case class VariantV2(
  variantId: Option[Int] = None,
  canonicalName: Option[String],
  mutationType: MutationType,
  namingStatus: NamingStatus = NamingStatus.Unnamed,
  aliases: JsValue = Json.obj(),
  coordinates: JsValue = Json.obj(),
  definingHaplogroupId: Option[Int] = None,
  evidence: JsValue = Json.obj(),
  primers: JsValue = Json.obj(),
  notes: Option[String] = None,
  annotations: JsValue = Json.obj(),
  createdAt: Instant = Instant.now(),
  updatedAt: Instant = Instant.now()
) {

  /**
   * Get coordinate entry for a specific reference genome.
   */
  def getCoordinates(refGenome: String): Option[JsValue] =
    (coordinates \ refGenome).toOption

  /**
   * Check if variant has coordinates for a given reference.
   */
  def hasCoordinates(refGenome: String): Boolean =
    (coordinates \ refGenome).isDefined

  /**
   * Get all reference genomes that have coordinates.
   */
  def availableReferences: Set[String] =
    coordinates.asOpt[Map[String, JsValue]].map(_.keySet).getOrElse(Set.empty)

  /**
   * Get common names from aliases.
   */
  def commonNames: Seq[String] =
    (aliases \ "common_names").asOpt[Seq[String]].getOrElse(Seq.empty)

  /**
   * Get rs IDs from aliases.
   */
  def rsIds: Seq[String] =
    (aliases \ "rs_ids").asOpt[Seq[String]].getOrElse(Seq.empty)

  /**
   * Check if this is an STR marker.
   */
  def isStr: Boolean = mutationType == MutationType.STR

  /**
   * Check if this is a structural variant.
   */
  def isStructuralVariant: Boolean = mutationType.isStructural

  /**
   * Display name for UI (canonical name or coordinate-based fallback).
   */
  def displayName: String = canonicalName.getOrElse {
    // For unnamed variants, show coordinate-based identifier
    getCoordinates("hs1").orElse(getCoordinates("GRCh38")).map { coords =>
      val contig = (coords \ "contig").asOpt[String].getOrElse("?")
      val position = (coords \ "position").asOpt[Int].orElse((coords \ "start").asOpt[Int]).getOrElse(0)
      val ref = (coords \ "ref").asOpt[String].getOrElse("")
      val alt = (coords \ "alt").asOpt[String].getOrElse("")
      if (ref.nonEmpty && alt.nonEmpty) s"$contig:$position:$ref>$alt"
      else s"$contig:$position"
    }.getOrElse(s"variant_${variantId.getOrElse(0)}")
  }
}

/**
 * Helper case class for SNP/INDEL/MNP coordinates.
 */
case class PointVariantCoordinates(
  contig: String,
  position: Int,
  ref: String,
  alt: String
)

object PointVariantCoordinates {
  implicit val format: OFormat[PointVariantCoordinates] = Json.format[PointVariantCoordinates]
}

/**
 * Helper case class for STR coordinates.
 */
case class StrCoordinates(
  contig: String,
  start: Long,
  end: Long,
  period: Int,
  repeatMotif: Option[String] = None,
  referenceRepeats: Option[Int] = None
)

object StrCoordinates {
  implicit val format: OFormat[StrCoordinates] = Json.format[StrCoordinates]
}

/**
 * Helper case class for structural variant coordinates.
 */
case class SvCoordinates(
  contig: String,
  start: Long,
  end: Long,
  length: Long,
  innerStart: Option[Long] = None,  // For inversions
  innerEnd: Option[Long] = None,    // For inversions
  referenceCopies: Option[Int] = None,  // For CNVs
  copyNumberRange: Option[Seq[Int]] = None  // For CNVs
)

object SvCoordinates {
  implicit val format: OFormat[SvCoordinates] = Json.format[SvCoordinates]
}

/**
 * Helper case class for aliases structure.
 */
case class VariantAliases(
  commonNames: Seq[String] = Seq.empty,
  rsIds: Seq[String] = Seq.empty,
  sources: Map[String, Seq[String]] = Map.empty
)

object VariantAliases {
  implicit val format: OFormat[VariantAliases] = Json.format[VariantAliases]

  val empty: VariantAliases = VariantAliases()

  /**
   * Create from a single source with names.
   */
  def fromSource(source: String, names: Seq[String], rsIds: Seq[String] = Seq.empty): VariantAliases =
    VariantAliases(
      commonNames = names,
      rsIds = rsIds,
      sources = Map(source -> names)
    )
}

object VariantV2 {
  // Custom format that handles enum serialization via dbValue strings
  implicit val format: Format[VariantV2] = {
    import play.api.libs.functional.syntax.*

    val reads: Reads[VariantV2] = (
      (__ \ "variantId").readNullable[Int] and
      (__ \ "canonicalName").readNullable[String] and
      (__ \ "mutationType").read[String].map(MutationType.fromStringOrDefault(_)) and
      (__ \ "namingStatus").read[String].map(NamingStatus.fromStringOrDefault(_)) and
      (__ \ "aliases").read[JsValue] and
      (__ \ "coordinates").read[JsValue] and
      (__ \ "definingHaplogroupId").readNullable[Int] and
      (__ \ "evidence").read[JsValue] and
      (__ \ "primers").read[JsValue] and
      (__ \ "notes").readNullable[String] and
      (__ \ "annotations").read[JsValue] and
      (__ \ "createdAt").read[Instant] and
      (__ \ "updatedAt").read[Instant]
    )(VariantV2.apply)

    val writes: Writes[VariantV2] = (
      (__ \ "variantId").writeNullable[Int] and
      (__ \ "canonicalName").writeNullable[String] and
      (__ \ "mutationType").write[String].contramap[MutationType](_.dbValue) and
      (__ \ "namingStatus").write[String].contramap[NamingStatus](_.dbValue) and
      (__ \ "aliases").write[JsValue] and
      (__ \ "coordinates").write[JsValue] and
      (__ \ "definingHaplogroupId").writeNullable[Int] and
      (__ \ "evidence").write[JsValue] and
      (__ \ "primers").write[JsValue] and
      (__ \ "notes").writeNullable[String] and
      (__ \ "annotations").write[JsValue] and
      (__ \ "createdAt").write[Instant] and
      (__ \ "updatedAt").write[Instant]
    )(v => (v.variantId, v.canonicalName, v.mutationType, v.namingStatus, v.aliases,
            v.coordinates, v.definingHaplogroupId, v.evidence, v.primers, v.notes,
            v.annotations, v.createdAt, v.updatedAt))

    Format(reads, writes)
  }

  /**
   * Create a named SNP variant with coordinates for a single reference.
   */
  def snp(
    name: String,
    refGenome: String,
    contig: String,
    position: Int,
    ref: String,
    alt: String,
    source: Option[String] = None
  ): VariantV2 = {
    val coords = Json.obj(
      refGenome -> Json.toJson(PointVariantCoordinates(contig, position, ref, alt))
    )
    val aliases = source.map { s =>
      Json.toJson(VariantAliases.fromSource(s, Seq(name)))
    }.getOrElse(Json.toJson(VariantAliases(commonNames = Seq(name))))

    VariantV2(
      canonicalName = Some(name),
      mutationType = MutationType.SNP,
      namingStatus = NamingStatus.Named,
      aliases = aliases,
      coordinates = coords
    )
  }

  /**
   * Create an unnamed variant from coordinates.
   */
  def unnamed(
    refGenome: String,
    contig: String,
    position: Int,
    ref: String,
    alt: String,
    variantType: MutationType = MutationType.SNP
  ): VariantV2 = {
    val coords = Json.obj(
      refGenome -> Json.toJson(PointVariantCoordinates(contig, position, ref, alt))
    )
    VariantV2(
      canonicalName = None,
      mutationType = variantType,
      namingStatus = NamingStatus.Unnamed,
      coordinates = coords
    )
  }
}
