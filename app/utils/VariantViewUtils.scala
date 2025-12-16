package utils

import models.domain.genomics.VariantV2
import play.api.libs.json.JsObject

object VariantViewUtils {
  def refGenomes(v: VariantV2): Seq[String] = {
    v.coordinates.asOpt[Map[String, JsObject]].map(_.keys.toSeq.sorted).getOrElse(Seq.empty)
  }

  def formatPosition(v: VariantV2, refGenome: String): String = {
    v.getCoordinates(refGenome).map { coords =>
      val contig = (coords \ "contig").asOpt[String].getOrElse("?")
      val pos = (coords \ "position").asOpt[Int].getOrElse(0)
      s"$contig:$pos"
    }.getOrElse("-")
  }

  def formatAlleles(v: VariantV2, refGenome: String): String = {
    v.getCoordinates(refGenome).map { coords =>
      val ref = (coords \ "ref").asOpt[String].getOrElse("?")
      val alt = (coords \ "alt").asOpt[String].getOrElse("?")
      s"$ref→$alt"
    }.getOrElse("-")
  }
  
  def formatAllelesTuple(v: VariantV2, refGenome: String): (String, String) = {
      v.getCoordinates(refGenome).map { coords =>
      val ref = (coords \ "ref").asOpt[String].getOrElse("?")
      val alt = (coords \ "alt").asOpt[String].getOrElse("?")
      (ref, alt)
    }.getOrElse(("?", "?"))
  }

  def primaryAlleles(v: VariantV2): (String, String) = {
    val coords = v.coordinates.asOpt[Map[String, JsObject]].getOrElse(Map.empty)
    val primary = coords.get("hs1").orElse(coords.get("GRCh38")).orElse(coords.headOption.map(_._2))
    primary.map { c =>
      val ref = (c \ "ref").asOpt[String].getOrElse("?")
      val alt = (c \ "alt").asOpt[String].getOrElse("?")
      (ref, alt)
    }.getOrElse(("?", "?"))
  }

  def buildBadgeClass(refGenome: String): String = {
    refGenome match {
      case "GRCh37" => "bg-warning text-dark"
      case "GRCh38" => "bg-info"
      case "hs1" => "bg-success"
      case _ => "bg-secondary"
    }
  }
  
  def shortRefGenome(refGenome: String): String = {
    refGenome match {
        case "GRCh37" => "GRCh37"
        case "GRCh38" => "GRCh38"
        case "hs1" => "hs1"
        case other => other
    }
  }
}
