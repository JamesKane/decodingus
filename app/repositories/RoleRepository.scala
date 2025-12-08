package repositories

import jakarta.inject.{Inject, Singleton}
import models.auth.Role
import models.dal.DatabaseSchema
import play.api.db.slick.DatabaseConfigProvider

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RoleRepository @Inject()(
                                override protected val dbConfigProvider: DatabaseConfigProvider
                              )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider) {

  import models.dal.MyPostgresProfile.api.*

  private val roles = DatabaseSchema.auth.roles

  def findByName(name: String): Future[Option[Role]] = {
    db.run(roles.filter(_.name === name).result.headOption)
  }

  def create(role: Role): Future[Role] = {
    db.run((roles returning roles) += role)
  }
  
  def findAll(): Future[Seq[Role]] = {
    db.run(roles.result)
  }
}
