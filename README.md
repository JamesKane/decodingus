# Decoding Us

A collaborative platform for genetic genealogy and population research, bridging
community efforts with secure, AT Protocol–powered Edge computing.

## Site Information

[Decoding-Us.com](https://decoding-us.com/)

## Overview

Decoding Us is the **AppView** — the central web application for the Decoding Us
federation. It maintains a curated Y-DNA and mitochondrial-DNA phylogenetic
catalog, contextualizes publicly available academic samples within it, and
aggregates privacy-preserving summaries contributed across the network. It is a
read/coordination surface and a **broker**: it aggregates and reports, it does
not hold raw genomes or perform the heavy genetic analysis — that happens at the
Edge (the [Navigator](https://github.com/JamesKane/decodingus-navigator)
companion app), on hardware the participant controls.

This repository is a **Rust rewrite** of the platform, which originally ran on
Scala 3 / Play Framework. The Rust app coexists with the Scala app during the
transition and replaces it at a single cutover. The spine is complete and the
data cutover is verified end-to-end against a real production dump; see
[`rust/README.md`](rust/README.md) and [`rust/STATUS.md`](rust/STATUS.md) for the
detailed, living status.

## Purpose

Decoding Us is a next-generation citizen-science platform for population research.
It connects individual genomic data — processed securely by companion Edge
software — to global research efforts, and collaboratively builds highly resolved
haplogroup trees. It integrates a curated list of academic papers by showing where
those public samples fall within the experimental trees the community is building,
with direct links back to the original sequencing data in repositories such as the
European Nucleotide Archive (ENA).

Built on decentralized personal-data principles, it leverages the AT Protocol and
Personal Data Servers (PDS) so sensitive genomic data stays under the user's
control. The AppView itself holds **no personal data**: it works with
de-identified call signatures and aggregated, privacy-preserving insights, while
identity-bearing data and genetic comparison move directly Edge-to-Edge over an
encrypted channel that the AppView only brokers.

A Pan-Genome approach underlies the science — moving beyond single reference
genomes toward a more inclusive, accurate representation of human genetic
diversity for genealogical and population study.

## Architecture

Decoding Us is a federation of three cooperating parts:

- **AppView (this repo, Rust).** The public read surface + JSON API, the curated
  Y/mt haplotree with versioning/merge tooling, the curator suite, and the
  federated-reporting mirror. It is a single static binary backed by PostgreSQL.
- **Navigator (Edge).** A companion application that runs on the participant's own
  machine, processes raw reads (BAM/CRAM) locally, and publishes only anonymized
  computed summaries to the participant's PDS — never raw data.
- **Shared crates** (`decodingus-shared`). Pure domain types, the AT Protocol
  identity/crypto + OAuth client, and the genomics coordinate/parse library,
  consumed by both the AppView and Navigator.

**Privacy stance:** the AppView is a pure broker. The anonymized reporting mirror
drops donor PII at ingest; identity-bearing data (names, ancestor records,
kit↔identity linkage) and genetic comparison (IBD) are exchanged **Edge-to-Edge
over an encrypted, consent-gated channel** that the AppView coordinates but cannot
read. The collaboration/IBD layer that builds on this is designed in
[`documents/planning/`](documents/planning/) (the `d1`–`d5` specs + roadmap).

## Key Features

**Available now:**

- **Curated haplogroup trees.** Server-rendered Y-DNA and mtDNA cladograms built
  from multiple sources (ISOGG foundation + community + FTDNA), with temporal
  versioning, change-set review, and SNP-anchored grafting.
- **Variant catalog & naming authority.** A universal per-site variant model with
  the `DU` naming authority, alias/coordinate search, and CSV/GFF3 export, kept in
  sync by a YBrowse GFF3 ingestion pipeline (~3M variants).
- **Academic data integration.** Publications and their public biosamples shown in
  context, with links to ENA; a public "suggest a paper" flow backed by OpenAlex.
- **Federated reporting.** Anonymized population coverage, ancestry, and
  haplogroup-distribution reports aggregated from network-published summaries.
- **Public JSON API** with OpenAPI 3 / Swagger UI, plus a full curator toolset.

**On the roadmap** (designed; see `documents/planning/`):

- **Privacy-preserving genetic-relative discovery** via Edge-to-Edge IBD segment
  matching, brokered (not read) by the AppView.
- **Collaborative research projects** — attributed, scoped assertions over a
  PII-free research-subject registry, with admin-team ACLs and an encrypted
  peer-to-peer channel for any identity-bearing exchange.

## Technology Stack

- [Rust](https://www.rust-lang.org/) — a single static binary, no JVM
- [Axum](https://github.com/tokio-rs/axum) + [tokio](https://tokio.rs/) — async web stack
- [Askama](https://github.com/rinja-rs/askama) — compile-time typed templates
- [HTMX](https://htmx.org/) + Bootstrap 5 — HATEOAS-first frontend
- [SQLx](https://github.com/launchbadge/sqlx) + [PostgreSQL](https://www.postgresql.org/) / [PostGIS](https://postgis.net/)
- [AT Protocol](https://atproto.com/) — decentralized identity + OAuth (PKCE / DPoP)
- [Docker](https://www.docker.com/) for deployment; Apple `container` for Docker-less local dev

## Repository Layout

```
rust/                 the Rust AppView (workspace) — see rust/README.md
  crates/du-db        SQLx data layer + versioning/merge/graft/naming engines
  crates/du-web       Axum app: routes, Askama templates, i18n, auth, JSON API
  crates/du-jobs      scheduled jobs + the Jetstream reporting-mirror consumer
  crates/du-external  OpenAlex / ENA / NCBI / AWS clients
  crates/du-migrate   legacy → new-schema ETL + the tree builder
  migrations/         the redesigned PostgreSQL schema
documents/            architecture, planning specs (incl. d1–d5), proposals
app/                  the legacy Scala/Play application (retired at cutover)
```

Shared crates live in the sibling [`decodingus-shared`](https://github.com/JamesKane/decodingus-shared)
repo and are pulled in as pinned git dependencies.

## Getting Started

Full setup, run, test, ETL, and deploy instructions live in
[`rust/README.md`](rust/README.md). The short version:

```sh
cd rust
eval "$(./scripts/test-db.sh up)"                            # local PostGIS (Apple container; no Docker)
DATABASE_URL=... APP_SECRET=<32+ chars> cargo run -p du-web  # serves on :9000
```

## Note on Data Processing & Privacy

Sensitive genetic computation and direct handling of raw sequencing data are
performed on the participant's own environment (local network or leased VPS) by
companion Edge software, using the AT Protocol and Personal Data Servers to
maintain data sovereignty. The Decoding Us AppView operates exclusively on
de-identified call signatures and aggregated, privacy-preserving insights; any
identity-bearing exchange between participants is end-to-end encrypted and merely
brokered by the AppView, which never sees the plaintext.

## Related Projects

- [DecodingUs — Navigator](https://github.com/JamesKane/decodingus-navigator) — the companion Edge computing application.
- [decodingus-shared](https://github.com/JamesKane/decodingus-shared) — shared Rust crates (domain, AT Protocol, genomics).

## [![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

This project is licensed under the BSD 3-Clause License. See the [LICENSE](LICENSE) file for details.
