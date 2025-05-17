package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.*
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupRepository {
  def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]]

  def findHaplogroupsByDefiningVariant(variantId: String, haplogroupType: HaplogroupType): Future[Seq[Haplogroup]]

  def findVariants(query: String): Future[Seq[Variant]]

  def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]]

  def getHaplogroupVariants(haplogroupId: Int): Future[Seq[(Variant, GenbankContig)]]

  def getSubtreeRelationships(rootId: Int): Future[Seq[(Haplogroup, HaplogroupRelationship)]]

  def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]]

  def getCurrentValidRelationships: Future[Seq[(HaplogroupRelationship, Haplogroup)]]

  def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[Variant]]

  def getHaplogroupsByVariant(variantId: Int): Future[Seq[Haplogroup]]

  def addVariantToHaplogroup(haplogroupId: Int, variantId: Int): Future[Int]

  def removeVariantFromHaplogroup(haplogroupId: Int, variantId: Int): Future[Int]

  def getHaplogroupAtRevision(haplogroupId: Int, revisionId: Int): Future[Option[Haplogroup]]

  def getLatestRevision(haplogroupId: Int): Future[Option[Haplogroup]]

  def getRevisionHistory(haplogroupId: Int): Future[Seq[Haplogroup]]

  def createNewRevision(haplogroup: Haplogroup): Future[Int]

  def createRelationshipRevision(relationship: HaplogroupRelationship): Future[Int]

  def getRelationshipsAtRevision(revisionId: Int): Future[Seq[(HaplogroupRelationship, Haplogroup, Haplogroup)]]

  def getChildrenAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]]

  def getAncestryAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]]

  def addRelationshipRevisionMetadata(metadata: RelationshipRevisionMetadata): Future[Int]

  def getRelationshipRevisionMetadata(relationshipId: Int, revisionId: Int): Future[Option[RelationshipRevisionMetadata]]

  def getRelationshipRevisionHistory(relationshipId: Int): Future[Seq[(HaplogroupRelationship, RelationshipRevisionMetadata)]]

  def getRevisionsByAuthor(author: String): Future[Seq[RelationshipRevisionMetadata]]

  def getRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[RelationshipRevisionMetadata]]

  def updateRevisionComment(relationshipId: Int, revisionId: Int, newComment: String): Future[Int]

  def getLatestRevisionsByChangeType(changeType: String, limit: Int): Future[Seq[RelationshipRevisionMetadata]]

  def getRevisionChain(relationshipId: Int, revisionId: Int): Future[Seq[RelationshipRevisionMetadata]]

  def addVariantRevisionMetadata(metadata: HaplogroupVariantMetadata): Future[Int]

  def getVariantRevisionMetadata(variantId: Int, revisionId: Int): Future[Option[HaplogroupVariantMetadata]]

  def getVariantRevisionHistory(variantId: Int): Future[Seq[(HaplogroupVariant, HaplogroupVariantMetadata)]]

  def getVariantRevisionsByAuthor(author: String): Future[Seq[HaplogroupVariantMetadata]]

  def getVariantRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[HaplogroupVariantMetadata]]

  def updateVariantRevisionComment(variantId: Int, revisionId: Int, newComment: String): Future[Int]

  def getLatestVariantRevisionsByChangeType(changeType: String, limit: Int = 10): Future[Seq[HaplogroupVariantMetadata]]

  def getVariantRevisionChain(variantId: Int, revisionId: Int): Future[Seq[HaplogroupVariantMetadata]]
}

class HaplogroupRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)
                                        (implicit ec: ExecutionContext)
  extends HaplogroupRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  import models.dal.DatabaseSchema.*

  def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(h => h.name === name && h.haplogroupType === haplogroupType.toString)
      .result
      .headOption

    db.run(query)
  }

  def findHaplogroupsByDefiningVariant(variantId: String, haplogroupType: HaplogroupType): Future[Seq[Haplogroup]] = {
    val query = for {
      variant <- variants if variant.rsId === variantId || variant.variantId === variantId.toIntOption
      haplogroupVariant <- haplogroupVariants if haplogroupVariant.variantId === variant.variantId
      haplogroup <- haplogroups if
        haplogroup.haplogroupId === haplogroupVariant.haplogroupId &&
          haplogroup.haplogroupType === haplogroupType.toString
    } yield haplogroup

    db.run(query.result)
  }

  def findVariants(query: String): Future[Seq[Variant]] = {
    val normalizedQuery = query.trim.toLowerCase

    def buildQuery = {
      if (normalizedQuery.startsWith("rs")) {
        variants.filter(v => v.rsId.isDefined && v.rsId === normalizedQuery)
      } else if (normalizedQuery.contains(":")) {
        val parts = normalizedQuery.split(":")
        parts.length match {
          case 2 =>
            for {
              variant <- variants
              contig <- genbankContigs if variant.genbankContigId === contig.genbankContigId
              if (variant.commonName.isDefined && variant.commonName === parts(0)) ||
                (contig.commonName.isDefined && contig.commonName === parts(0))
              if variant.position === parts(1).toIntOption.getOrElse(0)
            } yield variant
          case 4 =>
            for {
              variant <- variants
              contig <- genbankContigs if variant.genbankContigId === contig.genbankContigId
              if (variant.commonName.isDefined && variant.commonName === parts(0)) ||
                (contig.commonName.isDefined && contig.commonName === parts(0))
              if variant.position === parts(1).toIntOption.getOrElse(0) &&
                variant.referenceAllele === parts(2) &&
                variant.alternateAllele === parts(3)
            } yield variant
          case _ =>
            variants.filter(_ => false)
        }
      } else {
        variants.filter(v =>
          (v.rsId.isDefined && v.rsId === normalizedQuery) ||
            (v.commonName.isDefined && v.commonName === normalizedQuery)
        )
      }
    }

    db.run(buildQuery.result)
  }

  def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.parentHaplogroupId === haplogroupId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield child

    db.run(query.result)
  }

  def getHaplogroupVariants(haplogroupId: Int): Future[Seq[(Variant, GenbankContig)]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId
      v <- variants if v.variantId === hv.variantId
      gc <- genbankContigs if gc.genbankContigId === v.genbankContigId
    } yield (v, gc)

    db.run(query.result)
  }

  def getSubtreeRelationships(rootId: Int): Future[Seq[(Haplogroup, HaplogroupRelationship)]] = {
    val query = for {
      root <- haplogroups if root.haplogroupId === rootId
      rel <- haplogroupRelationships if rel.parentHaplogroupId === root.haplogroupId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield (child, rel)

    db.run(query.result)
  }

  def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val recursiveQuery = for {
      rel <- haplogroupRelationships if rel.childHaplogroupId === haplogroupId
      parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId
    } yield parent

    db.run(recursiveQuery.result)
  }

  def getCurrentValidRelationships: Future[Seq[(HaplogroupRelationship, Haplogroup)]] = {
    val now = java.time.LocalDateTime.now()
    val query = for {
      rel <- haplogroupRelationships if
        rel.validFrom <= now &&
          (rel.validUntil.isEmpty || rel.validUntil > now)
      haplogroup <- haplogroups if haplogroup.haplogroupId === rel.childHaplogroupId
    } yield (rel, haplogroup)

    db.run(query.result)
  }

  def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[Variant]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId
      variant <- variants if variant.variantId === hv.variantId
    } yield variant

    db.run(query.result)
  }

  def getHaplogroupsByVariant(variantId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      hv <- haplogroupVariants if hv.variantId === variantId
      haplogroup <- haplogroups if haplogroup.haplogroupId === hv.haplogroupId
    } yield haplogroup

    db.run(query.result)
  }

  def addVariantToHaplogroup(haplogroupId: Int, variantId: Int): Future[Int] = {
    val insertion = haplogroupVariants += HaplogroupVariant(None, haplogroupId, variantId)
    db.run(insertion)
  }

  def removeVariantFromHaplogroup(haplogroupId: Int, variantId: Int): Future[Int] = {
    val query = haplogroupVariants
      .filter(hv => hv.haplogroupId === haplogroupId && hv.variantId === variantId)
      .delete

    db.run(query)
  }

  def getHaplogroupAtRevision(haplogroupId: Int, revisionId: Int): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(h => h.haplogroupId === haplogroupId && h.revisionId === revisionId)
      .result.headOption

    db.run(query)
  }

  def getLatestRevision(haplogroupId: Int): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(_.haplogroupId === haplogroupId)
      .sortBy(_.revisionId.desc)
      .take(1)
      .result.headOption

    db.run(query)
  }

  def getRevisionHistory(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val query = haplogroups
      .filter(_.haplogroupId === haplogroupId)
      .sortBy(_.revisionId.desc)
      .result

    db.run(query)
  }

  def createNewRevision(haplogroup: Haplogroup): Future[Int] = {
    val nextRevisionQuery = haplogroups
      .filter(_.haplogroupId === haplogroup.id)
      .map(_.revisionId)
      .max
      .getOrElse(1)
      .result
      .map(_ + 1)

    db.run(nextRevisionQuery.flatMap { nextRev =>
      haplogroups += haplogroup.copy(revisionId = nextRev)
    })
  }

  def createRelationshipRevision(relationship: HaplogroupRelationship): Future[Int] = {
    val nextRevisionQuery = haplogroupRelationships
      .filter(r =>
        r.parentHaplogroupId === relationship.parentHaplogroupId &&
          r.childHaplogroupId === relationship.childHaplogroupId
      )
      .map(_.revisionId)
      .max
      .getOrElse(1)
      .result
      .map(_ + 1)

    db.run(nextRevisionQuery.flatMap { nextRev =>
      haplogroupRelationships += relationship.copy(revisionId = nextRev)
    })
  }

  def getRelationshipsAtRevision(revisionId: Int): Future[Seq[(HaplogroupRelationship, Haplogroup, Haplogroup)]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.revisionId === revisionId
      parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId &&
        parent.revisionId === revisionId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId &&
        child.revisionId === revisionId
    } yield (rel, parent, child)

    db.run(query.result)
  }

  def getChildrenAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.parentHaplogroupId === haplogroupId &&
        rel.revisionId === revisionId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId &&
        child.revisionId === revisionId
    } yield child

    db.run(query.result)
  }

  def getAncestryAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]] = {
    def recursiveAncestors(currentId: Int, ancestors: Seq[Haplogroup] = Seq.empty): DBIO[Seq[Haplogroup]] = {
      val query = for {
        rel <- haplogroupRelationships if rel.childHaplogroupId === currentId &&
          rel.revisionId === revisionId
        parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId &&
          parent.revisionId === revisionId
      } yield parent

      query.result.flatMap { parents =>
        if (parents.isEmpty) DBIO.successful(ancestors)
        else recursiveAncestors(parents.head.id.get, ancestors ++ parents)
      }
    }

    db.run(recursiveAncestors(haplogroupId))
  }

  def addRelationshipRevisionMetadata(
                                       metadata: RelationshipRevisionMetadata
                                     ): Future[Int] = {
    val insertion = relationshipRevisionMetadata += metadata
    db.run(insertion)
  }

  def getRelationshipRevisionMetadata(
                                       relationshipId: Int,
                                       revisionId: Int
                                     ): Future[Option[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(m => m.haplogroup_relationship_id === relationshipId && m.revisionId === revisionId)
      .result
      .headOption

    db.run(query)
  }

  def getRelationshipRevisionHistory(relationshipId: Int): Future[Seq[(HaplogroupRelationship, RelationshipRevisionMetadata)]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.haplogroupRelationshipId === relationshipId
      metadata <- relationshipRevisionMetadata if metadata.haplogroup_relationship_id === rel.haplogroupRelationshipId
    } yield (rel, metadata)

    db.run(query.sortBy(_._2.timestamp.desc).result)
  }

  def getRevisionsByAuthor(author: String): Future[Seq[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(_.author === author)
      .sortBy(_.timestamp.desc)
      .result

    db.run(query)
  }

  def getRevisionsBetweenDates(
                                startDate: LocalDateTime,
                                endDate: LocalDateTime
                              ): Future[Seq[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(m => m.timestamp >= startDate && m.timestamp <= endDate)
      .sortBy(_.timestamp.desc)
      .result

    db.run(query)
  }

  def updateRevisionComment(
                             relationshipId: Int,
                             revisionId: Int,
                             newComment: String
                           ): Future[Int] = {
    val query = relationshipRevisionMetadata
      .filter(m => m.haplogroup_relationship_id === relationshipId && m.revisionId === revisionId)
      .map(_.comment)
      .update(newComment)

    db.run(query)
  }

  def getLatestRevisionsByChangeType(
                                      changeType: String,
                                      limit: Int = 10
                                    ): Future[Seq[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(_.changeType === changeType)
      .sortBy(_.timestamp.desc)
      .take(limit)
      .result

    db.run(query)
  }

  def getRevisionChain(
                        relationshipId: Int,
                        revisionId: Int
                      ): Future[Seq[RelationshipRevisionMetadata]] = {
    def recursiveChain(
                        currentRevisionId: Int,
                        chain: Seq[RelationshipRevisionMetadata] = Seq.empty
                      ): DBIO[Seq[RelationshipRevisionMetadata]] = {
      val query = relationshipRevisionMetadata
        .filter(m =>
          m.haplogroup_relationship_id === relationshipId &&
            m.revisionId === currentRevisionId
        )
        .result.headOption

      query.flatMap {
        case Some(metadata) =>
          metadata.previousRevisionId match {
            case Some(prevId) => recursiveChain(prevId, chain :+ metadata)
            case None => DBIO.successful(chain :+ metadata)
          }
        case None => DBIO.successful(chain)
      }
    }

    db.run(recursiveChain(revisionId))
  }

  def addVariantRevisionMetadata(metadata: HaplogroupVariantMetadata): Future[Int] = {
    val insertion = haplogroupVariantMetadata += metadata
    db.run(insertion)
  }

  def getVariantRevisionMetadata(variantId: Int, revisionId: Int): Future[Option[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(m => m.haplogroup_variant_id === variantId && m.revision_id === revisionId)
      .result
      .headOption

    db.run(query)
  }

  def getVariantRevisionHistory(variantId: Int): Future[Seq[(HaplogroupVariant, HaplogroupVariantMetadata)]] = {
    val query = for {
      variant <- haplogroupVariants if variant.haplogroupVariantId === variantId
      metadata <- haplogroupVariantMetadata if metadata.haplogroup_variant_id === variant.haplogroupVariantId
    } yield (variant, metadata)

    db.run(query.sortBy(_._2.timestamp.desc).result)
  }

  def getVariantRevisionsByAuthor(author: String): Future[Seq[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(_.author === author)
      .sortBy(_.timestamp.desc)
      .result

    db.run(query)
  }

  def getVariantRevisionsBetweenDates(
                                       startDate: LocalDateTime,
                                       endDate: LocalDateTime
                                     ): Future[Seq[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(m => m.timestamp >= startDate && m.timestamp <= endDate)
      .sortBy(_.timestamp.desc)
      .result

    db.run(query)
  }

  def updateVariantRevisionComment(
                                    variantId: Int,
                                    revisionId: Int,
                                    newComment: String
                                  ): Future[Int] = {
    val query = haplogroupVariantMetadata
      .filter(m => m.haplogroup_variant_id === variantId && m.revision_id === revisionId)
      .map(_.comment)
      .update(newComment)

    db.run(query)
  }

  def getLatestVariantRevisionsByChangeType(
                                             changeType: String,
                                             limit: Int = 10
                                           ): Future[Seq[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(_.change_type === changeType)
      .sortBy(_.timestamp.desc)
      .take(limit)
      .result

    db.run(query)
  }

  def getVariantRevisionChain(
                               variantId: Int,
                               revisionId: Int
                             ): Future[Seq[HaplogroupVariantMetadata]] = {
    def recursiveChain(
                        currentRevisionId: Int,
                        chain: Seq[HaplogroupVariantMetadata] = Seq.empty
                      ): DBIO[Seq[HaplogroupVariantMetadata]] = {
      val query = haplogroupVariantMetadata
        .filter(m =>
          m.haplogroup_variant_id === variantId &&
            m.revision_id === currentRevisionId
        )
        .result.headOption

      query.flatMap {
        case Some(metadata) =>
          metadata.previous_revision_id match {
            case Some(prevId) => recursiveChain(prevId, chain :+ metadata)
            case None => DBIO.successful(chain :+ metadata)
          }
        case None => DBIO.successful(chain)
      }
    }

    db.run(recursiveChain(revisionId))
  }
}

