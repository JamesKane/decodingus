package services

import jakarta.inject.Inject
import models.Biosample
import repositories.BiosampleRepository

import scala.concurrent.Future

class BiosampleService @Inject()(biosampleRepository: BiosampleRepository) {
  def getBiosampleById(id: Int): Future[Option[Biosample]] = biosampleRepository.findById(id)
}
