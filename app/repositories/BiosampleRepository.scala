package repositories

import jakarta.inject.Inject
import models.Biosample
import models.dal.{DatabaseSchema, MyPostgresProfile}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

trait BiosampleRepository {
  def findById(id: Int): Future[Option[Biosample]]
}

class BiosampleRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)
                             (implicit ec: ExecutionContext) extends BiosampleRepository with HasDatabaseConfigProvider[MyPostgresProfile] {
  import models.dal.MyPostgresProfile.api.*
  private val biosamplesTable = DatabaseSchema.biosamples

  override def findById(id: Int): Future[Option[Biosample]] = {
    db.run(biosamplesTable.filter(_.id === id).result.headOption)
  }
}