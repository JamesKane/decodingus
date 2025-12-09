package repositories

import models.domain.genomics.GenotypeData

import java.util.UUID
import scala.concurrent.Future

trait GenotypeDataRepository {
  def findById(id: Int): Future[Option[GenotypeData]]
  def findByAtUri(atUri: String): Future[Option[GenotypeData]]
  def findBySampleGuid(sampleGuid: UUID): Future[Seq[GenotypeData]]
  def findByProvider(provider: String): Future[Seq[GenotypeData]]
  def create(genotypeData: GenotypeData): Future[GenotypeData]
  def upsertByAtUri(genotypeData: GenotypeData): Future[GenotypeData]
  def update(genotypeData: GenotypeData): Future[Boolean]
  def softDelete(id: Int): Future[Boolean]
  def findBySourceFileHash(hash: String): Future[Option[GenotypeData]]
}
