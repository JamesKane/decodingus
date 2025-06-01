package services

sealed trait BiosampleServiceException extends RuntimeException {
  def message: String
  override def getMessage: String = message
}

case class DuplicateAccessionException(accession: String) extends BiosampleServiceException {
  override val message = s"A biosample with accession $accession already exists"
}

case class DuplicateParticipantException(message: String) extends BiosampleServiceException

case class InvalidCoordinatesException(latitude: Double, longitude: Double) extends BiosampleServiceException {
  override val message = s"Invalid coordinates: latitude=$latitude, longitude=$longitude"
}

case class SequenceDataValidationException(details: String) extends BiosampleServiceException {
  override val message = s"Invalid sequence data: $details"
}

case class PublicationLinkageException(details: String) extends BiosampleServiceException {
  override val message = s"Failed to link publication: $details"
}

