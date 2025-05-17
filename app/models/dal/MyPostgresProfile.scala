package models.dal

import com.github.tminglei.slickpg.*
import play.api.libs.json.{JsValue, Json, OFormat, OWrites}
import slick.basic.Capability
import slick.jdbc.{JdbcCapabilities, JdbcType}

import scala.language.higherKinds

/**
 * A custom PostgreSQL profile extending `ExPostgresProfile` with additional support for various PostgreSQL features.
 * This trait provides extended capabilities such as JSON, array, range, HStore, search, PostGIS, and other PostgreSQL-specific features.
 * It also integrates with Play JSON to enable mapping between case classes and JSON types for database operations.
 *
 * The profile includes:
 * - Array handling with custom mappings for JSON and case class arrays.
 * - PostgreSQL JSON/JSONB support.
 * - PostgreSQL range types support.
 * - HStore key-value storage integration.
 * - Full-text search capabilities.
 * - PostGIS (geospatial) extensions.
 * - Network address types handling.
 * - LTree (label tree) extension for hierarchical data structures.
 *
 * Provides a custom API (`MyAPI`) which extends `ExtPostgresAPI` and includes additional implicits for seamless integration
 * of PostgreSQL extensions with Slick queries.
 *
 * Native support for `upsert` operations is enabled for PostgreSQL 9.5+ by adding `JdbcCapabilities.insertOrUpdate`
 * to the set of supported capabilities.
 *
 * @note The default PostgreSQL JSON type is set to `jsonb` for improved performance with PostgreSQL 9.4.0 or higher.
 *       For compatibility with PostgreSQL 9.3.x, the type can be changed to `json`.
 */
trait MyPostgresProfile extends ExPostgresProfile
  with PgArraySupport
  with PgDate2Support
  with PgRangeSupport
  with PgHStoreSupport
  with PgSearchSupport
  with PgPostGISSupport
  with PgNetSupport
  with PgLTreeSupport
  with PgPlayJsonSupport
  with array.PgArrayJdbcTypes {
  def pgjson = "jsonb" // jsonb support is in postgres 9.4.0 onward; for 9.3.x use "json"

  import slick.ast.*
  import slick.ast.Library.*
  import slick.lifted.FunctionSymbolExtensionMethods.*

  // Add back `capabilities.insertOrUpdate` to enable native `upsert` support; for postgres 9.5+
  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + JdbcCapabilities.insertOrUpdate

  override val api: MyAPI.type = MyAPI

  case class JBean(name: String, count: Int)

  object JBean {
    implicit val jbeanFmt: OFormat[JBean] = Json.format[JBean]
    implicit val jbeanWrt: OWrites[JBean] = Json.writes[JBean]
  }

  /**
   * Extends various Slick PostgreSQL profile traits and implicits to provide enhanced database functionality specific to PostgreSQL features.
   *
   * This object offers:
   * - Custom data type column mappers (`JdbcType`) for handling arrays, JSON, and other specialized PostgreSQL types.
   * - Implicit conversions and extensions for PostgreSQL-specific aggregate functions and SQL capabilities.
   *
   * Key Features:
   * - `strListTypeMapper`: Implicit mapper for PostgreSQL text arrays, converting them into Scala `List[String]`.
   * - `beanJsonTypeMapper`: Mapper for marshalling and unmarshalling objects of type `JBean` using Play JSON.
   * - `playJsonArrayTypeMapper`: Handles JSON array columns in PostgreSQL, allowing conversion to Scala `List[JsValue]`.
   * - `beanArrayTypeMapper`: Supports arrays of custom case class objects (`JBean`), leveraging Play JSON for serialization.
   * - `ArrayAgg`: PostgreSQL `array_agg` SQL aggregate function, allowing aggregation of rows into arrays.
   *
   * Additionally, custom extensions such as `ArrayAggColumnQueryExtensionMethods` are provided for integrating
   * PostgreSQL functionality with Slick's query abstraction, enabling more natural query construction with aggregate functions.
   */
  object MyAPI extends ExtPostgresAPI with ArrayImplicits
    with Date2DateTimeImplicitsDuration
    with NetImplicits
    with LTreeImplicits
    with RangeImplicits
    with HStoreImplicits
    with PostGISPlainImplicits
    with JsonImplicits
    with PostGISImplicits
    with PostGISAssistants
    with SearchImplicits
    with SearchAssistants {

    implicit val strListTypeMapper: DriverJdbcType[List[String]] = new SimpleArrayJdbcType[String]("text").to(_.toList)
    implicit val beanJsonTypeMapper: JdbcType[JBean] with BaseTypedType[JBean] = MappedJdbcType.base[JBean, JsValue](Json.toJson(_), _.as[JBean])

    implicit val playJsonArrayTypeMapper: DriverJdbcType[List[JsValue]] =
      new AdvancedArrayJdbcType[JsValue](
        pgjson,
        s => utils.SimpleArrayUtils.fromString[JsValue](Json.parse(_))(s).orNull,
        v => utils.SimpleArrayUtils.mkString[JsValue](_.toString())(v)
      ).to(_.toList)

    implicit val beanArrayTypeMapper: DriverJdbcType[List[JBean]] =
      new AdvancedArrayJdbcType[JBean](
        pgjson,
        (s) => utils.SimpleArrayUtils.fromString[JBean](Json.parse(_).as[JBean])(s).orNull,
        (v) => utils.SimpleArrayUtils.mkString[JBean](b => Json.stringify(Json.toJson(b)))(v)
      ).to(_.toList)

    // Declare the name of an aggregate function:
    val ArrayAgg = new SqlAggregateFunction("array_agg")

    // Implement the aggregate function as an extension method:
    implicit class ArrayAggColumnQueryExtensionMethods[P, C[_]](val q: Query[Rep[P], _, C]) {
      def arrayAgg[B](implicit tm: TypedType[List[B]]) = ArrayAgg.column[List[B]](q.toNode)
    }
  }
}

/**
 * Represents a custom PostgreSQL profile used for database interactions.
 *
 * This object extends a tailored `MyPostgresProfile` that integrates various
 * additional PostgreSQL features and capabilities provided by the 
 * `slick-pg` library.
 *
 * Key features of this profile include:
 * - Support for JSON/JSONB columns.
 * - Extended support for PostgreSQL-specific data types such as arrays, ranges,
 * hstore, and geometric data.
 * - Support for full-text search and PostGIS integration for spatial data.
 * - Compatibility with custom mappers such as Play JSON.
 * - Ability to perform native `upsert` operations for PostgreSQL 9.5+ using the
 * `capabilities.insertOrUpdate`.
 *
 * This profile provides a custom API for use within the application and enables
 * the seamless integration of additional PostgreSQL features not natively supported
 * by standard Slick profiles.
 */
object MyPostgresProfile extends MyPostgresProfile