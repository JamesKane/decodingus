package models

case class EnaStudy(
                     id: Option[Int] = None,
                     accession: String,
                     title: String,
                     centerName: String,
                     studyName: String,
                     details: String
                   )
