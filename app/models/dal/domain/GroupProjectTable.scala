package models.dal.domain

import models.dal.MyPostgresProfile.api.*
import models.domain.GroupProject
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime
import java.util.UUID

class GroupProjectTable(tag: Tag) extends Table[GroupProject](tag, "group_project") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def projectGuid = column[UUID]("project_guid", O.Unique)
  def projectName = column[String]("project_name")
  def projectType = column[String]("project_type")
  def targetHaplogroup = column[Option[String]]("target_haplogroup")
  def targetLineage = column[Option[String]]("target_lineage")
  def description = column[Option[String]]("description")
  def backgroundInfo = column[Option[String]]("background_info")
  def joinPolicy = column[String]("join_policy")
  def haplogroupRequirement = column[Option[String]]("haplogroup_requirement")
  def memberListVisibility = column[String]("member_list_visibility")
  def strPolicy = column[String]("str_policy")
  def snpPolicy = column[String]("snp_policy")
  def publicTreeView = column[Boolean]("public_tree_view")
  def successionPolicy = column[Option[String]]("succession_policy")
  def ownerDid = column[String]("owner_did")
  def atUri = column[Option[String]]("at_uri")
  def atCid = column[Option[String]]("at_cid")
  def deleted = column[Boolean]("deleted")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  override def * : ProvenShape[GroupProject] = (
    id.?,
    projectGuid,
    projectName,
    projectType,
    targetHaplogroup,
    targetLineage,
    description,
    backgroundInfo,
    joinPolicy,
    haplogroupRequirement,
    memberListVisibility,
    strPolicy,
    snpPolicy,
    publicTreeView,
    successionPolicy,
    ownerDid,
    atUri,
    atCid,
    deleted,
    createdAt,
    updatedAt
  ).mapTo[GroupProject]
}
