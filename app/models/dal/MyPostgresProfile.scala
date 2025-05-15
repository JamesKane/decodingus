package models.dal

import com.github.tminglei.slickpg._
import play.api.libs.json.{ JsValue, Json, OFormat, OWrites }
import slick.basic.Capability
import slick.jdbc.{ JdbcCapabilities, JdbcType }

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
  with PgPlayJsonSupport
  with array.PgArrayJdbcTypes {
  def pgjson = "jsonb" // jsonb support is in postgres 9.4.0 onward; for 9.3.x use "json"

  import slick.ast.Library._
  import slick.ast._
  import slick.lifted.FunctionSymbolExtensionMethods._

  // Add back `capabilities.insertOrUpdate` to enable native `upsert` support; for postgres 9.5+
  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + JdbcCapabilities.insertOrUpdate

  override val api: MyAPI.type = MyAPI

  case class JBean(name: String, count: Int)

  object JBean {
    implicit val jbeanFmt: OFormat[JBean] = Json.format[JBean]
    implicit val jbeanWrt: OWrites[JBean] = Json.writes[JBean]
  }

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

object MyPostgresProfile extends MyPostgresProfile