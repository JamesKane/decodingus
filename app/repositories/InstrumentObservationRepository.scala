package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.{InstrumentObservation, ObservationConfidence}
import play.api.db.slick.DatabaseConfigProvider
import slick.ast.BaseTypedType

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait InstrumentObservationRepository {
  def create(observation: InstrumentObservation): Future[InstrumentObservation]
  def findByAtUri(atUri: String): Future[Option[InstrumentObservation]]
  def findByInstrumentId(instrumentId: String): Future[Seq[InstrumentObservation]]
  def findByLabName(labName: String): Future[Seq[InstrumentObservation]]
  def findByBiosampleRef(biosampleRef: String): Future[Seq[InstrumentObservation]]
  def update(observation: InstrumentObservation): Future[Boolean]
  def deleteByAtUri(atUri: String): Future[Boolean]
}

@Singleton
class InstrumentObservationRepositoryImpl @Inject()(
                                                     override protected val dbConfigProvider: DatabaseConfigProvider
                                                   )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with InstrumentObservationRepository {

  import models.dal.MyPostgresProfile.api.*

  implicit private val confidenceMapper: BaseTypedType[ObservationConfidence] =
    MappedColumnType.base[ObservationConfidence, String](_.dbValue, ObservationConfidence.fromString)

  private val observations = DatabaseSchema.domain.genomics.instrumentObservations

  override def create(observation: InstrumentObservation): Future[InstrumentObservation] = {
    db.run(
      (observations returning observations.map(_.id)
        into ((o, id) => o.copy(id = Some(id)))) += observation
    )
  }

  override def findByAtUri(atUri: String): Future[Option[InstrumentObservation]] = {
    db.run(observations.filter(_.atUri === atUri).result.headOption)
  }

  override def findByInstrumentId(instrumentId: String): Future[Seq[InstrumentObservation]] = {
    db.run(observations.filter(_.instrumentId === instrumentId).result)
  }

  override def findByLabName(labName: String): Future[Seq[InstrumentObservation]] = {
    db.run(observations.filter(_.labName === labName).result)
  }

  override def findByBiosampleRef(biosampleRef: String): Future[Seq[InstrumentObservation]] = {
    db.run(observations.filter(_.biosampleRef === biosampleRef).result)
  }

  override def update(observation: InstrumentObservation): Future[Boolean] = {
    db.run(
      observations.filter(_.atUri === observation.atUri)
        .map(o => (o.atCid, o.instrumentId, o.labName, o.biosampleRef, o.sequenceRunRef,
          o.platform, o.instrumentModel, o.flowcellId, o.runDate, o.confidence, o.updatedAt))
        .update((observation.atCid, observation.instrumentId, observation.labName,
          observation.biosampleRef, observation.sequenceRunRef, observation.platform,
          observation.instrumentModel, observation.flowcellId, observation.runDate,
          observation.confidence, Some(LocalDateTime.now())))
    ).map(_ > 0)
  }

  override def deleteByAtUri(atUri: String): Future[Boolean] = {
    db.run(observations.filter(_.atUri === atUri).delete.map(_ > 0))
  }
}
