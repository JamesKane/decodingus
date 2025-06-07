package services

import jakarta.inject.{Inject, Singleton}
import models.api.{BiosampleUpdate, BiosampleView}
import models.domain.genomics.{Biosample, BiosampleType, SpecimenDonor}
import models.domain.publications.BiosampleOriginalHaplogroup
import repositories.{BiosampleOriginalHaplogroupRepository, BiosampleRepository, PublicationBiosampleRepository, SpecimenDonorRepository}
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
                                        biosampleOriginalHaplogroupRepository: BiosampleOriginalHaplogroupRepository,
                                        specimenDonorRepository: SpecimenDonorRepository
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
        case Some((biosample, specimenDonor)) =>
          val updatedBiosample = createUpdatedBiosample(biosample, update)

          for {
            _ <- updateHaplogroupsIfNeeded(id, update)
            _ <- updateSpecimenDonorIfNeeded(biosample.specimenDonorId, specimenDonor, update)
            updateResult <- biosampleRepository.update(updatedBiosample)
          } yield {
            if (updateResult) Right(BiosampleView.fromDomain(updatedBiosample, specimenDonor))
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
    existing.copy(
      alias = update.alias.orElse(existing.alias),
      locked = update.locked.getOrElse(existing.locked)
    )
  }

  private def updateSpecimenDonorIfNeeded(
                                           specimenDonorId: Option[Int],
                                           existingDonor: Option[SpecimenDonor],
                                           update: BiosampleUpdate
                                         ): Future[Unit] = {
    if (hasSpecimenDonorUpdates(update)) {
      (specimenDonorId, existingDonor) match {
        case (Some(id), Some(donor)) =>
          // Update existing donor
          val updatedDonor = donor.copy(
            sex = update.sex.orElse(donor.sex),
            geocoord = update.geoCoord.map(GeometryUtils.geoCoordToPoint).orElse(donor.geocoord),
            dateRangeStart = update.dateRangeStart.orElse(donor.dateRangeStart),
            dateRangeEnd = update.dateRangeEnd.orElse(donor.dateRangeEnd),
            donorType = if (update.dateRangeStart.isDefined || update.dateRangeEnd.isDefined) {
              BiosampleType.Ancient
            } else {
              donor.donorType
            }
          )
          specimenDonorRepository.update(updatedDonor).map(_ => ())

        case (None, None) if shouldCreateNewDonor(update) =>
          // Create new donor if we have enough data
          val newDonor = SpecimenDonor(
            id = None,
            donorIdentifier = s"DONOR_${java.util.UUID.randomUUID().toString}",
            originBiobank = "Unknown",
            donorType = if (update.dateRangeStart.isDefined || update.dateRangeEnd.isDefined) {
              BiosampleType.Ancient
            } else {
              BiosampleType.Standard
            },
            sex = update.sex,
            geocoord = update.geoCoord.map(GeometryUtils.geoCoordToPoint),
            dateRangeStart = update.dateRangeStart,
            dateRangeEnd = update.dateRangeEnd
          )
          specimenDonorRepository.create(newDonor).map(_ => ())

        case _ =>
          Future.successful(()) // No updates needed
      }
    } else {
      Future.successful(())
    }
  }

  private def hasSpecimenDonorUpdates(update: BiosampleUpdate): Boolean = {
    update.sex.isDefined ||
      update.geoCoord.isDefined ||
      update.dateRangeStart.isDefined ||
      update.dateRangeEnd.isDefined
  }

  private def shouldCreateNewDonor(update: BiosampleUpdate): Boolean = {
    // Create new donor only if we have at least two pieces of identifying information
    val identifyingFields = Seq(
      update.sex.isDefined,
      update.geoCoord.isDefined,
      update.dateRangeStart.isDefined || update.dateRangeEnd.isDefined
    )
    identifyingFields.count(identity) >= 2
  }

  private def updateHaplogroupsIfNeeded(biosampleId: Int, update: BiosampleUpdate): Future[Unit] = {
    // Existing haplogroup update logic remains unchanged
    if (update.yHaplogroup.isDefined || update.mtHaplogroup.isDefined) {
      publicationBiosampleRepository.findByBiosampleId(biosampleId).flatMap { pubBiosamples =>
        Future.sequence(pubBiosamples.map { pubBiosample =>
          biosampleOriginalHaplogroupRepository
            .findByBiosampleAndPublication(biosampleId, pubBiosample.publicationId)
            .flatMap {
              case Some(existing) =>
                biosampleOriginalHaplogroupRepository.update(existing.copy(
                  originalYHaplogroup = update.yHaplogroup.orElse(existing.originalYHaplogroup),
                  originalMtHaplogroup = update.mtHaplogroup.orElse(existing.originalMtHaplogroup)
                ))
              case None =>
                biosampleOriginalHaplogroupRepository.create(BiosampleOriginalHaplogroup(
                  id = None,
                  biosampleId = biosampleId,
                  publicationId = pubBiosample.publicationId,
                  originalYHaplogroup = update.yHaplogroup,
                  originalMtHaplogroup = update.mtHaplogroup,
                  notes = None
                ))
            }
        })
      }.map(_ => ())
    } else {
      Future.successful(())
    }
  }
}