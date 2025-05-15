package repositories

import com.vividsolutions.jts.geom.Point
import com.vividsolutions.jts.io.WKBReader
import jakarta.inject.Inject
import models.Biosample
import models.api.{BiosampleWithOrigin, GeoCoord}
import models.dal.{DatabaseSchema, MyPostgresProfile}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.GetResult

import scala.concurrent.{ExecutionContext, Future}

trait BiosampleRepository {
  def findById(id: Int): Future[Option[Biosample]]
  def findBiosamplesWithOriginForPublication(publicationId: Int): Future[Seq[BiosampleWithOrigin]]
  def findPaginatedBiosamplesWithOriginForPublication(publicationId: Int, page: Int, pageSize: Int): Future[Seq[BiosampleWithOrigin]]
  def countBiosamplesForPublication(publicationId: Int): Future[Long]
}

class BiosampleRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)
                                       (implicit ec: ExecutionContext) extends BiosampleRepository with HasDatabaseConfigProvider[MyPostgresProfile] {
  import models.dal.MyPostgresProfile.api.*
  private val biosamplesTable = DatabaseSchema.biosamples

  private def readPoint(pgObj: AnyRef): Option[GeoCoord] = pgObj match {
    case null => None
    case _ =>
      try {
        val wkbReader = new WKBReader()
        val point = wkbReader.read(WKBReader.hexToBytes(pgObj.toString)).asInstanceOf[Point]
        Some(GeoCoord(point.getX, point.getY))
      } catch {
        case e: Exception =>
          println(s"Error reading WKB: ${pgObj.toString} - ${e.getMessage}")
          None
      }
  }

  implicit val getBiosampleWithOriginResult: GetResult[BiosampleWithOrigin] = GetResult(r =>
    BiosampleWithOrigin(
      sampleName = r.nextStringOption(),
      enaAccession = r.nextString(),
      sex = r.nextStringOption(),
      geoCoord = r.nextObjectOption().flatMap(readPoint),
      yDnaHaplogroup = r.nextStringOption(),
      mtDnaHaplogroup = r.nextStringOption()
    )
  )

  override def findById(id: Int): Future[Option[Biosample]] = {
    db.run(biosamplesTable.filter(_.id === id).result.headOption)
  }


  override def findBiosamplesWithOriginForPublication(publicationId: Int): Future[Seq[BiosampleWithOrigin]] = {
    val sqlString =
      s"""
         SELECT b.alias,
                b.sample_accession,
                b.sex,
                b.geocoord,
                MAX(CASE WHEN h.haplogroup_type = 'Y' THEN h.name END)  AS y_haplogroup_name,
                MAX(CASE WHEN h.haplogroup_type = 'MT' THEN h.name END) AS mt_haplogroup_name
         FROM publication_biosample pb
                  INNER JOIN
              public.biosample b ON b.id = pb.biosample_id
                  LEFT JOIN
              biosample_haplogroup bh ON bh.sample_guid = b.sample_guid
                  LEFT JOIN
              haplogroup h ON h.haplogroup_id = bh.y_haplogroup_id AND h.haplogroup_type IN ('Y', 'MT')
         WHERE pb.publication_id = $publicationId
         GROUP BY b.alias, b.sample_accession, b.sex, b.geocoord;
      """

    db.run(sql"#${sqlString}".as[BiosampleWithOrigin])
  }

  def findPaginatedBiosamplesWithOriginForPublication(publicationId: Int, page: Int, pageSize: Int): Future[Seq[BiosampleWithOrigin]] = {
    val offset = (page - 1) * pageSize
    val sqlString =
      s"""
           SELECT b.alias,
                  b.sample_accession,
                  b.sex,
                  b.geocoord,
                  MAX(CASE WHEN h.haplogroup_type = 'Y' THEN h.name END)  AS y_haplogroup_name,
                  MAX(CASE WHEN h.haplogroup_type = 'MT' THEN h.name END) AS mt_haplogroup_name
           FROM publication_biosample pb
                    INNER JOIN
                public.biosample b ON b.id = pb.biosample_id
                    LEFT JOIN
                biosample_haplogroup bh ON bh.sample_guid = b.sample_guid
                    LEFT JOIN
                haplogroup h ON h.haplogroup_id = bh.y_haplogroup_id AND h.haplogroup_type IN ('Y', 'MT')
           WHERE pb.publication_id = $publicationId
           GROUP BY b.alias, b.sample_accession, b.sex, b.geocoord
           LIMIT $pageSize OFFSET $offset;
        """
    db.run(sql"#${sqlString}".as[BiosampleWithOrigin])
  }

  def countBiosamplesForPublication(publicationId: Int): Future[Long] = {
    val sqlString =
      s"""
           SELECT COUNT(DISTINCT b.id)
           FROM publication_biosample pb
                    INNER JOIN
                public.biosample b ON b.id = pb.biosample_id
           WHERE pb.publication_id = $publicationId;
        """
    db.run(sql"#${sqlString}".as[Long].head)
  }

}