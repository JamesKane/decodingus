package utils

import models.domain.genomics.VariantV2
import play.api.libs.json.{JsObject, JsValue}

object VariantViewUtils {
  def refGenomes(v: VariantV2): Seq[String] = {
    v.coordinates.asOpt[Map[String, JsObject]].map(_.keys.toSeq.sorted).getOrElse(Seq.empty)
  }

  def formatPosition(v: VariantV2, refGenome: String): String = {
    v.getCoordinates(refGenome).map { coords =>
      val contig = (coords \ "contig").asOpt[String].getOrElse("?")
      
      (coords \ "position").asOpt[Int].map(p => s"$contig:$p")
        .orElse {
          (coords \ "start").asOpt[Long].map { start =>
             val end = (coords \ "end").asOpt[Long].getOrElse(start)
             if (start == end) s"$contig:$start" else s"$contig:$start-$end"
          }
        }
        .getOrElse(s"$contig:?")
    }.getOrElse("-")
  }
  
  private def extractAlleles(coords: JsValue): (String, String) = {
    val ref = (coords \ "ref").asOpt[String]
    val alt = (coords \ "alt").asOpt[String]
    
    if (ref.isDefined) {
       (ref.get, alt.getOrElse("?"))
    } else {
       val rawMotif = (coords \ "repeatMotif").asOpt[String]
       val motif = rawMotif.filterNot(_ == "N/A")
       val refRepeats = (coords \ "referenceRepeats").asOpt[Int]
       
       if (motif.isDefined && refRepeats.isDefined) {
         (s"${motif.get} x ${refRepeats.get}", "?")
       } else if (refRepeats.isDefined) {
         (s"(repeats: ${refRepeats.get})", "?")
       } else {
         ("?", "?")
       }
    }
  }

  def formatAlleles(v: VariantV2, refGenome: String): String = {
    val (ref, alt) = formatAllelesTuple(v, refGenome)
    s"$ref→$alt"
  }
  
  def formatAllelesTuple(v: VariantV2, refGenome: String): (String, String) = {
      v.getCoordinates(refGenome).map { coords =>
        extractAlleles(coords)
    }.getOrElse(("?", "?"))
  }

  def primaryAlleles(v: VariantV2): (String, String) = {
    val coords = v.coordinates.asOpt[Map[String, JsObject]].getOrElse(Map.empty)
    val primary = coords.get("hs1").orElse(coords.get("GRCh38")).orElse(coords.headOption.map(_._2))
    primary.map(extractAlleles).getOrElse(("?", "?"))
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