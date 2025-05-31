package utils

trait DIDResolver {
  def toDID(sourceSystem: String, accession: String): String = sourceSystem match {
    case "pgp" => s"did:pgp:${accession.toLowerCase}"
    case "evolbio" => s"did:evolbio:${accession}"
    case _ => s"did:biosample:${accession}"
  }

  def fromDID(did: String): Option[(String, String)] = {
    val DIDPattern = "^did:([^:]+):(.+)$".r
    did match {
      case DIDPattern(system, acc) => Some((system, acc))
      case _ => None
    }
  }
}
