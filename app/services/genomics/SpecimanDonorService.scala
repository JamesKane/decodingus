package services.genomics

import jakarta.inject.{Inject, Singleton}
import models.api.genomics.{MergeConflict, MergeStrategy, SpecimenDonorMergeRequest, SpecimenDonorMergeResult}
import models.domain.genomics.SpecimenDonor
import play.api.Logging
import repositories.SpecimenDonorRepository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service interface for managing and merging specimen donor data.
 *
 * This trait defines the contract for merging multiple donor records into a
 * single unified donor record as per the provided merge request details.
 */
trait SpecimenDonorService {
  /**
   * Merges multiple donor records into a single unified donor record based on the specified merge strategy.
   *
   * @param request the merge request containing the target donor ID, a list of source donor IDs to be merged,
   *                and the merge strategy to resolve conflicts or handle the merging process.
   * @return a future containing the result of the merge operation, which includes the ID of the merged donor,
   *         the count of updated biosamples, a list of removed donor IDs, and any merge conflicts encountered.
   */
  def mergeDonors(request: SpecimenDonorMergeRequest): Future[SpecimenDonorMergeResult]
}

@Singleton
class SpecimenDonorServiceImpl @Inject()(donorRepo: SpecimenDonorRepository)
                                        (implicit ec: ExecutionContext) extends SpecimenDonorService with Logging {

  /**
   * Merges a target donor with multiple source donors based on the details specified in the request.
   * The operation involves transferring biosamples from source donors to the target donor, deleting the source donors,
   * and resolving potential conflicts according to a specified merge strategy.
   *
   * @param request the merge request containing the target donor ID, a list of source donor IDs to merge,
   *                and the merge strategy to be applied
   * @return a future containing the result of the merge operation, which includes the target donor ID,
   *         count of updated biosamples, list of removed donor IDs, and any conflicts encountered during the merge
   */
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

  /**
   * Processes the merging of a target donor with multiple source donors based on the provided merge request.
   * This involves updating the target donor with merged data, transferring biosamples from the source donors
   * to the target donor, deleting the source donors, and identifying any merge conflicts.
   *
   * @param targetDonor  the primary donor to which data and biosamples from the source donors will be merged
   * @param sourceDonors a sequence of donors whose data and biosamples are to be merged into the target donor
   * @param request      the merge request containing the target donor ID, a list of source donor IDs, and the strategy used for merging
   * @return a future containing the result of the merge operation, which includes details about the merged donor ID,
   *         number of updated biosamples, removed donor IDs, and any conflicts encountered during the merge
   */
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

  /**
   * Merges donor data from a sequence of source donors into the target donor according to the specified merge strategy.
   *
   * @param target   the primary donor object to which data will be merged
   * @param sources  a sequence of source donors whose data will be used in the merge process
   * @param strategy the merge strategy determining how conflicts between target and source donors are resolved
   *                 (PreferTarget, PreferSource, or MostComplete)
   * @return a new SpecimenDonor instance representing the result of merging the target and source donors
   */
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
            atUri = source.atUri.orElse(acc.atUri),
            dateRangeStart = source.dateRangeStart.orElse(acc.dateRangeStart),
            dateRangeEnd = source.dateRangeEnd.orElse(acc.dateRangeEnd)
          )
        }
    }
  }

  /**
   * Identifies and collects merge conflicts between a target donor and multiple source donors
   * in the context of a merge operation. A conflict arises when the values of corresponding
   * fields in the target and source donors differ.
   *
   * @param target  the primary donor whose values are compared against the source donors
   * @param sources a list of source donors whose values are compared against the target donor
   * @param result  the merged donor, used to determine the resolved value for conflicting fields
   * @return a list of `MergeConflict` instances representing the fields where conflicts were detected,
   *         including details of the conflicting values and the chosen resolution
   */
  private def collectMergeConflicts(target: SpecimenDonor, sources: List[SpecimenDonor], result: SpecimenDonor): List[MergeConflict] = {
    logger.info(s"Starting merge conflict detection for target donor ${target.id} with ${sources.length} source donors")

    // Helper function to compare values and create conflict if they differ
    def checkField[T](fieldName: String, targetValue: Option[T], sourceValue: Option[T], resultValue: Option[T]): Option[MergeConflict] = {
      (targetValue, sourceValue) match {
        case (Some(tv), Some(sv)) if tv != sv =>
          val conflict = MergeConflict(
            field = fieldName,
            targetValue = tv.toString,
            sourceValue = sv.toString,
            resolution = resultValue.map(_.toString).getOrElse("No value")
          )
          logger.debug(s"Found conflict in field '$fieldName': target='$tv', source='$sv', resolved to='${resultValue.getOrElse("No value")}'")
          Some(conflict)
        case _ => None
      }
    }

    // For multiple sources, we'll compare against the first different value we find
    val conflictingSources = sources.filter(_ != target)
    if (conflictingSources.isEmpty) {
      logger.info("No conflicting sources found - all sources match target")
      return Nil
    }

    logger.debug(s"Found ${conflictingSources.length} conflicting source donors")

    val conflicts = conflictingSources.flatMap { source =>
      logger.debug(s"Checking conflicts between target donor ${target.id} and source donor ${source.id}")

      val fieldConflicts = List(
        checkField("donorType", Option(target.donorType), Option(source.donorType), Option(result.donorType)),
        checkField("sex", target.sex, source.sex, result.sex),
        checkField("geocoord", target.geocoord, source.geocoord, result.geocoord),
        checkField("pgpParticipantId", target.pgpParticipantId, source.pgpParticipantId, result.pgpParticipantId),
        checkField("atUri", target.atUri, source.atUri, result.atUri),
        checkField("dateRangeStart", target.dateRangeStart, source.dateRangeStart, result.dateRangeStart),
        checkField("dateRangeEnd", target.dateRangeEnd, source.dateRangeEnd, result.dateRangeEnd)
      ).flatten

      if (fieldConflicts.isEmpty) {
        logger.debug(s"No field conflicts found between target donor ${target.id} and source donor ${source.id}")
      }

      fieldConflicts
    }.distinct

    logger.info(s"Completed merge conflict detection: found ${conflicts.length} unique conflicts")
    if (conflicts.nonEmpty) {
      logger.debug(s"Conflict summary: ${conflicts.map(c => s"${c.field}: ${c.targetValue} vs ${c.sourceValue}").mkString(", ")}")
    }

    conflicts
  }
}