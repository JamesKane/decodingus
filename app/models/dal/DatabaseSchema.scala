package models.dal

import models.dal.MyPostgresProfile.api._ // Adjust the import path if needed

object DatabaseSchema {
  val specimenDonors = TableQuery[SpecimenDonorsTable]
  val biosamples = TableQuery[BiosamplesTable]
  val haplogroups = TableQuery[HaplogroupsTable]
  val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
  val genbankContigs = TableQuery[GenbankContigsTable]
  val variants = TableQuery[VariantsTable]
  val haplogroupVariants = TableQuery[HaplogroupVariantsTable]
  val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
}