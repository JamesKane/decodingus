package repositories

import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.user.User
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserRepository @Inject()(
                                protected val dbConfigProvider: DatabaseConfigProvider
                              )(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  private val users = DatabaseSchema.domain.users

  def create(user: User): Future[User] = {
    db.run((users returning users) += user)
  }

  def findById(id: UUID): Future[Option[User]] = {
    db.run(users.filter(_.id === id).result.headOption)
  }

  def findByDid(did: String): Future[Option[User]] = {
    db.run(users.filter(_.did === did).result.headOption)
  }

  def findByEmail(email: String): Future[Option[User]] = {
    db.run(users.filter(_.email === email).result.headOption)
  }

  def update(user: User): Future[Int] = {
    db.run(users.filter(_.id === user.id).update(user))
  }

  def delete(id: UUID): Future[Int] = {
    db.run(users.filter(_.id === id).delete)
  }
}