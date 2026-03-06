package models.domain.discovery

import play.api.libs.json.*

enum BiosampleSourceType {
  case Citizen, External

  override def toString: String = this match {
    case Citizen => "CITIZEN"
    case External => "EXTERNAL"
  }
}

object BiosampleSourceType {
  def fromString(str: String): Option[BiosampleSourceType] = str.toUpperCase match {
    case "CITIZEN" => Some(Citizen)
    case "EXTERNAL" => Some(External)
    case _ => None
  }

  implicit val format: Format[BiosampleSourceType] = Format(
    Reads.StringReads.flatMap { str =>
      fromString(str) match {
        case Some(v) => Reads.pure(v)
        case None => Reads.failed(s"Invalid BiosampleSourceType: $str")
      }
    },
    Writes.StringWrites.contramap(_.toString)
  )
}

enum PrivateVariantStatus {
  case Active, Promoted, Invalidated

  override def toString: String = this match {
    case Active => "ACTIVE"
    case Promoted => "PROMOTED"
    case Invalidated => "INVALIDATED"
  }
}

object PrivateVariantStatus {
  def fromString(str: String): Option[PrivateVariantStatus] = str.toUpperCase match {
    case "ACTIVE" => Some(Active)
    case "PROMOTED" => Some(Promoted)
    case "INVALIDATED" => Some(Invalidated)
    case _ => None
  }

  implicit val format: Format[PrivateVariantStatus] = Format(
    Reads.StringReads.flatMap { str =>
      fromString(str) match {
        case Some(v) => Reads.pure(v)
        case None => Reads.failed(s"Invalid PrivateVariantStatus: $str")
      }
    },
    Writes.StringWrites.contramap(_.toString)
  )
}

enum ProposedBranchStatus {
  case Pending, ReadyForReview, UnderReview, Accepted, Promoted, Rejected, Split

  override def toString: String = this match {
    case Pending => "PENDING"
    case ReadyForReview => "READY_FOR_REVIEW"
    case UnderReview => "UNDER_REVIEW"
    case Accepted => "ACCEPTED"
    case Promoted => "PROMOTED"
    case Rejected => "REJECTED"
    case Split => "SPLIT"
  }
}

object ProposedBranchStatus {
  def fromString(str: String): Option[ProposedBranchStatus] = str.toUpperCase match {
    case "PENDING" => Some(Pending)
    case "READY_FOR_REVIEW" => Some(ReadyForReview)
    case "UNDER_REVIEW" => Some(UnderReview)
    case "ACCEPTED" => Some(Accepted)
    case "PROMOTED" => Some(Promoted)
    case "REJECTED" => Some(Rejected)
    case "SPLIT" => Some(Split)
    case _ => None
  }

  implicit val format: Format[ProposedBranchStatus] = Format(
    Reads.StringReads.flatMap { str =>
      fromString(str) match {
        case Some(v) => Reads.pure(v)
        case None => Reads.failed(s"Invalid ProposedBranchStatus: $str")
      }
    },
    Writes.StringWrites.contramap(_.toString)
  )
}

enum CuratorActionType {
  case Review, Accept, Reject, Modify, Split, Merge, Create, Delete, Reassign, NameVariant

  override def toString: String = this match {
    case Review => "REVIEW"
    case Accept => "ACCEPT"
    case Reject => "REJECT"
    case Modify => "MODIFY"
    case Split => "SPLIT"
    case Merge => "MERGE"
    case Create => "CREATE"
    case Delete => "DELETE"
    case Reassign => "REASSIGN"
    case NameVariant => "NAME_VARIANT"
  }
}

object CuratorActionType {
  def fromString(str: String): Option[CuratorActionType] = str.toUpperCase match {
    case "REVIEW" => Some(Review)
    case "ACCEPT" => Some(Accept)
    case "REJECT" => Some(Reject)
    case "MODIFY" => Some(Modify)
    case "SPLIT" => Some(Split)
    case "MERGE" => Some(Merge)
    case "CREATE" => Some(Create)
    case "DELETE" => Some(Delete)
    case "REASSIGN" => Some(Reassign)
    case "NAME_VARIANT" => Some(NameVariant)
    case _ => None
  }

  implicit val format: Format[CuratorActionType] = Format(
    Reads.StringReads.flatMap { str =>
      fromString(str) match {
        case Some(v) => Reads.pure(v)
        case None => Reads.failed(s"Invalid CuratorActionType: $str")
      }
    },
    Writes.StringWrites.contramap(_.toString)
  )
}

enum CuratorTargetType {
  case ProposedBranch, Haplogroup, HaplogroupRelationship, Variant, Biosample

  override def toString: String = this match {
    case ProposedBranch => "PROPOSED_BRANCH"
    case Haplogroup => "HAPLOGROUP"
    case HaplogroupRelationship => "HAPLOGROUP_RELATIONSHIP"
    case Variant => "VARIANT"
    case Biosample => "BIOSAMPLE"
  }
}

object CuratorTargetType {
  def fromString(str: String): Option[CuratorTargetType] = str.toUpperCase match {
    case "PROPOSED_BRANCH" => Some(ProposedBranch)
    case "HAPLOGROUP" => Some(Haplogroup)
    case "HAPLOGROUP_RELATIONSHIP" => Some(HaplogroupRelationship)
    case "VARIANT" => Some(Variant)
    case "BIOSAMPLE" => Some(Biosample)
    case _ => None
  }

  implicit val format: Format[CuratorTargetType] = Format(
    Reads.StringReads.flatMap { str =>
      fromString(str) match {
        case Some(v) => Reads.pure(v)
        case None => Reads.failed(s"Invalid CuratorTargetType: $str")
      }
    },
    Writes.StringWrites.contramap(_.toString)
  )
}
