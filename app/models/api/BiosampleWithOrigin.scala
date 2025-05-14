package models.api

case class BiosampleWithOrigin(sampleName: String, enaAccession: String, yDnaHaplogroup: String, mtDnaHaplogroup: String, latitude: Option[Double], longitude: Option[Double]) {
  def formattedOrigin: String = (latitude, longitude) match {
    case (Some(lat), Some(lon)) =>
      val latDir = if (lat >= 0) "N" else "S"
      val lonDir = if (lon >= 0) "E" else "W"
      f"${math.abs(lat)}%.2f°$latDir, ${math.abs(lon)}%.2f°$lonDir"
    case _ =>
      "Origin Not Available"
  }
}

