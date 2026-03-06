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

  val ValidAncestorVisibility: Set[String] = Set("NONE", "CENTURY_ONLY", "REGION_ONLY", "COUNTRY_ONLY", "SURNAME_ONLY", "FULL")
  val ValidStrVisibility: Set[String] = Set("NONE", "DISTANCE_CALCULATION_ONLY", "MODAL_COMPARISON_ONLY", "FULL_TO_MEMBERS", "FULL_PUBLIC")

  private val ancestorRank: Map[String, Int] = Map(
    "NONE" -> 0, "CENTURY_ONLY" -> 1, "REGION_ONLY" -> 2,
    "COUNTRY_ONLY" -> 3, "SURNAME_ONLY" -> 4, "FULL" -> 5
  )
  private val strRank: Map[String, Int] = Map(
    "NONE" -> 0, "DISTANCE_CALCULATION_ONLY" -> 1, "MODAL_COMPARISON_ONLY" -> 2,
    "FULL_TO_MEMBERS" -> 3, "FULL_PUBLIC" -> 4
  )

  def moreRestrictiveAncestor(a: String, b: String): String =
    if (ancestorRank.getOrElse(a, 0) <= ancestorRank.getOrElse(b, 0)) a else b

  def moreRestrictiveStr(a: String, b: String): String =
    if (strRank.getOrElse(a, 0) <= strRank.getOrElse(b, 0)) a else b
}

case class EffectiveVisibility(
                                showInMemberList: Boolean,
                                showInTree: Boolean,
                                shareTerminalHaplogroup: Boolean,
                                shareFullLineagePath: Boolean,
                                sharePrivateVariants: Boolean,
                                ancestorVisibility: String,
                                strVisibility: String,
                                allowDirectContact: Boolean,
                                showDisplayName: Boolean
                              )

object EffectiveVisibility {
  implicit val format: OFormat[EffectiveVisibility] = Json.format[EffectiveVisibility]

  def compute(project: GroupProject, member: MemberVisibility): EffectiveVisibility = {
    val projectSnpAllowsFullPath = project.snpPolicy == "FULL_PATH" || project.snpPolicy == "WITH_PRIVATE_VARIANTS"
    val projectSnpAllowsPrivate = project.snpPolicy == "WITH_PRIVATE_VARIANTS"

    val projectStrLevel = project.strPolicy match {
      case "HIDDEN" => "NONE"
      case "DISTANCE_ONLY" => "DISTANCE_CALCULATION_ONLY"
      case "MODAL_COMPARISON" => "MODAL_COMPARISON_ONLY"
      case "MEMBERS_ONLY_RAW" => "FULL_TO_MEMBERS"
      case "PUBLIC_RAW" => "FULL_PUBLIC"
      case _ => "NONE"
    }

    val projectAncestorLevel = "FULL" // project doesn't restrict ancestor granularity directly; member controls

    EffectiveVisibility(
      showInMemberList = member.showInMemberList && project.memberListVisibility != "HIDDEN",
      showInTree = member.showInTree && project.publicTreeView || member.showInTree,
      shareTerminalHaplogroup = member.shareTerminalHaplogroup && project.snpPolicy != "HIDDEN",
      shareFullLineagePath = member.shareFullLineagePath && projectSnpAllowsFullPath,
      sharePrivateVariants = member.sharePrivateVariants && projectSnpAllowsPrivate,
      ancestorVisibility = MemberVisibility.moreRestrictiveAncestor(member.ancestorVisibility, projectAncestorLevel),
      strVisibility = MemberVisibility.moreRestrictiveStr(member.strVisibility, projectStrLevel),
      allowDirectContact = member.allowDirectContact,
      showDisplayName = member.showDisplayName
    )
  }
}

case class AncestorData(
                          name: Option[String] = None,
                          surname: Option[String] = None,
                          birthYear: Option[Int] = None,
                          birthCentury: Option[String] = None,
                          birthDecade: Option[String] = None,
                          birthCountry: Option[String] = None,
                          birthRegion: Option[String] = None,
                          birthPlace: Option[String] = None,
                          additionalInfo: Option[String] = None
                        )

object AncestorData {
  implicit val format: OFormat[AncestorData] = Json.format[AncestorData]

  def filter(data: AncestorData, level: String): AncestorData = level match {
    case "NONE" => AncestorData()
    case "CENTURY_ONLY" => AncestorData(birthCentury = data.birthCentury)
    case "REGION_ONLY" => AncestorData(birthCountry = data.birthCountry, birthRegion = data.birthRegion)
    case "COUNTRY_ONLY" => AncestorData(birthCountry = data.birthCountry)
    case "SURNAME_ONLY" => AncestorData(surname = data.surname, birthCentury = data.birthCentury)
    case "FULL" => data
    case _ => AncestorData()
  }
}

case class FilteredMemberView(
                                memberId: Int,
                                kitId: Option[String],
                                displayName: Option[String],
                                role: String,
                                contributionLevel: Option[String],
                                terminalHaplogroup: Option[String],
                                lineagePath: Option[Seq[String]],
                                privateVariantCount: Option[Int],
                                ancestor: AncestorData,
                                strVisibility: String,
                                allowDirectContact: Boolean,
                                subgroupIds: List[String],
                                joinedAt: Option[LocalDateTime]
                              )

object FilteredMemberView {
  implicit val format: OFormat[FilteredMemberView] = Json.format[FilteredMemberView]
}
