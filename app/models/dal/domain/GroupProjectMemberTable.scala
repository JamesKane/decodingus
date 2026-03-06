package models.dal.domain

import models.dal.MyPostgresProfile.api.*
import models.domain.{GroupProjectMember, MemberVisibility}
import play.api.libs.json.{JsValue, Json}
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime

class GroupProjectMemberTable(tag: Tag) extends Table[GroupProjectMember](tag, "group_project_member") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def groupProjectId = column[Int]("group_project_id")
  def citizenDid = column[String]("citizen_did")
  def biosampleAtUri = column[Option[String]]("biosample_at_uri")
  def role = column[String]("role")
  def status = column[String]("status")
  def displayName = column[Option[String]]("display_name")
  def kitId = column[Option[String]]("kit_id")
  def visibility = column[JsValue]("visibility")
  def subgroupIds = column[List[String]]("subgroup_ids")
  def contributionLevel = column[Option[String]]("contribution_level")
  def joinedAt = column[Option[LocalDateTime]]("joined_at")
  def atUri = column[Option[String]]("at_uri")
  def atCid = column[Option[String]]("at_cid")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  private def visibilityToJson(v: MemberVisibility): JsValue = Json.toJson(v)
  private def jsonToVisibility(j: JsValue): MemberVisibility = j.asOpt[MemberVisibility].getOrElse(MemberVisibility())

  override def * : ProvenShape[GroupProjectMember] = (
    id.?,
    groupProjectId,
    citizenDid,
    biosampleAtUri,
    role,
    status,
    displayName,
    kitId,
    visibility,
    subgroupIds,
    contributionLevel,
    joinedAt,
    atUri,
    atCid,
    createdAt,
    updatedAt
  ).shaped.<>(
    { case (id, gpId, did, bio, role, status, dn, kit, vis, subs, cl, ja, aUri, aCid, ca, ua) =>
      GroupProjectMember(id, gpId, did, bio, role, status, dn, kit, jsonToVisibility(vis), subs, cl, ja, aUri, aCid, ca, ua)
    },
    { (m: GroupProjectMember) =>
      Some((m.id, m.groupProjectId, m.citizenDid, m.biosampleAtUri, m.role, m.status, m.displayName,
        m.kitId, visibilityToJson(m.visibility), m.subgroupIds, m.contributionLevel, m.joinedAt,
        m.atUri, m.atCid, m.createdAt, m.updatedAt))
    }
  )
}
