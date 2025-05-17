package repositories

import jakarta.inject.Inject
import models.{Haplogroup, HaplogroupType}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupCoreRepository {
  def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]]
  def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]]
  def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]]
}

class HaplogroupCoreRepositoryImpl @Inject()(
                                              dbConfigProvider: DatabaseConfigProvider
                                            )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupCoreRepository {

  import models.dal.DatabaseSchema.haplogroups
  import models.dal.DatabaseSchema.haplogroupRelationships
  import models.dal.MyPostgresProfile.api.*

  override def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(h => h.name === name && h.haplogroupType === haplogroupType.toString)
      .result
      .headOption

    runQuery(query)
  }

  override def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val recursiveQuery = for {
      rel <- haplogroupRelationships if rel.childHaplogroupId === haplogroupId
      parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId
    } yield parent

    runQuery(recursiveQuery.result)
  }

  override def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.parentHaplogroupId === haplogroupId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield child

    runQuery(query.result)
  }
}
