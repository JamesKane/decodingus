//! Server-side layout for the **ancestral-origin icicle** — the genealogical-era companion to
//! `tree_layout`'s cladogram. Design: `proposals/ancestral-origin-icicle.md` §5.
//!
//! The shape is ytree.net's / the Big Tree's: depth runs **down** the page, each branch is a band
//! spanning the horizontal extent of its descendants, and children sit flush beneath their parent
//! so *containment* carries descent and no connector is drawn. What differs is the fill — instead
//! of the branch's SNPs, a band is a **stacked composition of where its men's ancestors came
//! from**.
//!
//! **Time is absolute, not cumulative.** A band's top and bottom are dates on one linear axis, so
//! its height *is* its duration; nothing accumulates and nothing drifts.
//!
//! A branch spans **its parent's TMRCA → its own TMRCA**: from the split that brought it into
//! existence as a separate line, to the point it began diversifying itself. That specific pairing
//! is deliberate. The obvious choice — a node's own `formed_ybp` → its own `tmrca_ybp` — draws
//! children *above* their parents on real data: `formed_ybp` and the parent's `tmrca_ybp` are
//! independent point estimates under no monotonicity constraint, and on the live Y tree they are
//! equal on only 898 of 10,252 edges while **4,243 (41%) have the child forming earlier than its
//! parent's split**. Parent-TMRCA → own-TMRCA has zero inversions across the same 10,252 edges, so
//! containment is guaranteed by construction rather than by hope. `formed_ybp` is still reported,
//! on the band itself, where a reader can see the estimate without the geometry depending on it.
//!
//! Undated nodes (16% of sample-bearing nodes) are **not** silently normalized: they hang from
//! their parent at a minimum height and are hatched, so an unmeasured branch never reads as a
//! short one.
//!
//! Pure: no DB, no `Ui`, no template. Every function here is testable without a canvas.

use du_db::origins::SampleOrigin;
use du_db::place::Level;
use std::collections::HashMap;

/// Canvas geometry at scale 1.
const LEAF_W: f64 = 74.0;
const H_GAP: f64 = 4.0;
/// Pixels per year of elapsed time. The genealogical era is ~1,500 years, so this puts a full
/// gated subtree in roughly 900px.
const PX_PER_YEAR: f64 = 0.6;
/// A band never collapses below this, however brief the branch — a 20-year branch must still be
/// clickable and still show its composition.
const MIN_BAND_H: f64 = 18.0;
/// Height given to a band with no age at all. Deliberately equal to the minimum so it cannot be
/// mistaken for a *measured* short branch; the hatch is what distinguishes it.
const UNDATED_H: f64 = MIN_BAND_H;
/// Sample tips hang in a band below the youngest branch.
const TIP_H: f64 = 16.0;
const TIP_GAP: f64 = 10.0;
const GUTTER_W: f64 = 54.0;
const MARGIN: f64 = 8.0;
/// Gap between stacked segments, per the mark spec — segments are separated by surface, not by a
/// stroke.
const SEG_GAP: f64 = 2.0;

/// Categorical slots available before folding into "Other". The palette is fixed-order and never
/// cycled; a ninth locality is not given a generated hue.
pub const MAX_SERIES: usize = 8;

/// One locality's share of a band. `slot` indexes the fixed categorical palette (1..=[`MAX_SERIES`]);
/// `0` is the reserved neutral used for both "Other" and "no locality recorded", which are
/// absences rather than identities and must not wear a categorical hue.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub label: Option<String>,
    pub count: usize,
    pub slot: usize,
    pub x: f64,
    pub w: f64,
}

/// One laid-out branch.
#[derive(Debug, Clone, PartialEq)]
pub struct Band {
    pub id: i64,
    pub name: String,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
    /// False when the branch has no age estimate — rendered hatched, excluded from the ruler.
    pub dated: bool,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    /// Placed samples at or below this branch that carry a published origin.
    pub with_origin: usize,
    /// Placed samples at or below it that do not — always drawn, never omitted.
    pub without_origin: usize,
    pub segments: Vec<Segment>,
    /// True when the band is too short to letter — the view puts its label in the tooltip only.
    pub cramped: bool,
}

/// One man, as a leaf below the branch he is placed on.
#[derive(Debug, Clone, PartialEq)]
pub struct Tip {
    pub label: String,
    pub slot: usize,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// A ruler graduation on the absolute time axis.
#[derive(Debug, Clone, PartialEq)]
pub struct Tick {
    pub y: f64,
    pub ybp: i32,
    /// Calendar-era label (`"1500 CE"`), because the genealogical era reads in calendar years.
    pub label: String,
}

/// A legend row. Present whenever the chart carries two or more series — identity is never
/// carried by colour alone.
#[derive(Debug, Clone, PartialEq)]
pub struct LegendEntry {
    pub label: Option<String>,
    pub slot: usize,
    pub count: usize,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Laid {
    pub width: f64,
    pub height: f64,
    pub bands: Vec<Band>,
    pub tips: Vec<Tip>,
    pub ticks: Vec<Tick>,
    pub legend: Vec<LegendEntry>,
    /// Samples under the root with no published origin — the honest denominator.
    pub unresolved: usize,
    /// Branches dropped because no origin sits beneath them. Reported, never silent: a pruned
    /// chart that looked complete would misrepresent how much of the clade this is.
    pub pruned: usize,
}

/// The minimum a node needs from the tree window. Mirrors `du_db::haplogroup::WindowNode` so this
/// module stays independent of the DB layer and testable with literals.
#[derive(Debug, Clone, PartialEq)]
pub struct Node {
    pub id: i64,
    pub name: String,
    pub parent_id: Option<i64>,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    /// A de-novo auto-named node, which must not surface publicly. It stays in the input so the
    /// ancestor walk is unbroken, and its men are attributed to the nearest named ancestor.
    pub hidden: bool,
}

/// Attribute every origin to the nearest **visible** branch at or above where its sample is
/// placed, and drop the branches that carry no origin at all.
///
/// Both halves fix silent losses found by rendering the real tree:
///
/// - A sample placed on a hidden (de-novo) node, or below the drawn window, used to contribute to
///   **nothing** — its ancestors never saw it, so every band above it understated its own
///   composition. Climbing to the nearest visible ancestor is what the tree's own private-node
///   collapse does for sample tips, applied to composition.
/// - An origins view is about origins: a branch with none beneath it costs a full column of width
///   and says nothing. On a real clade that was 175 bands and a 7,944px canvas for 10 origins.
///   Pruning is reported, never silent.
///
/// Returns the retained nodes (root always kept), the origins re-pointed at visible branches, and
/// how many branches were pruned.
pub fn prune_to_origins(nodes: &[Node], origins: &[SampleOrigin]) -> (Vec<Node>, Vec<SampleOrigin>, usize) {
    let by_id: HashMap<i64, &Node> = nodes.iter().map(|n| (n.id, n)).collect();
    let Some(root) = nodes.iter().find(|n| n.parent_id.is_none()) else {
        return (Vec::new(), Vec::new(), 0);
    };

    // Climb to the nearest visible ancestor. The root is the floor: it is always drawn, so no
    // origin can escape the chart entirely.
    let visible_ancestor = |mut at: i64| -> i64 {
        let mut guard = 0;
        while let Some(n) = by_id.get(&at) {
            if !n.hidden {
                return at;
            }
            match n.parent_id {
                Some(p) if guard < nodes.len() => {
                    at = p;
                    guard += 1;
                }
                _ => break,
            }
        }
        root.id
    };
    let moved: Vec<SampleOrigin> = origins
        .iter()
        .map(|o| SampleOrigin {
            haplogroup_id: match by_id.contains_key(&o.haplogroup_id) {
                true => visible_ancestor(o.haplogroup_id),
                // Placed below the drawn window: attribute to the root rather than lose it.
                false => root.id,
            },
            ..o.clone()
        })
        .collect();

    // Keep a branch when an origin sits at or below it.
    let mut keep: std::collections::HashSet<i64> = std::collections::HashSet::new();
    keep.insert(root.id);
    for o in &moved {
        let mut at = Some(o.haplogroup_id);
        let mut guard = 0;
        while let Some(id) = at {
            if guard > nodes.len() {
                break;
            }
            keep.insert(id);
            at = by_id.get(&id).and_then(|n| n.parent_id);
            guard += 1;
        }
    }
    // Re-parent onto the nearest retained ancestor. Dropping a hidden or origin-less branch must
    // not orphan the branches beneath it — the chain has to stay walkable or the roll-up and the
    // layout both lose everything below the gap.
    let retained_id = |id: i64| -> bool { !by_id[&id].hidden && keep.contains(&id) };
    let retained: Vec<Node> = nodes
        .iter()
        .filter(|n| !n.hidden && keep.contains(&n.id))
        .map(|n| {
            let mut p = n.parent_id;
            let mut guard = 0;
            while let Some(pid) = p {
                if !by_id.contains_key(&pid) || guard > nodes.len() {
                    p = None;
                    break;
                }
                if retained_id(pid) {
                    break;
                }
                p = by_id[&pid].parent_id;
                guard += 1;
            }
            Node { parent_id: p, ..n.clone() }
        })
        .collect();
    let pruned = nodes.iter().filter(|n| !n.hidden).count() - retained.len();
    (retained, moved, pruned)
}

/// Roll each sample's locality up to its branch **and every ancestor of that branch**, so a band's
/// composition is what lies beneath it rather than what sits exactly on it.
///
/// Returns `node id -> (label -> count)`, where `None` is "no locality recorded" — kept as a key
/// rather than dropped, because a branch whose men are mostly unrecorded and a branch whose men
/// are mostly Irish must not look alike.
pub fn roll_up(
    nodes: &[Node],
    origins: &[SampleOrigin],
    level: Level,
) -> HashMap<i64, HashMap<Option<String>, usize>> {
    let parent: HashMap<i64, Option<i64>> = nodes.iter().map(|n| (n.id, n.parent_id)).collect();
    let mut out: HashMap<i64, HashMap<Option<String>, usize>> = HashMap::new();
    for o in origins {
        let label = o.place.label_at(level).map(str::to_string);
        // Climb to the root. A sample outside this window contributes nothing, which is correct:
        // the window is the subtree being drawn.
        let mut at = Some(o.haplogroup_id);
        let mut guard = 0;
        while let Some(id) = at {
            if !parent.contains_key(&id) || guard > nodes.len() {
                break;
            }
            *out.entry(id).or_default().entry(label.clone()).or_default() += 1;
            at = parent[&id];
            guard += 1;
        }
    }
    out
}

/// Assign a palette slot to each locality, **ranked once over the whole subtree** and then held
/// fixed for every band on the page.
///
/// Ranking per band would repaint a locality as you moved down the tree, and ranking per rendered
/// view would repaint the survivors when the reader re-roots — both violate the rule that colour
/// follows the entity, not its position. Ties break on the label so the assignment is
/// deterministic across requests.
///
/// Slot `0` is reserved: it takes "no locality recorded" and everything past [`MAX_SERIES`], which
/// fold together visually as *absence of a named origin* rather than being given invented hues.
pub fn assign_slots(root_composition: &HashMap<Option<String>, usize>) -> HashMap<String, usize> {
    let mut named: Vec<(&String, &usize)> = root_composition
        .iter()
        .filter_map(|(k, v)| k.as_ref().map(|k| (k, v)))
        .collect();
    named.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
    named
        .into_iter()
        .take(MAX_SERIES)
        .enumerate()
        .map(|(i, (label, _))| (label.clone(), i + 1))
        .collect()
}

/// Order a band's composition into drawable segments: named localities by descending count (ties
/// on label), then "other", then "no locality recorded" last so absence always sits at the same
/// end of every bar and the eye can compare bands.
fn segments_for(
    comp: &HashMap<Option<String>, usize>,
    slots: &HashMap<String, usize>,
) -> (Vec<Segment>, usize, usize) {
    let mut named: Vec<(&String, usize)> = Vec::new();
    let mut other = 0usize;
    let mut unknown = 0usize;
    for (label, n) in comp {
        match label {
            None => unknown += *n,
            Some(l) => match slots.get(l) {
                Some(_) => named.push((l, *n)),
                None => other += *n,
            },
        }
    }
    named.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(b.0)));

    let mut segs: Vec<Segment> = named
        .into_iter()
        .map(|(l, n)| Segment {
            slot: slots[l],
            label: Some(l.clone()),
            count: n,
            x: 0.0,
            w: 0.0,
        })
        .collect();
    let with_origin: usize = segs.iter().map(|s| s.count).sum::<usize>() + other;
    if other > 0 {
        segs.push(Segment { label: None, count: other, slot: 0, x: 0.0, w: 0.0 });
    }
    (segs, with_origin, unknown)
}

/// Lay the subtree out. `nodes` is the tree window (parents before children is not required);
/// `origins` are the published origins of the placed samples beneath it.
pub fn layout(all_nodes: &[Node], all_origins: &[SampleOrigin], level: Level, placed_total: usize) -> Laid {
    // Attribute origins to visible branches and drop the branches with none beneath them, before
    // anything is measured — see `prune_to_origins`.
    let (nodes, origins, pruned) = prune_to_origins(all_nodes, all_origins);
    let (nodes, origins) = (&nodes[..], &origins[..]);
    let Some(root) = nodes.iter().find(|n| n.parent_id.is_none()) else {
        return Laid::default();
    };
    let comp = roll_up(nodes, origins, level);
    let root_comp = comp.get(&root.id).cloned().unwrap_or_default();
    let slots = assign_slots(&root_comp);

    let index: HashMap<i64, usize> = nodes.iter().enumerate().map(|(i, n)| (n.id, i)).collect();
    let mut children: Vec<Vec<usize>> = vec![Vec::new(); nodes.len()];
    for (i, n) in nodes.iter().enumerate() {
        if let Some(p) = n.parent_id.and_then(|p| index.get(&p)) {
            children[*p].push(i);
        }
    }
    // Stable draw order: by name, so the same tree lays out the same way on every request.
    for kids in &mut children {
        kids.sort_by(|&a, &b| nodes[a].name.cmp(&nodes[b].name));
    }
    let root_i = index[&root.id];

    // Pass 1 (post-order): horizontal extent each subtree needs.
    let mut extent = vec![LEAF_W; nodes.len()];
    let order = post_order(&children, root_i);
    for &i in &order {
        if children[i].is_empty() {
            continue;
        }
        let kids: f64 = children[i].iter().map(|&c| extent[c]).sum();
        let gaps = H_GAP * (children[i].len() - 1) as f64;
        extent[i] = extent[i].max(kids + gaps);
    }

    // The time axis. The root's formation is the top of the canvas; the present is the bottom, so
    // tips land on "now" and every band sits at its true date.
    let top_ybp = root.formed_ybp.or(root.tmrca_ybp).unwrap_or(0);
    let y_of = |ybp: i32| MARGIN + (top_ybp - ybp).max(0) as f64 * PX_PER_YEAR;
    // A branch begins at its parent's TMRCA — the split that created it. See the module header for
    // why this is not the node's own `formed_ybp`.
    let tmrca_of: HashMap<i64, i32> = nodes.iter().filter_map(|n| Some((n.id, n.tmrca_ybp?))).collect();
    let split_of: HashMap<i64, i32> = nodes
        .iter()
        .filter_map(|n| Some((n.id, *tmrca_of.get(&n.parent_id?)?)))
        .collect();

    // Pass 2 (pre-order): x from the parent's band, y from the node's own dates.
    let mut bands = Vec::with_capacity(nodes.len());
    let mut tips = Vec::new();
    let mut left = vec![0.0f64; nodes.len()];
    let mut fallback_top = vec![0.0f64; nodes.len()];
    left[root_i] = GUTTER_W;
    fallback_top[root_i] = MARGIN;
    let mut stack = vec![root_i];
    let mut deepest = 0.0f64;
    while let Some(i) = stack.pop() {
        let n = &nodes[i];
        // Top: the parent's TMRCA for a child, the node's own formation for the root. Bottom: this
        // node's TMRCA. The pairing is monotone, so `h` can never come out negative.
        let top_ybp_of = split_of.get(&n.id).copied().or(n.formed_ybp);
        let dated = n.tmrca_ybp.is_some();
        let (y, h) = match (top_ybp_of, n.tmrca_ybp) {
            (Some(from), Some(to)) => (y_of(from), (y_of(to) - y_of(from)).max(MIN_BAND_H)),
            // Undated: hang from wherever the parent ended, at the minimum height. Hatched, so it
            // reads as unmeasured rather than brief.
            _ => (fallback_top[i], UNDATED_H),
        };
        let (mut segs, with_origin, without_origin) =
            segments_for(comp.get(&n.id).unwrap_or(&HashMap::new()), &slots);

        // Widths proportional to the composition, with a surface gap between segments.
        let inner = extent[i];
        let total = with_origin + without_origin;
        if total > 0 {
            let gaps = SEG_GAP * segs.len().saturating_sub(1) as f64;
            let usable = (inner - gaps).max(0.0);
            let mut x = left[i];
            for s in &mut segs {
                s.w = usable * (s.count as f64 / total as f64);
                s.x = x;
                x += s.w + SEG_GAP;
            }
        }
        bands.push(Band {
            id: n.id,
            name: n.name.clone(),
            x: left[i],
            y,
            w: extent[i],
            h,
            dated,
            formed_ybp: n.formed_ybp,
            tmrca_ybp: n.tmrca_ybp,
            with_origin,
            without_origin,
            segments: segs,
            cramped: h < 14.0,
        });
        deepest = deepest.max(y + h);

        let mut cx = left[i];
        for &c in &children[i] {
            left[c] = cx;
            fallback_top[c] = y + h;
            cx += extent[c] + H_GAP;
            stack.push(c);
        }
    }

    // Tips: one per sample with a published origin, under the branch it sits on.
    let tip_y = deepest + TIP_GAP;
    let mut per_node: HashMap<i64, Vec<&SampleOrigin>> = HashMap::new();
    for o in origins {
        per_node.entry(o.haplogroup_id).or_default().push(o);
    }
    let band_x: HashMap<i64, (f64, f64)> = bands.iter().map(|b| (b.id, (b.x, b.w))).collect();
    for (node_id, mut list) in per_node {
        let Some(&(bx, bw)) = band_x.get(&node_id) else { continue };
        list.sort_by(|a, b| a.sample_guid.cmp(&b.sample_guid));
        let n = list.len() as f64;
        let w = ((bw - H_GAP * (n - 1.0).max(0.0)) / n).min(LEAF_W).max(8.0);
        for (k, o) in list.iter().enumerate() {
            let locality = o.place.label_at(level);
            let label = match (&o.surname, locality) {
                (Some(s), Some(l)) => format!("{s} · {l}"),
                (Some(s), None) => s.clone(),
                (None, Some(l)) => l.to_string(),
                (None, None) => String::new(),
            };
            tips.push(Tip {
                slot: locality.and_then(|l| slots.get(l).copied()).unwrap_or(0),
                label,
                x: bx + k as f64 * (w + H_GAP),
                y: tip_y,
                w,
                h: TIP_H,
            });
        }
    }

    let ticks = ruler(top_ybp, deepest, &y_of);
    let legend = legend_for(&root_comp, &slots);
    let width = GUTTER_W + extent[root_i] + MARGIN * 2.0;
    let height = tip_y + TIP_H + MARGIN;
    let resolved: usize = root_comp.values().sum();
    Laid {
        width,
        height,
        bands,
        tips,
        ticks,
        legend,
        unresolved: placed_total.saturating_sub(resolved),
        pruned,
    }
}

/// Children before parents, iteratively — the tree is user-shaped and may be deep enough to blow
/// a recursive stack.
fn post_order(children: &[Vec<usize>], root: usize) -> Vec<usize> {
    let mut out = Vec::with_capacity(children.len());
    let mut stack = vec![root];
    while let Some(i) = stack.pop() {
        out.push(i);
        stack.extend(children[i].iter().copied());
    }
    out.reverse();
    out
}

/// Graduations at a round interval chosen so the axis carries roughly 6–10 of them.
fn ruler(top_ybp: i32, bottom_px: f64, y_of: &dyn Fn(i32) -> f64) -> Vec<Tick> {
    if top_ybp <= 0 {
        return Vec::new();
    }
    let step = [50, 100, 200, 250, 500, 1000, 2000, 5000]
        .into_iter()
        .find(|s| top_ybp / s <= 10)
        .unwrap_or(10_000);
    let mut ticks = Vec::new();
    let mut ybp = (top_ybp / step) * step;
    while ybp >= 0 {
        let y = y_of(ybp);
        if y <= bottom_px + 1.0 {
            ticks.push(Tick { y, ybp, label: era_label(ybp) });
        }
        ybp -= step;
    }
    ticks
}

/// `ybp` → a calendar-era label. The genealogical era is read in calendar years, not in years
/// before present, and 1950 is the radiocarbon reference the rest of the tree uses.
fn era_label(ybp: i32) -> String {
    let year = 1950 - ybp;
    if year > 0 {
        format!("{year} CE")
    } else {
        format!("{} BCE", 1 - year)
    }
}

fn legend_for(root_comp: &HashMap<Option<String>, usize>, slots: &HashMap<String, usize>) -> Vec<LegendEntry> {
    let (segs, _, unknown) = segments_for(root_comp, slots);
    let mut out: Vec<LegendEntry> = segs
        .into_iter()
        .map(|s| LegendEntry { label: s.label, slot: s.slot, count: s.count })
        .collect();
    if unknown > 0 {
        out.push(LegendEntry { label: None, slot: 0, count: unknown });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use du_db::place::{self, PlacePath};
    use uuid::Uuid;

    fn node(id: i64, name: &str, parent: Option<i64>, formed: Option<i32>, tmrca: Option<i32>) -> Node {
        Node { id, name: name.into(), parent_id: parent, formed_ybp: formed, tmrca_ybp: tmrca, hidden: false }
    }

    fn hidden(id: i64, name: &str, parent: Option<i64>, formed: Option<i32>, tmrca: Option<i32>) -> Node {
        Node { hidden: true, ..node(id, name, parent, formed, tmrca) }
    }

    fn origin(node_id: i64, place: &str) -> SampleOrigin {
        SampleOrigin {
            sample_guid: Uuid::new_v4(),
            haplogroup_id: node_id,
            surname: Some("Kane".into()),
            place: place::normalize(Some(place), None),
            birth_year: Some(1830),
        }
    }

    fn bare(node_id: i64) -> SampleOrigin {
        SampleOrigin {
            sample_guid: Uuid::new_v4(),
            haplogroup_id: node_id,
            surname: Some("Walsh".into()),
            place: PlacePath::default(),
            birth_year: None,
        }
    }

    fn tree() -> Vec<Node> {
        vec![
            node(1, "R-S764", None, Some(1600), Some(1355)),
            node(2, "R-A", Some(1), Some(1355), Some(800)),
            node(3, "R-B", Some(1), Some(1355), Some(600)),
        ]
    }

    /// A band shows what lies *beneath* it, not what sits exactly on it — otherwise every interior
    /// branch would read as empty.
    #[test]
    fn composition_rolls_up_to_every_ancestor() {
        let origins = vec![origin(2, "Cork, Co. Cork, Ireland"), origin(3, "Kenmare, Co. Kerry, Ireland")];
        let comp = roll_up(&tree(), &origins, Level::Admin);
        assert_eq!(comp[&1].values().sum::<usize>(), 2, "root sees both");
        assert_eq!(comp[&2][&Some("Co. Cork".into())], 1);
        assert_eq!(comp[&3][&Some("Co. Kerry".into())], 1);
        assert!(!comp[&2].contains_key(&Some("Co. Kerry".into())), "a sibling's origin is not borrowed");
    }

    /// "No locality recorded" is a key, not a dropped row: a branch whose men are unrecorded and a
    /// branch whose men are Irish must not look alike.
    #[test]
    fn samples_without_a_locality_are_counted_not_dropped() {
        let origins = vec![origin(2, "Cork, Co. Cork, Ireland"), bare(2)];
        let comp = roll_up(&tree(), &origins, Level::Admin);
        assert_eq!(comp[&2][&None], 1);
        assert_eq!(comp[&2].values().sum::<usize>(), 2);

        let laid = layout(&tree(), &origins, Level::Admin, 2);
        let root = laid.bands.iter().find(|b| b.id == 1).unwrap();
        assert_eq!(root.with_origin, 1);
        assert_eq!(root.without_origin, 1, "drawn, never omitted");
    }

    /// Colour follows the entity. Ranking is done once over the root and held, so drilling into a
    /// child cannot repaint a locality that survived.
    #[test]
    fn slots_are_stable_and_rank_by_count_then_name() {
        let mut comp = HashMap::new();
        comp.insert(Some("Co. Cork".to_string()), 5);
        comp.insert(Some("Co. Kerry".to_string()), 2);
        comp.insert(Some("Co. Clare".to_string()), 2); // ties Kerry — name breaks it
        comp.insert(None, 9); // the unknown pile never takes a categorical slot
        let slots = assign_slots(&comp);
        assert_eq!(slots["Co. Cork"], 1);
        assert_eq!(slots["Co. Clare"], 2, "tie broken on label, deterministically");
        assert_eq!(slots["Co. Kerry"], 3);
        assert_eq!(slots.len(), 3, "None is not assigned a hue");
    }

    /// A ninth locality is not given an invented hue — it folds into the reserved neutral.
    #[test]
    fn past_eight_localities_fold_into_the_reserved_slot() {
        let comp: HashMap<Option<String>, usize> =
            (0..12).map(|i| (Some(format!("Place {i:02}")), 12 - i)).collect();
        let slots = assign_slots(&comp);
        assert_eq!(slots.len(), MAX_SERIES);
        assert!(slots.values().all(|&s| (1..=MAX_SERIES).contains(&s)));

        let (segs, with_origin, _) = segments_for(&comp, &slots);
        let folded = segs.iter().find(|s| s.slot == 0).expect("an Other segment exists");
        assert_eq!(folded.label, None);
        assert_eq!(with_origin, comp.values().sum::<usize>());
    }

    /// Height is duration on one absolute axis: a branch that ran twice as long is twice as tall,
    /// wherever it sits in the tree.
    #[test]
    fn band_height_is_elapsed_time_on_an_absolute_axis() {
        let laid = layout(&tree(), &[origin(2, "Ireland"), origin(3, "Scotland")], Level::Country, 2);
        let b = |id: i64| laid.bands.iter().find(|b| b.id == id).unwrap().clone();
        // Both children begin at the parent's split (1355) and run to their own TMRCA.
        assert!((b(2).h - 555.0 * PX_PER_YEAR).abs() < 0.01, "1355→800");
        assert!((b(3).h - 755.0 * PX_PER_YEAR).abs() < 0.01, "1355→600");
        // Siblings share that split, so their tops align exactly.
        assert!((b(2).y - b(3).y).abs() < 0.01);
        // And each child starts where the parent's diversification did.
        assert!((b(2).y - (b(1).y + b(1).h)).abs() < 0.01);
    }

    /// The bug that rendering the real tree exposed. `formed_ybp` and the parent's `tmrca_ybp` are
    /// independent estimates under no monotonicity constraint: on the live Y tree 4,243 of 10,252
    /// edges have a child forming *earlier* than its parent's split. Driving the geometry from
    /// `formed_ybp` drew those children on top of their parents — R-A13318 landed at exactly its
    /// parent R-S764's y, at the top of the canvas.
    ///
    /// Spanning parent-TMRCA → own-TMRCA cannot do that, whatever `formed_ybp` says.
    #[test]
    fn a_child_forming_before_its_parents_split_still_nests() {
        // R-S764 / R-A13318's real numbers.
        let nodes = vec![
            node(1, "R-S764", None, Some(1620), Some(1355)),
            node(2, "R-A13318", Some(1), Some(1622), Some(1355)), // formed 2 yrs BEFORE the parent
        ];
        let laid = layout(&nodes, &[origin(2, "Ireland")], Level::Country, 1);
        let root = laid.bands.iter().find(|b| b.id == 1).unwrap();
        let child = laid.bands.iter().find(|b| b.id == 2).unwrap();

        assert!(child.y >= root.y + root.h - 0.01, "the child begins at or below the parent's split");
        assert!(child.h >= 0.0, "and never inverts into a negative height");
        assert!(child.y > root.y, "it is not drawn on top of its parent");
    }

    /// The invariant, stated once over an awkward tree: no band may start above its parent's end.
    #[test]
    fn no_band_ever_starts_above_its_parent() {
        let nodes = vec![
            node(1, "R-Root", None, Some(2000), Some(1500)),
            node(2, "R-Early", Some(1), Some(1900), Some(1200)), // formed long before the split
            node(3, "R-Late", Some(1), Some(1400), Some(900)),
            node(4, "R-Deep", Some(2), Some(1800), Some(400)),   // ditto, one level down
        ];
        let origins: Vec<_> = [2, 3, 4].iter().map(|&id| origin(id, "Ireland")).collect();
        let laid = layout(&nodes, &origins, Level::Country, 3);
        let by_id: HashMap<i64, &Band> = laid.bands.iter().map(|b| (b.id, b)).collect();
        for n in &nodes {
            let (Some(b), Some(p)) = (by_id.get(&n.id), n.parent_id.and_then(|p| by_id.get(&p))) else {
                continue;
            };
            assert!(b.y >= p.y + p.h - 0.01, "{} starts above its parent", n.name);
        }
    }

    /// An unmeasured branch must not read as a short one.
    #[test]
    fn an_undated_branch_is_hatched_at_the_minimum_height() {
        let mut nodes = tree();
        nodes.push(node(4, "R-C", Some(2), None, None));
        let laid = layout(&nodes, &[origin(4, "Ireland")], Level::Country, 1);
        let c = laid.bands.iter().find(|b| b.id == 4).unwrap();
        assert!(!c.dated);
        assert_eq!(c.h, UNDATED_H);
        // It hangs off its parent rather than floating at the top of the canvas.
        let parent = laid.bands.iter().find(|b| b.id == 2).unwrap();
        assert!((c.y - (parent.y + parent.h)).abs() < 0.01);
    }

    /// Containment carries descent: a parent spans its children, and siblings never overlap.
    #[test]
    fn a_parent_spans_its_children_and_siblings_do_not_overlap() {
        let laid = layout(&tree(), &[origin(2, "Ireland"), origin(3, "Scotland")], Level::Country, 2);
        let b = |id: i64| laid.bands.iter().find(|b| b.id == id).unwrap().clone();
        let (root, a, bb) = (b(1), b(2), b(3));
        assert!(a.x >= root.x && a.x + a.w <= root.x + root.w + 0.01);
        assert!(bb.x >= root.x && bb.x + bb.w <= root.x + root.w + 0.01);
        assert!(a.x + a.w <= bb.x + 0.01, "siblings are disjoint");
    }

    /// Segment widths are proportional and stay inside the band, gaps included.
    #[test]
    fn segments_are_proportional_and_stay_within_the_band() {
        let origins = vec![
            origin(2, "Cork, Co. Cork, Ireland"),
            origin(2, "Cork, Co. Cork, Ireland"),
            origin(3, "Kenmare, Co. Kerry, Ireland"),
            bare(3),
        ];
        let laid = layout(&tree(), &origins, Level::Admin, 4);
        let root = laid.bands.iter().find(|b| b.id == 1).unwrap();
        let cork = root.segments.iter().find(|s| s.label.as_deref() == Some("Co. Cork")).unwrap();
        let kerry = root.segments.iter().find(|s| s.label.as_deref() == Some("Co. Kerry")).unwrap();
        assert!((cork.w / kerry.w - 2.0).abs() < 0.01, "2 Cork to 1 Kerry");
        for s in &root.segments {
            assert!(s.x >= root.x - 0.01 && s.x + s.w <= root.x + root.w + 0.01);
        }
    }

    /// The reader is told what the chart could not account for.
    #[test]
    fn unresolved_samples_are_reported_against_the_placed_total() {
        let laid = layout(&tree(), &[origin(2, "Ireland")], Level::Country, 17);
        assert_eq!(laid.unresolved, 16, "17 placed, 1 with a published origin");
    }

    #[test]
    fn the_ruler_reads_in_calendar_years() {
        let laid = layout(&tree(), &[], Level::Country, 0);
        assert!(!laid.ticks.is_empty());
        assert!(laid.ticks.windows(2).all(|w| w[0].y < w[1].y), "monotone down the page");
        assert_eq!(era_label(1600), "350 CE");
        assert_eq!(era_label(0), "1950 CE");
        assert_eq!(era_label(2000), "51 BCE");
    }

    /// A legend is always available for two or more series — identity is never colour alone.
    #[test]
    fn the_legend_covers_every_drawn_series_including_absence() {
        let origins = vec![origin(2, "Cork, Co. Cork, Ireland"), bare(3)];
        let laid = layout(&tree(), &origins, Level::Admin, 2);
        assert!(laid.legend.iter().any(|e| e.label.as_deref() == Some("Co. Cork")));
        assert!(laid.legend.iter().any(|e| e.label.is_none() && e.slot == 0));
    }

    #[test]
    fn an_empty_window_lays_out_to_nothing_rather_than_panicking() {
        assert_eq!(layout(&[], &[], Level::Country, 0), Laid::default());
    }

    /// Found by rendering the real tree: a clade drew 175 bands across 7,944px to show 10
    /// origins. A branch with none beneath it is all width and no information.
    #[test]
    fn branches_with_no_origin_beneath_them_are_pruned_and_counted() {
        let mut nodes = tree();
        for id in 10..20 {
            nodes.push(node(id, &format!("R-Empty{id}"), Some(3), Some(600), Some(400)));
        }
        let laid = layout(&nodes, &[origin(2, "Ireland")], Level::Country, 1);
        // Root + the one branch carrying the origin. R-B and its ten empty children are gone.
        assert_eq!(laid.bands.len(), 2);
        assert!(laid.bands.iter().all(|b| b.id == 1 || b.id == 2));
        assert_eq!(laid.pruned, 11, "reported, never silent");
        assert!(laid.width < 200.0, "canvas follows the data, not the tree");
    }

    /// Also found by rendering: a sample on a de-novo node contributed to *nothing*, so every
    /// band above it understated itself. It must be attributed to the nearest named branch.
    #[test]
    fn a_sample_on_a_hidden_node_is_attributed_to_the_nearest_named_branch() {
        let mut nodes = tree();
        nodes.push(hidden(9, "R-(hs1)chrY:2561207 TA->T", Some(2), Some(800), Some(400)));
        let laid = layout(&nodes, &[origin(9, "Cork, Co. Cork, Ireland")], Level::Admin, 1);

        assert!(laid.bands.iter().all(|b| b.id != 9), "the de-novo node never surfaces");
        let named = laid.bands.iter().find(|b| b.id == 2).expect("its named parent is drawn");
        assert_eq!(named.with_origin, 1, "its man is counted here, not lost");
        let root = laid.bands.iter().find(|b| b.id == 1).unwrap();
        assert_eq!(root.with_origin, 1, "and still rolls up to the root");
    }

    /// A sample placed deeper than the walk still has to land somewhere, or the chart quietly
    /// undercounts.
    #[test]
    fn a_sample_below_the_window_falls_back_to_the_root() {
        let laid = layout(&tree(), &[origin(999, "Ireland")], Level::Country, 1);
        let root = laid.bands.iter().find(|b| b.id == 1).unwrap();
        assert_eq!(root.with_origin, 1);
    }

    /// Dropping a branch must not orphan what hangs beneath it.
    #[test]
    fn pruning_reparents_rather_than_orphaning_descendants() {
        let mut nodes = tree();
        nodes.push(hidden(9, "R-(hs1)chrY:99", Some(3), Some(600), Some(500)));
        nodes.push(node(10, "R-Deep", Some(9), Some(500), Some(300)));
        let laid = layout(&nodes, &[origin(10, "Ireland")], Level::Country, 1);

        let deep = laid.bands.iter().find(|b| b.id == 10).expect("kept: it carries an origin");
        // Its hidden parent is gone, so it must now hang off R-B, not float free.
        let b3 = laid.bands.iter().find(|b| b.id == 3).unwrap();
        assert!(deep.x >= b3.x && deep.x + deep.w <= b3.x + b3.w + 0.01);
        // And it sits below R-B on the time axis rather than at the top of the canvas.
        assert!(deep.y >= b3.y + b3.h - 0.01);
    }
}
