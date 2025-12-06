# Haplogroup Discovery System

## What It Does

The Haplogroup Discovery System automatically finds new branches on the Y-DNA and mtDNA family trees by analyzing genetic samples from the community.

When someone submits their DNA data, the system compares it against the known family tree. Sometimes the DNA contains mutations that extend beyond the current tree—these are called "private variants." When multiple unrelated people share the same private variants, it's evidence of a new branch that should be added to the tree.

---

## How It Works

### 1. Discover

When DNA samples are analyzed, the system identifies mutations that don't match any known branch on the tree. These "private variants" are potential new discoveries.

### 2. Correlate

The system looks for patterns across all samples—both from individual users and from academic research publications. When multiple independent samples share the same private variants, it suggests a real genetic lineage rather than random mutations.

### 3. Propose

Once enough evidence accumulates (configurable threshold, default 3 samples), the system creates a "proposed branch"—a candidate for addition to the official tree.

### 4. Review

Expert curators review proposed branches, examining the supporting evidence. They can:
- **Accept** the proposal and add it to the official tree
- **Reject** it if the evidence is insufficient or the variants appear to be errors
- **Modify** the proposal to refine which variants define the branch
- **Split** a proposal if the evidence suggests multiple distinct sub-branches

### 5. Evolve

Accepted proposals become official branches on the tree. Samples that contributed evidence are automatically reassigned to the new, more specific branch.

---

## Key Benefits

### For Genetic Genealogists

- **Automatic discovery** - New branches are found without manual research
- **Community-powered** - Every sample contributes to tree expansion
- **Transparent process** - See which proposals your sample supports

### For Researchers

- **Unified evidence** - Combines data from individual users and academic publications
- **Audit trail** - Every change to the tree is logged with justification
- **Configurable thresholds** - Adjust consensus requirements for Y-DNA vs mtDNA

### For the Community

- **Living tree** - The haplogroup tree evolves with new discoveries
- **Quality control** - Curator review ensures accuracy
- **Visibility control** - Proposed branches remain hidden from public reporting until accepted

---

## Evidence Sources

The system aggregates evidence from two sources:

| Source | Description |
|--------|-------------|
| **Citizen Samples** | DNA data submitted by individual users through the Edge App |
| **Publication Samples** | Academic research data loaded by curators from peer-reviewed studies |

Both sources contribute equally to consensus building. A proposed branch might be supported by a mix of citizen and publication samples.

---

## Consensus Thresholds

The system uses configurable thresholds to determine when proposals are ready for review:

| Stage | Requirement | What Happens |
|-------|-------------|--------------|
| Proposal Created | 1+ sample with shared variants | Tracked as "Pending" |
| Ready for Review | 3+ samples (configurable) | Flagged for curator attention |
| Auto-Promotion | 10+ samples with high confidence | Can be automatically accepted |

Different thresholds can be set for Y-DNA and mtDNA trees.

---

## Curator Workflow

Curators are trusted experts who review and approve tree changes.

### What Curators Can Do

1. **Review proposals** - Examine evidence, variant details, and sample distribution
2. **Accept branches** - Add new branches to the official tree with a name (e.g., "R-ABC123")
3. **Reject proposals** - Remove low-quality or erroneous proposals with documented reasoning
4. **Split branches** - Divide a proposal when evidence suggests multiple sub-branches
5. **Manual creation** - Add branches directly without waiting for consensus (rare)

### Audit Trail

Every curator action is logged permanently:
- Who made the change
- What was changed (before/after snapshots)
- Why (curator's justification)
- When it occurred

---

## Privacy and Visibility

| Data Type | Visibility |
|-----------|------------|
| Proposed branches | Hidden from public tree views |
| Accepted branches | Visible to everyone |
| Private variants | Visible only to sample owner and curators |
| Curator actions | Logged for accountability, visible to admins |

Proposed branches remain invisible in public reports until a curator accepts them. This prevents speculative branches from appearing in user-facing haplogroup assignments.

---

## Federated Architecture

The discovery system is built on a federated model where users own and control their genetic data.

### You Own Your Data

Your DNA data lives in your **Personal Data Server (PDS)**—a secure digital vault that you control. Think of it like having your own personal cloud storage for genetic information. You decide what to share and with whom.

### The Edge App

The **Edge App** runs on your device and handles all the heavy computation:
- Analyzes your DNA files locally
- Determines your haplogroup assignment
- Identifies private variants
- Publishes results to your PDS

Your raw genetic files never leave your device. Only the analysis results (haplogroup assignments and variant summaries) are shared.

### How Data Flows

```
Your Device                    Your PDS                     DecodingUs
┌──────────────┐              ┌──────────────┐              ┌──────────────┐
│  Edge App    │──results───► │  Your Data   │──stream────► │  Discovery   │
│  (analyzes   │              │  (you own)   │              │  System      │
│   locally)   │              │              │              │              │
└──────────────┘              └──────────────┘              └──────────────┘
     │                                                             │
     │                                                             ▼
     │                                                      ┌──────────────┐
     └──────────────────────────────────────────────────────│  Tree grows  │
                                                            │  with new    │
                                                            │  branches    │
                                                            └──────────────┘
```

1. **Edge App** analyzes your DNA file on your device
2. Results are saved to **your PDS** (your personal data store)
3. DecodingUs receives a notification through the **Firehose** (a real-time data stream)
4. The discovery system processes your haplogroup data to find shared variants
5. New branches are proposed and eventually added to the tree

### Why This Matters

| Traditional Model | Federated Model |
|-------------------|-----------------|
| Company stores your raw DNA | You store your raw DNA |
| Company controls access | You control access |
| Data locked in one platform | Data portable across services |
| Company can change terms | Your data, your rules |

The federated approach means:
- **Privacy by design** - Raw genetic data stays under your control
- **Portability** - Take your data to any compatible service
- **Transparency** - You can see exactly what's shared
- **Resilience** - No single point of failure for your data

---

## Example Scenario

1. **Alice** submits her Y-DNA sample. The analysis finds 2 mutations beyond her terminal haplogroup R-M269.

2. **Bob** (unrelated to Alice) submits his sample. He also has 2 mutations beyond R-M269—and they match Alice's!

3. The system creates a **proposed branch** under R-M269 with these shared variants.

4. Over the next few months, **Carol** and **David** also submit samples with the same mutations.

5. With 4 independent samples, the proposal reaches the **"Ready for Review"** threshold.

6. A **curator** reviews the evidence, confirms the variants are legitimate, and **accepts** the proposal as "R-XYZ789".

7. Alice, Bob, Carol, and David are all **reassigned** from R-M269 to the new R-XYZ789 branch.

8. The new branch appears on the **public tree** for everyone to see.

---

## Relationship to Other Features

| Feature | Relationship |
|---------|--------------|
| **Multi-Test-Type Support** | Y/mtDNA variants from WGS, Big Y-700, chip data, etc. all feed into discovery |
| **IBD Matching** | Separate system—autosomal comparisons happen directly between users |
| **Sequencer Lab Inference** | Separate system—tracks which labs own which sequencing instruments |

---

## Technical Reference

For implementation details, database schema, and API specifications, see:
- [`haplogroup-discovery-system.md`](./haplogroup-discovery-system.md) (full technical design)
