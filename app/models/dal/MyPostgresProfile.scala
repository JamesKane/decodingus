package models.dal

import com.github.tminglei.slickpg.*
import models.dal.domain.genomics.MinHashSketch.{bytesToLongArray, longArrayToBytes}
import models.domain.genomics.*
import models.domain.publications.StudySource
import slick.basic.Capability
import slick.jdbc.{JdbcCapabilities, JdbcType}

import java.time.LocalDateTime
import scala.language.higherKinds

trait MyPostgresProfile extends ExPostgresProfile
  with PgArraySupport
  with PgDate2Support
  with PgRangeSupport
  with PgHStoreSupport
  with PgSearchSupport
  with PgPostGISSupport
  with PgNetSupport
  with PgLTreeSupport
  with PgPlayJsonSupport // For JSON/JSONB support with Play JSON
  with PgEnumSupport // Added PgEnumSupport
  with array.PgArrayJdbcTypes {
  def pgjson = "jsonb" // jsonb support is in postgres 9.4.0 onward; for 9.3.x use "json"

  import slick.ast.*
  import slick.ast.Library.*
  import slick.lifted.FunctionSymbolExtensionMethods.*

  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + JdbcCapabilities.insertOrUpdate

  override val api: MyAPI.type = MyAPI // MyAPI is an inner object

  object MyAPI extends ExtPostgresAPI with ArrayImplicits
    with Date2DateTimeImplicitsDuration
    with NetImplicits
    with LTreeImplicits
    with RangeImplicits
    with HStoreImplicits
    with PostGISPlainImplicits
    with JsonImplicits // Bring in JSON implicits, including jsonType and jsonbListType
    with PostGISImplicits
    with PostGISAssistants
    with SearchImplicits
    with SearchAssistants {

    import models.HaplogroupType
    import models.domain.genomics.{DataGenerationMethod, HaplogroupResult, TargetType, TestType}
    import play.api.libs.json.*

    // Implicit JSON formatters for the new JSONB case classes
    implicit val sequenceFileChecksumJsonbFormat: OFormat[SequenceFileChecksumJsonb] = Json.format[SequenceFileChecksumJsonb]
    implicit val sequenceFileHttpLocationJsonbFormat: OFormat[SequenceFileHttpLocationJsonb] = Json.format[SequenceFileHttpLocationJsonb]
    implicit val sequenceFileAtpLocationJsonbFormat: OFormat[SequenceFileAtpLocationJsonb] = Json.format[SequenceFileAtpLocationJsonb]

    implicit val haplogroupResultJsonTypeMapper: JdbcType[HaplogroupResult] with BaseTypedType[HaplogroupResult] =
      MappedJdbcType.base[HaplogroupResult, JsValue](Json.toJson(_), _.as[HaplogroupResult])

    implicit val haplogroupTypeMapper: BaseColumnType[HaplogroupType] =
      MappedColumnType.base[HaplogroupType, String](
        ht => ht.toString,
        str => HaplogroupType.fromString(str).getOrElse(
          throw new IllegalArgumentException(s"Invalid haplogroup type: $str")
        )
      )

    implicit val studySourceTypeMapper: JdbcType[StudySource] =
      MappedColumnType.base[StudySource, String](
        st => st.toString,
        s => StudySource.valueOf(s)
      )

    implicit val biosampleTypeMapper: JdbcType[BiosampleType] =
      MappedColumnType.base[BiosampleType, String]( // Fixed BiologicalSex[String] to BiosampleType
        bt => bt.toString, // converts BiosampleType to String for storage
        s => BiosampleType.valueOf(s) // converts String back to BiosampleType
      )

    implicit val biologicalSexTypeMapper: JdbcType[BiologicalSex] =
      MappedColumnType.base[BiologicalSex, String]( // Fixed BiologicalSex[String] to BiologicalSex
        bs => bs.toString,
        s => BiologicalSex.valueOf(s)
      )

    implicit val metricLevelTypeMapper: JdbcType[MetricLevel] =
      MappedColumnType.base[MetricLevel, String](
        ml => ml.toString,
        s => MetricLevel.valueOf(s)
      )

    // Import for TestType
    import models.domain.genomics.TestType

    implicit val testTypeMapper: JdbcType[TestType] =
      MappedColumnType.base[TestType, String](
        tt => tt.toString,
        s => TestType.fromString(s).getOrElse(
          throw new IllegalArgumentException(s"Invalid TestType value: $s")
        )
      )

    implicit val dataGenerationMethodTypeMapper: JdbcType[DataGenerationMethod] =
      createEnumJdbcType("data_generation_method", _.toString, s => DataGenerationMethod.fromString(s).getOrElse(
        throw new IllegalArgumentException(s"Invalid DataGenerationMethod value: $s")
      ), quoteName = false)

    implicit val targetTypeTypeMapper: JdbcType[TargetType] =
      createEnumJdbcType("target_type", _.toString, s => TargetType.fromString(s).getOrElse(
        throw new IllegalArgumentException(s"Invalid TargetType value: $s")
      ), quoteName = false)

    // Custom Slick mapper for Array[Long] <-> bytea
    implicit val longArrayTypeMapper: BaseColumnType[Array[Long]] =
      MappedColumnType.base[Array[Long], Array[Byte]](
        longArrayToBytes,
        bytesToLongArray
      )

    // Array type mappers (from PgArraySupport)
    implicit val strListTypeMapper: DriverJdbcType[List[String]] = new SimpleArrayJdbcType[String]("text").to(_.toList)
    implicit val intListTypeMapper: DriverJdbcType[List[Int]] = new SimpleArrayJdbcType[Int]("int4").to(_.toList)

    // Play JSON array type mapper
    implicit val playJsonArrayTypeMapper: DriverJdbcType[List[JsValue]] =
      new AdvancedArrayJdbcType[JsValue](
        MyPostgresProfile.this.pgjson, // Use PgPlayJsonSupport.this.pgjson
        s => utils.SimpleArrayUtils.fromString[JsValue](Json.parse(_))(s).orNull,
        v => utils.SimpleArrayUtils.mkString[JsValue](_.toString())(v)
      ).to(_.toList)

    // JSONB Type Mappers for the new SequenceFile fields
    implicit val sequenceFileChecksumsJsonbTypeMapper: JdbcType[List[SequenceFileChecksumJsonb]] with BaseTypedType[List[SequenceFileChecksumJsonb]] =
      MappedJdbcType.base[List[SequenceFileChecksumJsonb], JsValue](Json.toJson(_), _.as[List[SequenceFileChecksumJsonb]])

    implicit val sequenceFileHttpLocationsJsonbTypeMapper: JdbcType[List[SequenceFileHttpLocationJsonb]] with BaseTypedType[List[SequenceFileHttpLocationJsonb]] =
      MappedJdbcType.base[List[SequenceFileHttpLocationJsonb], JsValue](Json.toJson(_), _.as[List[SequenceFileHttpLocationJsonb]])

    implicit val sequenceFileAtpLocationJsonbTypeMapper: JdbcType[Option[SequenceFileAtpLocationJsonb]] with BaseTypedType[Option[SequenceFileAtpLocationJsonb]] = {
      // Import JsNull, JsObject locally for clarity
      import play.api.libs.json.{JsNull, JsObject}
      MappedJdbcType.base[Option[SequenceFileAtpLocationJsonb], JsValue](
        {
          case Some(atp) => Json.toJson(atp)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull || (jsValue.isInstanceOf[JsObject] && jsValue.as[JsObject].value.isEmpty)) None
          else Some(jsValue.as[SequenceFileAtpLocationJsonb])
        }
      )
    }

    // --- Population Breakdown JSONB Type Mappers ---
    import models.domain.genomics.{PcaCoordinatesJsonb, SuperPopulationListJsonb}

    implicit val pcaCoordinatesJsonbTypeMapper: JdbcType[Option[PcaCoordinatesJsonb]] with BaseTypedType[Option[PcaCoordinatesJsonb]] = {
      import play.api.libs.json.{JsNull, JsObject}
      MappedJdbcType.base[Option[PcaCoordinatesJsonb], JsValue](
        {
          case Some(pca) => Json.toJson(pca)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull || (jsValue.isInstanceOf[JsObject] && jsValue.as[JsObject].value.isEmpty)) None
          else Some(jsValue.as[PcaCoordinatesJsonb])
        }
      )
    }

    implicit val superPopulationListJsonbTypeMapper: JdbcType[Option[SuperPopulationListJsonb]] with BaseTypedType[Option[SuperPopulationListJsonb]] = {
      import play.api.libs.json.{JsNull, JsObject}
      MappedJdbcType.base[Option[SuperPopulationListJsonb], JsValue](
        {
          case Some(sp) => Json.toJson(sp)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull || (jsValue.isInstanceOf[JsObject] && jsValue.as[JsObject].value.isEmpty)) None
          else Some(jsValue.as[SuperPopulationListJsonb])
        }
      )
    }

    // --- Genotype Data JSONB Type Mappers ---
    import models.atmosphere.FileInfo
    import models.domain.genomics.GenotypeMetrics

    implicit val genotypeMetricsJsonbTypeMapper: JdbcType[GenotypeMetrics] with BaseTypedType[GenotypeMetrics] =
      MappedJdbcType.base[GenotypeMetrics, JsValue](Json.toJson(_), _.as[GenotypeMetrics])

    implicit val fileInfoSeqJsonbTypeMapper: JdbcType[Option[Seq[FileInfo]]] with BaseTypedType[Option[Seq[FileInfo]]] = {
      import play.api.libs.json.{JsNull, JsArray}
      MappedJdbcType.base[Option[Seq[FileInfo]], JsValue](
        {
          case Some(files) => Json.toJson(files)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull) None
          else Some(jsValue.as[Seq[FileInfo]])
        }
      )
    }


    // --- Haplogroup Reconciliation JSONB Type Mappers ---
    import models.domain.genomics.{DnaType, CompatibilityLevel, ReconciliationStatus}
    import models.atmosphere.{RunHaplogroupCall, SnpConflict, HeteroplasmyObservation, IdentityVerification, ManualOverride, AuditEntry}

    implicit val dnaTypeMapper: JdbcType[DnaType] =
      createEnumJdbcType("dna_type", _.toString, s => DnaType.fromString(s).getOrElse(
        throw new IllegalArgumentException(s"Invalid DnaType value: $s")
      ), quoteName = false)

    implicit val reconciliationStatusJsonbTypeMapper: JdbcType[ReconciliationStatus] with BaseTypedType[ReconciliationStatus] =
      MappedJdbcType.base[ReconciliationStatus, JsValue](Json.toJson(_), _.as[ReconciliationStatus])

    implicit val compatibilityLevelMapper: JdbcType[CompatibilityLevel] =
      createEnumJdbcType("compatibility_level", _.toString, s => CompatibilityLevel.fromString(s).getOrElse(
        throw new IllegalArgumentException(s"Invalid CompatibilityLevel value: $s")
      ), quoteName = false)


    implicit val warningsSeqJsonbTypeMapper: JdbcType[Option[Seq[String]]] with BaseTypedType[Option[Seq[String]]] = {
      import play.api.libs.json.{JsNull, JsArray}
      MappedJdbcType.base[Option[Seq[String]], JsValue](
        {
          case Some(warnings) => Json.toJson(warnings)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull) None
          else Some(jsValue.as[Seq[String]])
        }
      )
    }

    implicit val runHaplogroupCallsJsonbTypeMapper: JdbcType[Seq[RunHaplogroupCall]] with BaseTypedType[Seq[RunHaplogroupCall]] =
      MappedJdbcType.base[Seq[RunHaplogroupCall], JsValue](Json.toJson(_), _.as[Seq[RunHaplogroupCall]])

    implicit val snpConflictsJsonbTypeMapper: JdbcType[Option[Seq[SnpConflict]]] with BaseTypedType[Option[Seq[SnpConflict]]] = {
      import play.api.libs.json.JsNull
      MappedJdbcType.base[Option[Seq[SnpConflict]], JsValue](
        {
          case Some(conflicts) => Json.toJson(conflicts)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull) None
          else Some(jsValue.as[Seq[SnpConflict]])
        }
      )
    }

    implicit val heteroplasmyObservationsJsonbTypeMapper: JdbcType[Option[Seq[HeteroplasmyObservation]]] with BaseTypedType[Option[Seq[HeteroplasmyObservation]]] = {
      import play.api.libs.json.JsNull
      MappedJdbcType.base[Option[Seq[HeteroplasmyObservation]], JsValue](
        {
          case Some(obs) => Json.toJson(obs)
          case None => JsNull
        },
        { jsValue =>
          if (jsValue == JsNull) None
          else Some(jsValue.as[Seq[HeteroplasmyObservation]])
        }
      )
    }

    implicit val identityVerificationJsonbTypeMapper: JdbcType[Option[IdentityVerification]] with BaseTypedType[Option[IdentityVerification]] = {
      import play.api.libs.json.{JsNull, JsObject}
      MappedJdbcType.base[Option[IdentityVerification], JsValue](
        {
          case Some(iv) => Json.toJson(iv)
          case None => JsNull
        },
        { jsValue =>
          // Handle database NULL (Java null), JSON null, or empty object
          if (jsValue == null || jsValue == JsNull || (jsValue.isInstanceOf[JsObject] && jsValue.as[JsObject].value.isEmpty)) None
          else Some(jsValue.as[IdentityVerification])
        }
      )
    }

    implicit val manualOverrideJsonbTypeMapper: JdbcType[Option[ManualOverride]] with BaseTypedType[Option[ManualOverride]] = {
      import play.api.libs.json.{JsNull, JsObject}
      MappedJdbcType.base[Option[ManualOverride], JsValue](
        {
          case Some(mo) => Json.toJson(mo)
          case None => JsNull
        },
        { jsValue =>
          // Handle database NULL (Java null), JSON null, or empty object
          if (jsValue == null || jsValue == JsNull || (jsValue.isInstanceOf[JsObject] && jsValue.as[JsObject].value.isEmpty)) None
          else Some(jsValue.as[ManualOverride])
        }
      )
    }

    implicit val auditLogJsonbTypeMapper: JdbcType[Option[Seq[AuditEntry]]] with BaseTypedType[Option[Seq[AuditEntry]]] = {
      import play.api.libs.json.JsNull
      MappedJdbcType.base[Option[Seq[AuditEntry]], JsValue](
        {
          case Some(entries) => Json.toJson(entries)
          case None => JsNull
        },
        { jsValue =>
          // Handle database NULL (Java null) or JSON null
          if (jsValue == null || jsValue == JsNull) None
          else Some(jsValue.as[Seq[AuditEntry]])
        }
      )
    }

    // --- Haplogroup Provenance JSONB Type Mapper ---
    // Maps HaplogroupProvenance directly to JsValue. For nullable columns, use column[Option[HaplogroupProvenance]]
    // and Slick will handle NULL automatically.
    import models.domain.haplogroups.HaplogroupProvenance

    implicit val haplogroupProvenanceJsonbTypeMapper: JdbcType[HaplogroupProvenance] with BaseTypedType[HaplogroupProvenance] =
      MappedJdbcType.base[HaplogroupProvenance, JsValue](Json.toJson(_), _.as[HaplogroupProvenance])

    // Declare the name of an aggregate function:
    val ArrayAgg = new SqlAggregateFunction("array_agg")

    // Implement the aggregate function as an extension method:
    implicit class ArrayAggColumnQueryExtensionMethods[P, C[_]](val q: Query[Rep[P], _, C]) {
      def arrayAgg[B](implicit tm: TypedType[List[B]]) = ArrayAgg.column[List[B]](q.toNode)
    }
  }
}

object MyPostgresProfile extends MyPostgresProfile