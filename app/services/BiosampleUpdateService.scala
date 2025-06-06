package services

import jakarta.inject.{Inject, Singleton}
import models.api.{BiosampleUpdate, BiosampleView}
import models.domain.genomics.{Biosample, BiosampleType}
import models.domain.publications.BiosampleOriginalHaplogroup
import repositories.{BiosampleOriginalHaplogroupRepository, BiosampleRepository, PublicationBiosampleRepository}
import utils.GeometryUtils

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service class responsible for updating biosamples and managing associated data.
 *
 * The `BiosampleUpdateService` provides methods for updating biosample records,
 * handling related haplogroup updates, and ensuring consistency in the repository layer.
 *
 * @constructor Creates an instance of `BiosampleUpdateService` with the necessary repositories and execution context.
 * @param biosampleRepository                   Repository for performing CRUD operations on biosamples.
 * @param publicationBiosampleRepository        Repository for managing the relationship between publications and biosamples.
 * @param biosampleOriginalHaplogroupRepository Repository for handling the original haplogroup assignments for biosamples.
 * @param ec                                    Execution context used for asynchronous operations.
 */
@Singleton
class BiosampleUpdateService @Inject()(
                                        biosampleRepository: BiosampleRepository,
                                        publicationBiosampleRepository: PublicationBiosampleRepository,
                                        biosampleOriginalHaplogroupRepository: BiosampleOriginalHaplogroupRepository
                                      )(implicit ec: ExecutionContext) {

  /**
   * Updates a biosample with the given modifications if valid updates are provided.
   * Handles updating optional fields of the biosample, and also ensures any
   * haplogroup-related updates are processed if necessary.
   *
   * @param id     The unique identifier of the biosample to be updated.
   * @param update The object containing the updated fields for the biosample.
   * @return A Future containing either a String message indicating an error
   *         (e.g., if the biosample is not found or update fails), or a BiosampleView
   *         representing the updated state of the biosample.
   */
  def updateBiosample(id: Int, update: BiosampleUpdate): Future[Either[String, BiosampleView]] = {
    if (!update.hasUpdates) {
      Future.successful(Left("No valid fields to update"))
    } else {
      biosampleRepository.findById(id).flatMap {
        case None => Future.successful(Left("Biosample not found"))
        case Some(existingBiosample) =>
          val updatedBiosample = createUpdatedBiosample(existingBiosample, update)

          for {
            _ <- updateHaplogroupsIfNeeded(id, update)
            updateResult <- biosampleRepository.update(updatedBiosample)
          } yield {
            if (updateResult) Right(BiosampleView.fromDomain(updatedBiosample))
            else Left("Failed to update biosample")
          }
      }
    }
  }

  /**
   * Creates an updated version of a Biosample by applying changes from a BiosampleUpdate object.
   * Handles updates to optional fields and also updates the sample type if certain conditions are met.
   *
   * @param existing The existing Biosample object that serves as the baseline for updates.
   * @param update   The BiosampleUpdate object containing the modifications to apply to the existing Biosample.
   * @return A new Biosample object reflecting the updates provided in the BiosampleUpdate.
   */
  private def createUpdatedBiosample(existing: Biosample, update: BiosampleUpdate): Biosample = {
    val newType = if (update.dateRangeStart.isDefined || update.dateRangeEnd.isDefined) {
      BiosampleType.Ancient
    } else {
      existing.sampleType
    }

    existing.copy(
      sex = update.sex.orElse(existing.sex),
      geocoord = update.geoCoord.map(GeometryUtils.geoCoordToPoint)
        .orElse(existing.geocoord),
      alias = update.alias.orElse(existing.alias),
      locked = update.locked.getOrElse(existing.locked),
      dateRangeStart = update.dateRangeStart.orElse(existing.dateRangeStart),
      dateRangeEnd = update.dateRangeEnd.orElse(existing.dateRangeEnd),
      sampleType = newType
    )
  }

  /**
   * Updates haplogroup information for a biosample if necessary, based on the provided updates.
   * If haplogroup-related updates are detected (e.g., Y-chromosomal or mitochondrial haplogroup),
   * this method ensures that the updates are applied to all related publications for the biosample.
   * Handles insertion or updates of haplogroups into the repository.
   *
   * @param biosampleId The unique identifier of the biosample being updated.
   * @param update      An object containing the potential updates for the biosample, including optional haplogroup fields.
   * @return A Future containing Unit, indicating the completion of the update process.
   */
  private def updateHaplogroupsIfNeeded(biosampleId: Int, update: BiosampleUpdate): Future[Unit] = {
    if (update.yHaplogroup.isDefined || update.mtHaplogroup.isDefined) {
      for {
        count <- publicationBiosampleRepository.countSamplesForPublication(biosampleId)
        _ <- if (count > 0) {
          biosampleOriginalHaplogroupRepository.findByBiosampleId(biosampleId).flatMap { existingHaplogroups =>
            val publicationIds = existingHaplogroups.map(_.publicationId)

            Future.sequence(publicationIds.map { pubId =>
              val existingHaplogroup = existingHaplogroups.find(_.publicationId == pubId)

              existingHaplogroup match {
                case Some(existing) =>
                  biosampleOriginalHaplogroupRepository.update(existing.copy(
                    originalYHaplogroup = update.yHaplogroup.orElse(existing.originalYHaplogroup),
                    originalMtHaplogroup = update.mtHaplogroup.orElse(existing.originalMtHaplogroup)
                  ))
                case None =>
                  biosampleOriginalHaplogroupRepository.create(BiosampleOriginalHaplogroup(
                    id = None,
                    biosampleId = biosampleId,
                    publicationId = pubId,
                    originalYHaplogroup = update.yHaplogroup,
                    originalMtHaplogroup = update.mtHaplogroup,
                    notes = None
                  ))
              }
            })
          }
        } else Future.successful(())
      } yield ()
    } else {
      Future.successful(())
    }
  }
}