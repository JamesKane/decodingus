package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.publications.CitizenBiosampleOriginalHaplogroup
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait CitizenBiosampleOriginalHaplogroupRepository {
  def create(info: CitizenBiosampleOriginalHaplogroup): Future[CitizenBiosampleOriginalHaplogroup]
  def deleteByCitizenBiosampleId(citizenBiosampleId: Int): Future[Int]
}

class CitizenBiosampleOriginalHaplogroupRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends CitizenBiosampleOriginalHaplogroupRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api.*

  private val table = DatabaseSchema.domain.publications.citizenBiosampleOriginalHaplogroups

  override def create(info: CitizenBiosampleOriginalHaplogroup): Future[CitizenBiosampleOriginalHaplogroup] = {
    val insertQuery = (table returning table.map(_.id)
      into ((item, id) => item.copy(id = Some(id)))) += info
    db.run(insertQuery)
  }

  override def deleteByCitizenBiosampleId(citizenBiosampleId: Int): Future[Int] = {
    db.run(table.filter(_.citizenBiosampleId === citizenBiosampleId).delete)
  }
}
