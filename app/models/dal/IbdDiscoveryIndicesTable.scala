package models.dal

import models.IbdDiscoveryIndex
import models.dal.MyPostgresProfile.api.*

import java.time.ZonedDateTime
import java.util.UUID

class IbdDiscoveryIndicesTable(tag: Tag) extends Table[IbdDiscoveryIndex](tag, "ibd_discovery_index") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def sampleGuid1 = column[UUID]("sample_guid_1")

  def sampleGuid2 = column[UUID]("sample_guid_2")

  def pangenomeGraphId = column[Int]("pangenome_graph_id")

  def matchRegionType = column[String]("match_region_type")

  def totalSharedCmApprox = column[Option[Double]]("total_shared_cm_approx")

  def numSharedSegmentsApprox = column[Option[Int]]("num_shared_segments_approx")

  def isPubliclyDiscoverable = column[Boolean]("is_publicly_discoverable")

  def consensusStatus = column[String]("consensus_status")

  def lastConsensusUpdate = column[ZonedDateTime]("last_consensus_update")

  def validationServiceGuid = column[Option[UUID]]("validation_service_guid")

  def validationTimestamp = column[Option[ZonedDateTime]]("validation_timestamp")

  def indexedByService = column[Option[String]]("indexed_by_service")

  def indexedDate = column[ZonedDateTime]("indexed_date")

  def * = (
    id.?,
    sampleGuid1,
    sampleGuid2,
    pangenomeGraphId,
    matchRegionType,
    totalSharedCmApprox,
    numSharedSegmentsApprox,
    isPubliclyDiscoverable,
    consensusStatus,
    lastConsensusUpdate,
    validationServiceGuid,
    validationTimestamp,
    indexedByService,
    indexedDate
  ).mapTo[IbdDiscoveryIndex]
}