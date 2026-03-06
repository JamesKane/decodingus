package models.domain

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime
import java.util.UUID

case class GroupProject(
                         id: Option[Int] = None,
                         projectGuid: UUID = UUID.randomUUID(),
                         projectName: String,
                         projectType: String,
                         targetHaplogroup: Option[String] = None,
                         targetLineage: Option[String] = None,
                         description: Option[String] = None,
                         backgroundInfo: Option[String] = None,
                         joinPolicy: String = "APPROVAL_REQUIRED",
                         haplogroupRequirement: Option[String] = None,
                         memberListVisibility: String = "MEMBERS_ONLY",
                         strPolicy: String = "DISTANCE_ONLY",
                         snpPolicy: String = "TERMINAL_ONLY",
                         publicTreeView: Boolean = false,
                         successionPolicy: Option[String] = Some("CO_ADMIN_INHERITS"),
                         ownerDid: String,
                         atUri: Option[String] = None,
                         atCid: Option[String] = None,
                         deleted: Boolean = false,
                         createdAt: LocalDateTime = LocalDateTime.now(),
                         updatedAt: LocalDateTime = LocalDateTime.now()
                       )

object GroupProject {
  implicit val format: OFormat[GroupProject] = Json.format[GroupProject]

  val ValidProjectTypes: Set[String] = Set("HAPLOGROUP", "SURNAME", "GEOGRAPHIC", "ETHNIC", "RESEARCH", "CUSTOM")
  val ValidJoinPolicies: Set[String] = Set("OPEN", "APPROVAL_REQUIRED", "INVITE_ONLY", "HAPLOGROUP_VERIFIED")
  val ValidLineages: Set[String] = Set("Y_DNA", "MT_DNA", "BOTH")
}

case class GroupProjectMember(
                               id: Option[Int] = None,
                               groupProjectId: Int,
                               citizenDid: String,
                               biosampleAtUri: Option[String] = None,
                               role: String = "MEMBER",
                               status: String = "PENDING_APPROVAL",
                               displayName: Option[String] = None,
                               kitId: Option[String] = None,
                               visibility: MemberVisibility = MemberVisibility(),
                               subgroupIds: List[String] = List.empty,
                               contributionLevel: Option[String] = Some("OBSERVER"),
                               joinedAt: Option[LocalDateTime] = None,
                               atUri: Option[String] = None,
                               atCid: Option[String] = None,
                               createdAt: LocalDateTime = LocalDateTime.now(),
                               updatedAt: LocalDateTime = LocalDateTime.now()
                             )

object GroupProjectMember {
  implicit val format: OFormat[GroupProjectMember] = Json.format[GroupProjectMember]

  val ValidRoles: Set[String] = Set("ADMIN", "CO_ADMIN", "MODERATOR", "CURATOR", "MEMBER")
  val ValidStatuses: Set[String] = Set("PENDING_APPROVAL", "ACTIVE", "SUSPENDED", "LEFT", "REMOVED")
}

case class MemberVisibility(
                              showInMemberList: Boolean = true,
                              showInTree: Boolean = true,
                              shareTerminalHaplogroup: Boolean = true,
                              shareFullLineagePath: Boolean = false,
                              sharePrivateVariants: Boolean = false,
                              ancestorVisibility: String = "NONE",
                              strVisibility: String = "NONE",
                              allowDirectContact: Boolean = false,
                              showDisplayName: Boolean = true
                            )

object MemberVisibility {
  implicit val format: OFormat[MemberVisibility] = Json.format[MemberVisibility]
}
