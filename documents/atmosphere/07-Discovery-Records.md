# Discovery Records

Record types for crowdsourced discovery and inference systems.

**Status:** Future Scope (Sequencer Lab Inference System)

---

## Instrument Observation Record

This record allows citizens to contribute instrument-lab observations from their sequencing data for crowdsourced lab discovery.

**NSID:** `com.decodingus.atmosphere.instrumentObservation`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.instrumentObservation",
  "defs": {
    "main": {
      "type": "record",
      "description": "An observation of a sequencer instrument and its associated laboratory, extracted from BAM/CRAM read headers.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "instrumentId", "labName", "biosampleRef"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this observation record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "instrumentId": {
            "type": "string",
            "description": "The instrument ID extracted from the @RG header (e.g., 'A00123').",
            "minLength": 1,
            "maxLength": 255
          },
          "labName": {
            "type": "string",
            "description": "The name of the sequencing laboratory (as known by the user or inferred).",
            "minLength": 1,
            "maxLength": 255
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample from which this observation was extracted."
          },
          "sequenceRunRef": {
            "type": "string",
            "description": "AT URI of the specific sequence run (optional, for precision)."
          },
          "platform": {
            "type": "string",
            "description": "Sequencing platform (e.g., 'ILLUMINA', 'PACBIO').",
            "knownValues": ["ILLUMINA", "PACBIO", "NANOPORE", "MGI", "ELEMENT", "ULTIMA"]
          },
          "instrumentModel": {
            "type": "string",
            "description": "Inferred or known instrument model (e.g., 'NovaSeq 6000')."
          },
          "flowcellId": {
            "type": "string",
            "description": "Flowcell identifier if extractable from read headers."
          },
          "runDate": {
            "type": "string",
            "format": "datetime",
            "description": "Date of the sequencing run if extractable."
          },
          "confidence": {
            "type": "string",
            "description": "Confidence level of the lab association.",
            "knownValues": ["KNOWN", "INFERRED", "GUESSED"],
            "default": "INFERRED"
          }
        }
      }
    }
  }
}
```

---

## How It Works

1. **Extraction:** When a user processes their BAM/CRAM file in the Navigator Workbench, the `@RG` (read group) headers are parsed to extract `instrumentId`, `platform`, and other metadata.

2. **User Contribution:** If the user knows which lab performed the sequencing, they can create an `instrumentObservation` record with `confidence: KNOWN`.

3. **Consensus Building:** The DecodingUs AppView aggregates observations for each `instrumentId`:
   - Multiple users reporting the same `instrumentId` → `labName` association increases confidence
   - When consensus threshold is reached, a proposal is created for curator review
   - Curators can approve the association, adding it to the verified database

4. **Benefits:**
   - Users can identify which lab performed their sequencing (provenance)
   - Research projects can understand sample origins
   - Quality metrics can be aggregated by lab for benchmarking

---

## Private Variant Record

This record lets a citizen publish the **private variants** their analysis found beyond
their assigned terminal haplogroup — the mutations that may define a new branch. The
DecodingUs AppView mirrors them into `fed.private_variant` and the **discovery consensus
engine** (`du_db::discovery`) pools them across submitters by variant-set similarity
(Jaccard) into proposed branches for curator review. One record per (biosample, DNA arm).

**Privacy:** like the `biosample`/`strProfile` summary records, this is citizen-opt-in,
keyed by biosample ref (no donor PII); variants are anonymized to coordinates/known names.

**NSID:** `com.decodingus.atmosphere.privateVariant`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.privateVariant",
  "defs": {
    "main": {
      "type": "record",
      "description": "The private variants a sample carries beyond its assigned terminal haplogroup — candidate defining mutations for a new branch.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "biosampleRef", "dnaType", "terminalHaplogroup", "variants"],
        "properties": {
          "meta": { "type": "ref", "ref": "com.decodingus.atmosphere.defs#recordMeta" },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample these private variants were extracted from."
          },
          "sequenceRunRef": {
            "type": "string",
            "description": "AT URI of the specific sequence run (optional, for precision)."
          },
          "dnaType": {
            "type": "string",
            "description": "Which tree the variants extend.",
            "knownValues": ["Y_DNA", "MT_DNA"]
          },
          "terminalHaplogroup": {
            "type": "string",
            "description": "The terminal haplogroup the sample was assigned (e.g., 'R-M269'); the private variants sit below it."
          },
          "variants": {
            "type": "array",
            "description": "The private (mismatching) variant calls beyond the terminal.",
            "items": {
              "type": "object",
              "required": ["contig", "position", "ancestral", "derived"],
              "properties": {
                "name": { "type": "string", "description": "Known name if any (e.g., 'FT123456'); omit for novel variants." },
                "contig": { "type": "string", "description": "Reference contig (e.g., 'chrY')." },
                "position": { "type": "integer", "description": "GRCh38 position." },
                "ancestral": { "type": "string", "description": "Ancestral allele." },
                "derived": { "type": "string", "description": "Derived allele." },
                "rsId": { "type": "string", "description": "dbSNP rsID if known." }
              }
            }
          }
        }
      }
    }
  }
}
```

---

## Backend Mapping

* **`InstrumentObservation`:** Maps to `instrument_observation` table for lab inference consensus.
* **`PrivateVariant`:** Mirrored to `fed.private_variant`; the discovery consensus engine
  (`du_db::discovery`) materializes it into `tree.biosample_private_variant` and pools it
  into `tree.proposed_branch`. See
  [haplogroup-discovery-system.md](../haplogroup-discovery-system.md) (D6).

See [sequencer-lab-inference-system.md](../sequencer-lab-inference-system.md) for implementation planning.
