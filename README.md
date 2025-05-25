# Decoding Us
A collaborative platform for genetic genealogy and population research, bridging community efforts with secure, AT Protocol-powered Edge computing.
## Site Information
[Decoding-Us.com](https://decoding-us.com/)

## Overview

Decoding Us is the central web application designed to empower genetic genealogists and researchers by:

* Facilitating collaborative development of Y-DNA and mtDNA haplogroup trees, leveraging de-identified data from Edge computing participants within a Pan Genome framework. This allows for more comprehensive and accurate analysis than traditional linear assemblies.
* Enabling discovery of genetic relatives through privacy-preserving IBD segment matching across the network of users.
* Providing a secure hub for sharing and refining population research insights derived from distributed genetic data.

## Purpose

Decoding Us serves as a next-generation citizen science platform for population research, specifically engineered to connect and empower individuals contributing to genetic genealogy. It bridges the gap between individual genomic data (processed securely via companion Edge computing software) and global research efforts. A core objective is to collaboratively build out highly resolved haplogroup trees, and to this end, the platform integrates data from a curated list of academic research papers by showing where these public samples fit within the experimental trees being constructed by the community. Direct links back to original sequencing data in repositories like the European Nucleotide Archive (ENA) are provided for full transparency.

Built on the principles of decentralized personal data management, it leverages the AT Protocol and Personal Data Servers (PDS) to ensure that sensitive genomic data remains under the user's control. By focusing on de-identified call signatures and IBD segment discovery within a Pan Genome context, Decoding Us enables the community to expand genetic relative networks, all while upholding the highest standards of data privacy and security. The Pan Genome approach moves beyond single reference genomes, providing a more inclusive and accurate representation of human genetic diversity for genealogical and population studies.
## Key Features

* Collaborative Haplogroup Tree Resolution: Tools for community-driven refinement and expansion of Y-DNA and mtDNA haplogroup trees based on de-identified call signatures.
* Academic Data Integration & Contextualization: Visualize publicly available academic samples within the experimental haplogroup trees built by the Decoding Us community, with direct links to original sequencing data in public archives (e.g., ENA).
* Privacy-Preserving Genetic Relative Discovery: Functionality to discover and connect with other users based on matching IBD segments, enhancing genealogical research.
* Secure Data Interaction: A web interface for interacting with and visualizing insights derived from de-identified data processed on secure Edge nodes.
* Research Collaboration Tools: Features to facilitate discussion, sharing, and joint analysis among genealogists and researchers.

## Technologies

- [Scala 3](https://www.scala-lang.org/) - Scalable programming language
- [Play Framework](https://www.playframework.com/) - Web framework for Scala
- [HTMX](https://htmx.org/) - HTML extension for modern web applications
- [PostgreSQL](https://www.postgresql.org/) - Relational database management system
- [Docker](https://www.docker.com/) - Containerization platform
- [AWS](https://aws.amazon.com/) - Cloud computing platform

## Note on Data Processing & Privacy
Sensitive genetic computing and direct handling of raw sequencing data are performed securely on the user's chosen 
environment (local network or leased virtual private server) via companion Edge computing software. This utilizes AT 
Protocol and Personal Data Servers (PDS) to maintain individual data sovereignty. Decoding Us, the web application, 
operates exclusively with de-identified call signatures and aggregated, privacy-preserving insights to facilitate 
collaborative research and network building.

## [![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

This project is licensed under the BSD 3-Clause License. See the [LICENSE](LICENSE) file for details.