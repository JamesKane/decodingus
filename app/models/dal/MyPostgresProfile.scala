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
    import models.domain.genomics.HaplogroupResult
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


    // Declare the name of an aggregate function:
    val ArrayAgg = new SqlAggregateFunction("array_agg")

    // Implement the aggregate function as an extension method:
    implicit class ArrayAggColumnQueryExtensionMethods[P, C[_]](val q: Query[Rep[P], _, C]) {
      def arrayAgg[B](implicit tm: TypedType[List[B]]) = ArrayAgg.column[List[B]](q.toNode)
    }
  }
}

object MyPostgresProfile extends MyPostgresProfile