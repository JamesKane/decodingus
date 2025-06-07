package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.genomics.{BiologicalSex, BiosampleType, SpecimenDonor}
import play.api.db.slick.DatabaseConfigProvider
import com.vividsolutions.jts.geom.Point

import scala.concurrent.{ExecutionContext, Future}

trait SpecimenDonorRepository {
  def findById(id: Int): Future[Option[SpecimenDonor]]
  def create(donor: SpecimenDonor): Future[SpecimenDonor]
  def update(donor: SpecimenDonor): Future[Boolean]
  def upsert(donor: SpecimenDonor): Future[SpecimenDonor]
  def findByIdentifier(identifier: String): Future[Option[SpecimenDonor]]
  def findByOriginBiobank(biobank: String): Future[Seq[SpecimenDonor]]
  def findByType(donorType: BiosampleType): Future[Seq[SpecimenDonor]]
  def findBySex(sex: BiologicalSex): Future[Seq[SpecimenDonor]]
  def getAllGeoLocations: Future[Seq[(Point, Int)]]
  def findByBiobankAndType(biobank: String, donorType: BiosampleType): Future[Seq[SpecimenDonor]]
  def deleteMany(ids: Seq[Int]): Future[Int]
  def transferBiosamples(fromDonorIds: Seq[Int], toDonorId: Int): Future[Int]

}

@Singleton
class SpecimenDonorRepositoryImpl @Inject()(
                                             override protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SpecimenDonorRepository {

  import models.dal.MyPostgresProfile.api._

  private val donorsTable = DatabaseSchema.domain.genomics.specimenDonors
  private val biosamplesTable = DatabaseSchema.domain.genomics.biosamples

  override def findById(id: Int): Future[Option[SpecimenDonor]] = {
    db.run(donorsTable.filter(_.id === id).result.headOption)
  }

  override def create(donor: SpecimenDonor): Future[SpecimenDonor] = {
    val insertQuery = (donorsTable returning donorsTable.map(_.id)
      into ((d, id) => d.copy(id = Some(id))))
      .+=(donor)

    db.run(insertQuery.transactionally)
  }

  override def update(donor: SpecimenDonor): Future[Boolean] = {
    donor.id match {
      case None => Future.successful(false)
      case Some(id) =>
        db.run(
          donorsTable
            .filter(_.id === id)
            .map(d => (
              d.donorIdentifier,
              d.originBiobank,
              d.donorType,
              d.sex,
              d.geocoord,
              d.pgpParticipantId,
              d.citizenBiosampleDid,
              d.dateRangeStart,
              d.dateRangeEnd
            ))
            .update((
              donor.donorIdentifier,
              donor.originBiobank,
              donor.donorType,
              donor.sex,
              donor.geocoord,
              donor.pgpParticipantId,
              donor.citizenBiosampleDid,
              donor.dateRangeStart,
              donor.dateRangeEnd
            ))
            .map(_ > 0)
        )
    }
  }

  override def upsert(donor: SpecimenDonor): Future[SpecimenDonor] = {
    val query = for {
      existing <- donorsTable.filter(_.donorIdentifier === donor.donorIdentifier).result.headOption
      result <- existing match {
        case Some(existingDonor) =>
          // Update existing donor
          donorsTable
            .filter(_.id === existingDonor.id)
            .map(d => (
              d.originBiobank,
              d.donorType,
              d.sex,
              d.geocoord,
              d.pgpParticipantId,
              d.citizenBiosampleDid,
              d.dateRangeStart,
              d.dateRangeEnd
            ))
            .update((
              donor.originBiobank,
              donor.donorType,
              donor.sex,
              donor.geocoord,
              donor.pgpParticipantId,
              donor.citizenBiosampleDid,
              donor.dateRangeStart,
              donor.dateRangeEnd
            ))
            .map(_ => donor.copy(id = existingDonor.id))

        case None =>
          // Insert new donor
          (donorsTable returning donorsTable.map(_.id)
            into ((d, id) => d.copy(id = Some(id))))
            .+=(donor)
      }
    } yield result

    db.run(query.transactionally)
  }

  override def findByIdentifier(identifier: String): Future[Option[SpecimenDonor]] = {
    db.run(donorsTable.filter(_.donorIdentifier === identifier).result.headOption)
  }

  override def findByOriginBiobank(biobank: String): Future[Seq[SpecimenDonor]] = {
    db.run(donorsTable.filter(_.originBiobank === biobank).result)
  }

  override def findByType(donorType: BiosampleType): Future[Seq[SpecimenDonor]] = {
    db.run(donorsTable.filter(_.donorType === donorType).result)
  }

  override def findBySex(sex: BiologicalSex): Future[Seq[SpecimenDonor]] = {
    db.run(donorsTable.filter(_.sex === sex).result)
  }

  override def getAllGeoLocations: Future[Seq[(Point, Int)]] = {
    val query = donorsTable
      .filter(_.geocoord.isDefined)
      .groupBy(_.geocoord)
      .map { case (point, group) =>
        (point.asColumnOf[Point], group.length)
      }

    db.run(query.result)
  }

  def findByBiobankAndType(
                            biobank: String,
                            donorType: BiosampleType
                          ): Future[Seq[SpecimenDonor]] = {
    db.run(
      donorsTable
        .filter(d => d.originBiobank === biobank && d.donorType === donorType)
        .result
    )
  }

  override def deleteMany(ids: Seq[Int]): Future[Int] = {
    db.run(donorsTable.filter(_.id.inSet(ids)).delete)
  }

  def transferBiosamples(fromDonorIds: Seq[Int], toDonorId: Int): Future[Int] = {
    import MyPostgresProfile.api._

    db.run(
      biosamplesTable
        .filter(_.specimenDonorId inSet fromDonorIds)
        .map(_.specimenDonorId)
        .update(Some(toDonorId))
    )
  }

}