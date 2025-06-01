package utils

/**
 * Trait for handling Decentralized Identifier (DID) conversions.
 *
 * This trait provides methods for converting a source system and accession identifier
 * to a DID format and extracting components from a given DID string.
 */
trait DIDResolver {
  /**
   * Converts a given source system and accession identifier into a Decentralized Identifier (DID) format.
   *
   * @param sourceSystem The source system identifier (e.g., "pgp", "evolbio").
   * @param accession    The accession identifier associated with the entity.
   * @return A formatted Decentralized Identifier (DID) string specific to the source system.
   */
  def toDID(sourceSystem: String, accession: String): String = sourceSystem match {
    case "pgp" => s"did:pgp:${accession.toLowerCase}"
    case "evolbio" => s"did:evolbio:${accession}"
    case _ => s"did:biosample:${accession}"
  }

  /**
   * Extracts the source system and accession identifier from a given Decentralized Identifier (DID) string.
   *
   * The input DID string is expected to follow the format `did:<sourceSystem>:<accession>`.
   * If the input string matches the pattern, the method returns an `Option` containing a tuple
   * with the extracted `sourceSystem` and `accession`. Otherwise, it returns `None`.
   *
   * @param did The Decentralized Identifier (DID) string to be parsed.
   * @return An `Option` containing a tuple `(sourceSystem, accession)` if parsing succeeds, or `None` if the input
   *         string does not match the expected DID format.
   */
  def fromDID(did: String): Option[(String, String)] = {
    val DIDPattern = "^did:([^:]+):(.+)$".r
    did match {
      case DIDPattern(system, acc) => Some((system, acc))
      case _ => None
    }
  }
}
