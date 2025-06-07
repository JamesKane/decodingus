package services.genomics

import models.api.genomics.{MergeConflict, MergeStrategy, SpecimenDonorMergeRequest, SpecimenDonorMergeResult}
import models.domain.genomics.SpecimenDonor
import repositories.{BiosampleRepository, SpecimenDonorRepository}
import jakarta.inject.{Inject, Singleton}

import scala.concurrent.{ExecutionContext, Future}

trait SpecimenDonorService {
  def mergeDonors(request: SpecimenDonorMergeRequest): Future[SpecimenDonorMergeResult]
}

@Singleton
class SpecimenDonorServiceImpl @Inject()(
                                          donorRepo: SpecimenDonorRepository,
                                          biosampleRepo: BiosampleRepository
                                        )(implicit ec: ExecutionContext) extends SpecimenDonorService {

  def mergeDonors(request: SpecimenDonorMergeRequest): Future[SpecimenDonorMergeResult] = {
    for {
      // 1. Get all donors involved
      targetDonorOpt <- donorRepo.findById(request.targetId)
      sourceDonors <- Future.sequence(request.sourceIds.map(donorRepo.findById))

      // 2. Validate all donors exist
      result <- (targetDonorOpt, sourceDonors.flatten) match {
        case (Some(targetDonor), sources) if sources.length == request.sourceIds.length =>
          processMerge(targetDonor, sources, request)
        case _ =>
          Future.failed(new IllegalArgumentException("One or more donors not found"))
      }
    } yield result
  }

  private def processMerge(
                            targetDonor: SpecimenDonor,
                            sourceDonors: Seq[SpecimenDonor],
                            request: SpecimenDonorMergeRequest
                          ): Future[SpecimenDonorMergeResult] = {

    // Merge donor data according to strategy
    val mergedDonor = mergeDonorData(targetDonor, sourceDonors, request.mergeStrategy)

    for {
      // Update target donor with merged data
      _ <- donorRepo.update(mergedDonor)

      // Transfer all biosamples to target donor
      updatedCount <- donorRepo.transferBiosamples(request.sourceIds, request.targetId)

      // Delete source donors
      _ <- donorRepo.deleteMany(request.sourceIds)

    } yield SpecimenDonorMergeResult(
      mergedDonorId = request.targetId,
      updatedBiosamples = updatedCount,
      removedDonors = request.sourceIds,
      conflicts = collectMergeConflicts(targetDonor, sourceDonors.toList, mergedDonor)
    )
  }

  private def mergeDonorData(target: SpecimenDonor, sources: Seq[SpecimenDonor], strategy: MergeStrategy): SpecimenDonor = {
    strategy match {
      case MergeStrategy.PreferTarget => target
      case MergeStrategy.PreferSource => sources.head.copy(id = target.id)
      case MergeStrategy.MostComplete =>
        sources.foldLeft(target) { (acc, source) =>
          SpecimenDonor(
            id = acc.id,
            donorIdentifier = acc.donorIdentifier,
            originBiobank = acc.originBiobank,
            donorType = (source.donorType, acc.donorType) match {
              case (null, accType) => accType
              case (sourceType, _) => sourceType
            },
            sex = source.sex.orElse(acc.sex),
            geocoord = source.geocoord.orElse(acc.geocoord),
            pgpParticipantId = source.pgpParticipantId.orElse(acc.pgpParticipantId),
            citizenBiosampleDid = source.citizenBiosampleDid.orElse(acc.citizenBiosampleDid),
            dateRangeStart = source.dateRangeStart.orElse(acc.dateRangeStart),
            dateRangeEnd = source.dateRangeEnd.orElse(acc.dateRangeEnd)
          )
        }
    }
  }

  private def collectMergeConflicts(target: SpecimenDonor, sources: List[SpecimenDonor], result: SpecimenDonor): List[MergeConflict] = {
    // Implementation for tracking merge conflicts
    Nil // TODO: Implement conflict collection
  }
}