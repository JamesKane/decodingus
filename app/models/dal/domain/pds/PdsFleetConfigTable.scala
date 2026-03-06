package models.dal.domain.pds

import models.dal.MyPostgresProfile.api.*
import models.domain.pds.PdsFleetConfig

import java.time.LocalDateTime

class PdsFleetConfigTable(tag: Tag) extends Table[PdsFleetConfig](tag, "pds_fleet_config") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def configKey = column[String]("config_key")
  def configValue = column[String]("config_value")
  def description = column[Option[String]]("description")
  def updatedBy = column[Option[String]]("updated_by")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * = (id.?, configKey, configValue, description, updatedBy, updatedAt).mapTo[PdsFleetConfig]

  def uniqueKey = index("idx_pds_fleet_config_key_unique", configKey, unique = true)
}
