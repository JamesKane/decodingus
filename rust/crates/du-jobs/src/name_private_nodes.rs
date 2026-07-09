//! One-shot backfill: give real names to de-novo **private placeholder** tree nodes
//! (`<parent>:n<k>`) whose defining sites have since acquired a public SNP name.
//!
//! The de-novo loader names a node from a catalog SNP on its defining branch, else it
//! falls back to the ytree build's placeholder id (`Q-Z35893:n0`). When YBrowse later
//! names one of those sites (see `resync-ybrowse.sh`), nothing re-derives the node's
//! name — the placeholder sticks even though a namable marker now sits on the branch.
//! This job closes that gap, applying the ytree stage-88 rule (candidate #1): rename
//! each such node to the **lowest natural-sort** namable SNP it carries, keeping the old
//! id as an alias. Names are resolved by site (co-located named `core.variant`), so a
//! fresh YBrowse resync directly widens what can be named. Recurrent-region markers and
//! coordinate-synthetic names are never candidates.
//!
//! Usage:
//!   decodingus-jobs run-once name-private-nodes          # preview (no writes)
//!   decodingus-jobs run-once name-private-nodes --apply  # rename + bump tree revision

use du_db::PgPool;

pub struct Config {
    pub apply: bool,
}

impl Config {
    pub fn from_env(args: &[String]) -> Self {
        Config { apply: args.iter().any(|a| a == "--apply") }
    }
}

/// Natural-order comparison: compare maximal digit runs numerically and other runs
/// bytewise, so `C106099 < C114528 < MF677040` (not plain lexicographic, which would
/// misorder unequal-length numbers). Mirrors the ytree relabel's "natural sort".
fn natural_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    let (mut ai, mut bi) = (a.bytes().peekable(), b.bytes().peekable());
    loop {
        match (ai.peek().copied(), bi.peek().copied()) {
            (None, None) => return Ordering::Equal,
            (None, _) => return Ordering::Less,
            (_, None) => return Ordering::Greater,
            (Some(x), Some(y)) => {
                if x.is_ascii_digit() && y.is_ascii_digit() {
                    // Compare the two digit runs as numbers (skip leading zeros).
                    let da: String = std::iter::from_fn(|| ai.next_if(u8::is_ascii_digit).map(char::from)).collect();
                    let db: String = std::iter::from_fn(|| bi.next_if(u8::is_ascii_digit).map(char::from)).collect();
                    let (ta, tb) = (da.trim_start_matches('0'), db.trim_start_matches('0'));
                    let ord = ta.len().cmp(&tb.len()).then_with(|| ta.cmp(tb));
                    if ord != Ordering::Equal {
                        return ord;
                    }
                } else {
                    ai.next();
                    bi.next();
                    if x != y {
                        return x.cmp(&y);
                    }
                }
            }
        }
    }
}

/// The major-haplogroup prefix a placeholder id carries, e.g. `Q-Z35893:n0` → `Q`
/// (the segment before the first `-`, ignoring any `:n…` suffix). Y haplogroup names
/// are `<major>-<definingSNP>`, so a renamed subclade must keep the major letter:
/// `Q-Z35893:n0` → `Q-C106099`, not the bare `C106099`. `None` if the id has no `-`
/// (no major clade to preserve → the bare SNP name is used).
fn major_clade(placeholder: &str) -> Option<&str> {
    let base = placeholder.split(':').next().unwrap_or(placeholder);
    base.split_once('-').map(|(major, _)| major)
}

/// Prefix each bare SNP candidate with the node's major clade (when it has one).
fn qualify(candidates: &[String], placeholder: &str) -> Vec<String> {
    match major_clade(placeholder) {
        Some(major) => candidates.iter().map(|snp| format!("{major}-{snp}")).collect(),
        None => candidates.to_vec(),
    }
}

/// Pick the lowest natural-sort candidate whose name is not already taken by a live
/// haplogroup (nor already claimed earlier in this run). `None` if all collide.
fn pick_name(candidates: &[String], taken: &std::collections::HashSet<String>) -> Option<String> {
    let mut c: Vec<&String> = candidates.iter().collect();
    c.sort_by(|a, b| natural_cmp(a, b));
    c.into_iter().find(|n| !taken.contains(*n)).cloned()
}

pub async fn run(pool: &PgPool, cfg: &Config) -> anyhow::Result<()> {
    let nodes = du_db::haplogroup::private_nodes_needing_names(pool).await?;
    tracing::info!(candidates = nodes.len(), apply = cfg.apply, "name-private-nodes starting");

    // Per-dna-type set of names already in use, extended as we assign (so two nodes
    // in this run can't both claim the same SNP name).
    use std::collections::HashMap;
    use std::collections::HashSet;
    let mut taken: HashMap<String, HashSet<String>> = HashMap::new();

    let (mut renamed, mut collided) = (0usize, 0usize);
    let mut tx = pool.begin().await?;
    for node in &nodes {
        let live = match taken.get(&node.dna_label) {
            Some(s) => s.clone(),
            None => {
                let names: HashSet<String> =
                    du_db::haplogroup::live_names(pool, &node.dna_label).await?.into_iter().collect();
                taken.insert(node.dna_label.clone(), names.clone());
                names
            }
        };
        // Qualify bare SNP candidates with the node's major clade (`C106099` → `Q-C106099`)
        // so the stored name matches the `<major>-<SNP>` convention and collisions are
        // checked in the right namespace.
        let candidates = qualify(&node.candidates, &node.current_name);
        let Some(new_name) = pick_name(&candidates, &live) else {
            collided += 1;
            tracing::debug!(node = %node.current_name, cands = ?candidates, "all candidate names collide — skipped");
            continue;
        };
        tracing::info!(id = node.id, from = %node.current_name, to = %new_name, "rename");
        if cfg.apply {
            du_db::haplogroup::rename_with_alias(&mut *tx, node.id, &new_name, &node.current_name).await?;
        }
        taken.get_mut(&node.dna_label).unwrap().insert(new_name);
        renamed += 1;
    }

    let revision = if cfg.apply && renamed > 0 {
        let rev = du_db::tree_revision::bump(&mut *tx).await?;
        tx.commit().await?;
        rev
    } else {
        tx.rollback().await?;
        0
    };

    tracing::info!(
        apply = cfg.apply, candidates = nodes.len(), renamed, collided, revision,
        "name-private-nodes complete{}",
        if cfg.apply { "" } else { " (preview — pass --apply to rename)" }
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cmp::Ordering;
    use std::collections::HashSet;

    #[test]
    fn natural_sort_orders_snp_names() {
        assert_eq!(natural_cmp("C106099", "C114528"), Ordering::Less);
        assert_eq!(natural_cmp("C99", "C100"), Ordering::Less); // numeric, not lexical
        assert_eq!(natural_cmp("C106099", "MF677040"), Ordering::Less); // 'C' < 'M'
        assert_eq!(natural_cmp("A1", "A1"), Ordering::Equal);
        assert_eq!(natural_cmp("BY013", "BY13"), Ordering::Equal); // leading zeros ignored
    }

    #[test]
    fn qualifies_with_major_clade() {
        assert_eq!(major_clade("Q-Z35893:n0"), Some("Q"));
        assert_eq!(major_clade("R-BY195408:n2"), Some("R"));
        assert_eq!(major_clade("Node123:n0"), None); // no major clade → bare name
        let q = qualify(&["C106099".to_string(), "C114528".to_string()], "Q-Z35893:n0");
        assert_eq!(q, vec!["Q-C106099".to_string(), "Q-C114528".to_string()]);
        // natural-sort still picks the lowest SNP once qualified.
        assert_eq!(pick_name(&q, &std::collections::HashSet::new()).as_deref(), Some("Q-C106099"));
    }

    #[test]
    fn picks_lowest_non_colliding() {
        let cands = vec!["C114528".to_string(), "C106099".to_string(), "MF677040".to_string()];
        let none = HashSet::new();
        assert_eq!(pick_name(&cands, &none).as_deref(), Some("C106099"));
        let taken: HashSet<String> = ["C106099".to_string()].into_iter().collect();
        assert_eq!(pick_name(&cands, &taken).as_deref(), Some("C114528"));
        let all: HashSet<String> = cands.iter().cloned().collect();
        assert_eq!(pick_name(&cands, &all), None);
    }
}
