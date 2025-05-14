package models.dal

import models.dal.MyPostgresProfile.api._

object DatabaseSchema {
  val analysisMethods = TableQuery[AnalysisMethodTable]
  val ancestryAnalyses = TableQuery[AncestryAnalysisTable]
  val specimenDonors = TableQuery[SpecimenDonorsTable]
  val biosamples = TableQuery[BiosamplesTable]
  val citizenBiosamples = TableQuery[CitizenBiosamplesTable]
  val haplogroups = TableQuery[HaplogroupsTable]
  val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
  val genbankContigs = TableQuery[GenbankContigsTable]
  val variants = TableQuery[VariantsTable]
  val haplogroupVariants = TableQuery[HaplogroupVariantsTable]
  val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
}