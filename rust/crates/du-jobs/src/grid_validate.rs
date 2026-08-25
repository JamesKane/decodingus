//! Grid validation — adaptive replication (design §6.1), as a `run-once` job.
//!
//! Per unit with unvalidated submissions: cluster the digests by agreement, and if one cluster
//! satisfies the unit's replica bar *and* the trust policy, promote the unit to `CANONICAL` and pay
//! the contributors who agreed.
//!
//! # Why trust is derived from grid work only
//!
//! Tiers come from a contributor's `grid.submission` history, never from the social reputation
//! score. Those are different claims: social standing says a person participates well in the
//! community, and grid trust has to say their *machine produces correct results*. Letting the first
//! stand in for the second would let a well-regarded member canonicalize bad output on reputation
//! alone, which is precisely the attack adaptive replication exists to stop.
//!
//! # The constants are placeholders and are meant to be
//!
//! Design §9 asks for conservative placeholders until real throughput data exists. `AGREED_FOR_*`
//! and the credit weights are exactly that. They are deliberately strict: too-slow promotion costs
//! duplicated compute, while too-fast promotion costs a wrong canonical result, and only one of
//! those is recoverable.

use du_db::grid::{self, digest, GridHistory, PendingSubmission};
use du_db::PgPool;
use std::collections::HashSet;

/// Units examined per run.
const BATCH: i64 = 200;

/// Agreed units needed to leave `Untrusted`.
const AGREED_FOR_PROVISIONAL: i64 = 5;
/// Agreed units needed to reach `Trusted`, where one submission can canonicalize alone.
const AGREED_FOR_TRUSTED: i64 = 25;
/// A contributor with any divergence in its history cannot be `Trusted`. Blunt on purpose: with no
/// real data yet, the safe reading of a divergence is the pessimistic one.
const MAX_DIVERGENCE_FOR_TRUSTED: i64 = 0;

/// Fraction of would-be lone canonicalizations that are held for a shadow replica instead.
pub const SPOT_CHECK_RATE: f64 = 0.05;

/// Cobblestones (in milli-units) for running the stack on one unit, whatever its size.
const BASE_CREDIT: i64 = 2 * grid::COBBLESTONE;
/// Additional cobblestones per gigabase realigned. Only a FASTQ unit earns this.
const REALIGN_PER_GBP: i64 = grid::COBBLESTONE / 2;

/// What a contributor's history has earned it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Tier {
    /// New, or still proving itself. Can contribute to a quorum but never satisfy one alone.
    Untrusted,
    /// Established. Counts toward quorum and may pair with an untrusted node to reach it.
    Provisional,
    /// Sustained agreement. One submission canonicalizes, subject to a random shadow re-check.
    Trusted,
}

fn tier_of(h: GridHistory) -> Tier {
    if h.agreed >= AGREED_FOR_TRUSTED && h.divergent <= MAX_DIVERGENCE_FOR_TRUSTED {
        Tier::Trusted
    } else if h.agreed >= AGREED_FOR_PROVISIONAL {
        Tier::Provisional
    } else {
        Tier::Untrusted
    }
}

/// Partition submissions into clusters that agree, keeping only mutually comparable ones together.
///
/// Comparability comes first: results against different references, or different stack majors, are
/// answers to different questions rather than a disagreement, and clustering them together would
/// manufacture divergence out of nothing.
fn cluster(subs: &[PendingSubmission]) -> Vec<Vec<usize>> {
    let mut clusters: Vec<Vec<usize>> = Vec::new();
    'next: for (i, s) in subs.iter().enumerate() {
        for c in clusters.iter_mut() {
            let head = &subs[c[0]];
            if digest::comparable(
                &head.reference_build,
                &head.stack_version,
                &s.reference_build,
                &s.stack_version,
            ) && digest::agree(&head.digest, &s.digest)
            {
                c.push(i);
                continue 'next;
            }
        }
        clusters.push(vec![i]);
    }
    // Biggest first, and among equals the one that got there first — so `CANONICAL_FIRST` goes to
    // whoever actually arrived first rather than to an accident of iteration order.
    clusters.sort_by(|a, b| b.len().cmp(&a.len()).then(a[0].cmp(&b[0])));
    clusters
}

/// Whether `cluster` may canonicalize the unit, given who is in it.
///
/// A lone submission canonicalizes only from a `Trusted` contributor. Two or more agreeing from
/// **distinct** contributors always suffice: independent agreement is the evidence, and requiring
/// a trusted node on top of it would stall a young fleet where nobody is trusted yet.
fn may_canonicalize(tiers: &[Tier], required: i16) -> bool {
    match tiers.len() {
        0 => false,
        1 => tiers[0] == Tier::Trusted,
        n => n >= required.max(2) as usize,
    }
}

/// Credit for one agreed unit, in milli-cobblestones. Passthrough units earn the base only; a
/// realigned unit earns per gigabase on top, which is where the real compute went.
fn credit_for(data_kind: &str, est_bases: Option<i64>) -> i64 {
    if data_kind == "FASTQ" {
        let gbp = est_bases.unwrap_or(0) as f64 / 1e9;
        BASE_CREDIT + (gbp * REALIGN_PER_GBP as f64).round() as i64
    } else {
        BASE_CREDIT
    }
}

/// Outcome of one validation pass.
#[derive(Debug, Default)]
pub struct ValidateOutcome {
    pub examined: usize,
    pub canonicalized: usize,
    pub contested: usize,
    pub shadow_requested: usize,
    pub credited: usize,
}

pub async fn validate(pool: &PgPool, spot_check_rate: f64) -> anyhow::Result<ValidateOutcome> {
    let units = grid::units_awaiting_validation(pool, BATCH).await?;
    let mut out = ValidateOutcome {
        examined: units.len(),
        ..Default::default()
    };

    for unit in &units {
        let subs = grid::submissions_for_validation(pool, unit.id).await?;
        if subs.is_empty() {
            continue;
        }
        let clusters = cluster(&subs);
        let winner = &clusters[0];

        // A digest with no comparable call agrees with every other such digest, because every
        // field is absent on both sides. Two nodes whose analysis failed would therefore reach a
        // quorum on nothing and be paid for it. The node is expected to fail its unit instead of
        // sending an empty digest, and the AppView must not depend on that: a node is untrusted by
        // construction, which is the premise adaptive replication rests on.
        if !digest::has_content(&subs[winner[0]].digest) {
            tracing::warn!(
                unit = %unit.sample_accession,
                submissions = winner.len(),
                "grid-validate: the agreeing digests carry no calls; not canonical"
            );
            continue;
        }

        // Distinct DIDs, not distinct rows. The unique index already makes them the same thing;
        // relying on it silently would leave this correct only by coincidence.
        let dids: HashSet<&str> = winner.iter().map(|&i| subs[i].did.as_str()).collect();
        let mut tiers = Vec::with_capacity(dids.len());
        for did in &dids {
            tiers.push(tier_of(grid::grid_history(pool, did).await?));
        }

        if !may_canonicalize(&tiers, unit.required_replicas) {
            // Several mutually contradictory answers and nothing decisive: raise the bar and let
            // the next replica break the tie. With two conflicting clusters there is no evidence
            // about which is wrong, so nobody is marked divergent yet.
            if clusters.len() > 1 {
                grid::contest(
                    pool,
                    unit.id,
                    "submissions disagree; awaiting a tie-breaker",
                )
                .await?;
                out.contested += 1;
            }
            continue;
        }

        // A trusted node about to canonicalize alone is sometimes held for a shadow replica, so
        // that trust is re-earned rather than assumed indefinitely. The draw is made in the
        // database (see `maybe_request_shadow`) because a rule derived from the unit id or the
        // digest would be one a contributor could compute in advance and cheat around — which is
        // exactly what §6.2 says a spot-check must not be.
        if tiers.len() == 1 && grid::maybe_request_shadow(pool, unit.id, spot_check_rate).await? {
            out.shadow_requested += 1;
            continue;
        }

        let agreed_ids: Vec<i64> = winner.iter().map(|&i| subs[i].id).collect();
        let divergent_ids: Vec<i64> = clusters[1..]
            .iter()
            .flatten()
            .map(|&i| subs[i].id)
            .collect();
        let canonical = subs[winner[0]].digest.clone();
        grid::canonicalize(pool, unit.id, &canonical, &agreed_ids, &divergent_ids).await?;
        out.canonicalized += 1;

        let amount = credit_for(&unit.data_kind, unit.est_bases);
        for (rank, &i) in winner.iter().enumerate() {
            let kind = if rank == 0 {
                "CANONICAL_FIRST"
            } else {
                "QUORUM_AGREE"
            };
            match grid::award_credit(pool, &subs[i].did, unit.id, subs[i].id, amount, kind).await {
                Ok(true) => out.credited += 1,
                Ok(false) => {} // already paid for this unit; the ledger's unique index held
                Err(e) => {
                    tracing::warn!(did = %subs[i].did, unit = unit.id, error = %e, "grid-validate: credit failed")
                }
            }
        }
        tracing::debug!(unit = %unit.sample_accession, replicas = winner.len(), "grid-validate: canonical");
    }

    tracing::info!(
        examined = out.examined,
        canonicalized = out.canonicalized,
        contested = out.contested,
        shadow_requested = out.shadow_requested,
        credited = out.credited,
        "grid-validate: done"
    );
    Ok(out)
}

/// Close every lapsed lease. See `du_db::grid::reap_expired` for what this is and is not for — in
/// particular, it is **not** what returns a unit to the claimable pool.
pub async fn reap(pool: &PgPool) -> anyhow::Result<u64> {
    let n = grid::reap_expired(pool).await?;
    if n > 0 {
        tracing::info!(closed = n, "grid-reap: lapsed leases closed");
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sub(id: i64, did: &str, y: &str, build: &str, stack: &str) -> PendingSubmission {
        PendingSubmission {
            id,
            did: did.into(),
            digest: json!({"calls": {"sex": "XY", "y_terminal": y, "coverage_mean": 30.0}}),
            stack_version: stack.into(),
            reference_build: build.into(),
            submitted_at: chrono::DateTime::from_timestamp(1_724_500_000 + id, 0).unwrap(),
        }
    }

    #[test]
    fn agreeing_submissions_form_one_cluster() {
        let subs = vec![
            sub(1, "did:a", "R-A", "chm13v2.0", "1.7.0"),
            sub(2, "did:b", "R-A", "chm13v2.0", "1.8.1"),
        ];
        assert_eq!(
            cluster(&subs),
            vec![vec![0, 1]],
            "a minor version bump does not split a quorum"
        );
    }

    #[test]
    fn disagreeing_submissions_split_and_the_larger_cluster_leads() {
        let subs = vec![
            sub(1, "did:a", "R-WRONG", "chm13v2.0", "1.7.0"),
            sub(2, "did:b", "R-A", "chm13v2.0", "1.7.0"),
            sub(3, "did:c", "R-A", "chm13v2.0", "1.7.0"),
        ];
        let c = cluster(&subs);
        assert_eq!(c[0], vec![1, 2], "the majority answer leads");
        assert_eq!(c[1], vec![0]);
    }

    /// Different references are not a disagreement — they are different questions.
    #[test]
    fn incomparable_submissions_never_share_a_cluster() {
        let subs = vec![
            sub(1, "did:a", "R-A", "chm13v2.0", "1.7.0"),
            sub(2, "did:b", "R-A", "GRCh38", "1.7.0"),
            sub(3, "did:c", "R-A", "chm13v2.0", "2.0.0"),
        ];
        assert_eq!(
            cluster(&subs).len(),
            3,
            "same call, three incompatible contexts"
        );
    }

    #[test]
    fn only_a_trusted_contributor_canonicalizes_alone() {
        assert!(may_canonicalize(&[Tier::Trusted], 2));
        assert!(!may_canonicalize(&[Tier::Provisional], 2));
        assert!(!may_canonicalize(&[Tier::Untrusted], 2));
        assert!(!may_canonicalize(&[], 2));
    }

    /// Two independent untrusted contributors agreeing is evidence, and must be enough — otherwise
    /// a fleet where nobody is trusted yet can never canonicalize anything at all.
    #[test]
    fn two_independent_contributors_suffice_whatever_their_tier() {
        assert!(may_canonicalize(&[Tier::Untrusted, Tier::Untrusted], 2));
        assert!(
            !may_canonicalize(&[Tier::Untrusted, Tier::Untrusted], 3),
            "a contested unit needs more"
        );
    }

    #[test]
    fn tiers_come_from_agreement_and_any_divergence_blocks_trust() {
        assert_eq!(
            tier_of(GridHistory {
                agreed: 0,
                divergent: 0
            }),
            Tier::Untrusted
        );
        assert_eq!(
            tier_of(GridHistory {
                agreed: 5,
                divergent: 0
            }),
            Tier::Provisional
        );
        assert_eq!(
            tier_of(GridHistory {
                agreed: 25,
                divergent: 0
            }),
            Tier::Trusted
        );
        assert_eq!(
            tier_of(GridHistory {
                agreed: 100,
                divergent: 1
            }),
            Tier::Provisional,
            "one bad result costs trust, however much good work surrounds it"
        );
    }

    #[test]
    fn only_a_realigned_unit_earns_per_gigabase() {
        assert_eq!(credit_for("CRAM", Some(90_000_000_000)), BASE_CREDIT);
        assert_eq!(
            credit_for("FASTQ", Some(90_000_000_000)),
            BASE_CREDIT + 90 * REALIGN_PER_GBP
        );
        assert_eq!(
            credit_for("FASTQ", None),
            BASE_CREDIT,
            "an unknown size pays the base rather than nothing — the work was still done"
        );
    }
}
