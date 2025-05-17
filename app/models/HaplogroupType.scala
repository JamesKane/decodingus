package models

enum HaplogroupType {
  case Y, MT

  override def toString: String = this match {
    case Y => "Y"
    case MT => "MT"
  }
}

object HaplogroupType {
  def fromString(str: String): Option[HaplogroupType] = str.toUpperCase match {
    case "Y" => Some(Y)
    case "MT" => Some(MT)
    case _ => None
  }
}