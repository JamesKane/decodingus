package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.HaplogroupCharacterState
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupCharacterStateRepository {
  def findByHaplogroup(haplogroupId: Int): Future[Seq[HaplogroupCharacterState]]
  def findByHaplogroupAndVariants(haplogroupId: Int, variantIds: Seq[Int]): Future[Seq[HaplogroupCharacterState]]
  def findStrStatesForHaplogroup(haplogroupId: Int, strVariantIds: Seq[Int]): Future[Seq[HaplogroupCharacterState]]
}

class HaplogroupCharacterStateRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupCharacterStateRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.genomics.haplogroupCharacterStates
  import models.dal.MyPostgresProfile.api.*

  override def findByHaplogroup(haplogroupId: Int): Future[Seq[HaplogroupCharacterState]] =
    runQuery(haplogroupCharacterStates.filter(_.haplogroupId === haplogroupId).result)

  override def findByHaplogroupAndVariants(haplogroupId: Int, variantIds: Seq[Int]): Future[Seq[HaplogroupCharacterState]] =
    runQuery(haplogroupCharacterStates
      .filter(r => r.haplogroupId === haplogroupId && r.variantId.inSet(variantIds))
      .result)

  override def findStrStatesForHaplogroup(haplogroupId: Int, strVariantIds: Seq[Int]): Future[Seq[HaplogroupCharacterState]] =
    runQuery(haplogroupCharacterStates
      .filter(r => r.haplogroupId === haplogroupId && r.variantId.inSet(strVariantIds))
      .result)
}
