package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{DataGenerationMethod, TestTypeRow}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestTypeRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends TestTypeRepository with HasDatabaseConfigProvider[JdbcProfile] {

  private val testTypeDefinitions = DatabaseSchema.domain.genomics.testTypeDefinition

  override def findByCode(code: String): Future[Option[TestTypeRow]] = {
    db.run(testTypeDefinitions.filter(_.code === code).result.headOption) // Changed .name to .code
  }

  override def listAll(): Future[Seq[TestTypeRow]] = {
    db.run(testTypeDefinitions.result)
  }

  override def findByCategory(category: DataGenerationMethod): Future[Seq[TestTypeRow]] = {
    db.run(testTypeDefinitions.filter(_.category === category).result) // Correctly accesses .category
  }

  override def findByCapability(
    supportsY: Option[Boolean] = None,
    supportsMt: Option[Boolean] = None,
    supportsAutosomalIbd: Option[Boolean] = None,
    supportsAncestry: Option[Boolean] = None
  ): Future[Seq[TestTypeRow]] = {
    val query = testTypeDefinitions.filter { ttd =>
      val conditions = Seq(
        supportsY.map(s => ttd.supportsHaplogroupY === s),
        supportsMt.map(s => ttd.supportsHaplogroupMt === s),
        supportsAutosomalIbd.map(s => ttd.supportsAutosomalIbd === s),
        supportsAncestry.map(s => ttd.supportsAncestry === s)
      ).flatten // This creates Seq[Rep[Boolean]]

      if (conditions.isEmpty) {
        true.asColumnOf[Boolean] // If no conditions, return true
      } else {
        conditions.reduceLeft(_ && _) // Combine conditions with AND
      }
    }
    db.run(query.result)
  }

  override def create(testType: TestTypeRow): Future[TestTypeRow] = {
    db.run(
      (testTypeDefinitions returning testTypeDefinitions.map(_.id)
        into ((tt, id) => tt.copy(id = Some(id)))) += testType
    )
  }

  override def update(testType: TestTypeRow): Future[Boolean] = {
    testType.id match {
      case None => Future.successful(false)
      case Some(id) =>
        db.run(testTypeDefinitions.filter(_.id === id).update(testType)).map(_ > 0)
    }
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(testTypeDefinitions.filter(_.id === id).delete).map(_ > 0)
  }

  override def getTestTypeRowsByIds(ids: Seq[Int]): Future[Seq[TestTypeRow]] = {
    db.run(testTypeDefinitions.filter(_.id inSet ids).result)
  }
}