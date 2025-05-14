package models.dal

import models.dal.MyPostgresProfile.api._

object DatabaseSchema {
  val analysisMethods = TableQuery[AnalysisMethodTable]
  val ancestryAnalyses = TableQuery[AncestryAnalysisTable]
  val biosamples = TableQuery[BiosamplesTable]
  val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
  val citizenBiosamples = TableQuery[CitizenBiosamplesTable]
  val enaStudies = TableQuery[EnaStudiesTable]
  val genbankContigs = TableQuery[GenbankContigsTable]
  val haplogroups = TableQuery[HaplogroupsTable]
  val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
  val haplogroupVariants = TableQuery[HaplogroupVariantsTable]
  val pgpBiosamples = TableQuery[PgpBiosamplesTable]
  val populations = TableQuery[PopulationsTable]
  val publications = TableQuery[PublicationsTable]
  val specimenDonors = TableQuery[SpecimenDonorsTable]
  val variants = TableQuery[VariantsTable]
}