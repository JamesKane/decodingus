//! Server-side layout for the **ancestral-origin icicle** — the genealogical-era companion to
//! `tree_layout`'s cladogram. Design: `proposals/ancestral-origin-icicle.md` §5.
//!
//! The shape is ytree.net's / the Big Tree's: depth runs **down** the page, each branch is a band
//! spanning the horizontal extent of its descendants, and children sit flush beneath their parent
//! so *containment* carries descent and no connector is drawn. A block shows **its equivalent
//! SNPs**, exactly as the Big Tree does: the mutations on that branch are unordered, so the list
//! *is* the block.
//!
//! **Origin is carried by the men, not by the branches.** An early cut tinted each block by the
//! composition of its descendants' origins; it is gone. A branch has no locality of its own — only
//! the men standing on it do — and tinting a whole clade by the modal origin of its subtree
//! asserted something the data does not support. The colour now lives exactly where the claim
//! does: on each man's box, keyed to his own most distant known ancestor.
//!
//! **A block's height is its SNP count, and nothing is elided.** Every equivalent SNP gets a line,
//! so the box's height *is* how long that branch ran unbroken, and vertical position is cumulative:
//! how far down a block sits is the mutations accrued along the path to it.
//!
//! This is not a stylistic choice — sizing blocks by the age model was tried and does not survive
//! contact with the data. `formed_ybp == tmrca_ybp` on **41% of terminal branches and 26.5% of
//! internal ones**, collapsing those branches to a point; on R-DF85 at depth 4 that left **30 of
//! 75 blocks unable to show a single one of their SNPs**, `R-BY18328` getting 3px of span for 9
//! mutations. SNP count never degenerates. And it is still a time axis: measured on this tree,
//! branch length tracks SNP count at **r = 0.975, about 69 years per mutation**.
//!
//! Ages are not discarded — they gate the view to the genealogical era and label each block — they
//! just do not drive geometry, because a per-branch estimate is exactly the thing that is missing
//! or degenerate when a block most needs a height.
//!
//! Pure: no DB, no `Ui`, no template. Every function here is testable without a canvas.

use du_db::origins::SampleOrigin;
use du_db::place::Level;
use std::collections::HashMap;

/// Canvas geometry at scale 1.
const LEAF_W: f64 = 74.0;
const H_GAP: f64 = 4.0;
/// Ruler graduation interval, in mutations.
const TICK_SNPS: usize = 5;
/// Sample tips hang in a band below the youngest branch.
const TIP_H: f64 = 16.0;
/// Narrowest a man's box may be and still carry a readable label. Below it the box is dropped and
/// the man counted instead — a row of 8px slivers hides the composition bar rather than adding to
/// it.
const MIN_TIP_W: f64 = 26.0;
const TIP_GAP: f64 = 10.0;
const GUTTER_W: f64 = 54.0;
const MARGIN: f64 = 8.0;
/// One line of SNP text inside a block.
const SNP_LINE_H: f64 = 11.0;
/// Padding inside a block before its SNP list starts.
const SNP_PAD: f64 = 4.0;
/// The branch-name line at the top of every block.
const NAME_LINE_H: f64 = 12.0;

/// Categorical slots available before folding into "Other". The palette is fixed-order and never
/// cycled; a ninth locality is not given a generated hue.
pub const MAX_SERIES: usize = 8;

/// One locality's share of a clade. `slot` indexes the fixed categorical palette
/// (1..=[`MAX_SERIES`]); `0` is the reserved neutral used for both "Other" and "no locality
/// recorded", which are absences rather than identities and must not wear a categorical hue.
///
/// This is a tally, not a drawn mark: blocks are no longer tinted by composition, so a segment
/// carries no geometry. It feeds the legend and the table, which are what explain the men's
/// colours.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub label: Option<String>,
    pub count: usize,
    pub slot: usize,
    /// Distinguishes the two things slot 0 carries: `true` = "no locality recorded" (an absence),
    /// `false` with no label = "Other" (localities past the palette). They share a colour but not
    /// a meaning, so the legend and tooltips must not call both the same thing.
    pub unknown: bool,
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
    /// The branch's SNP names, placed inside the block. Flowed into columns, and cut to what the
    /// block's height and width can hold — the height means elapsed time, so it is not stretched
    /// to fit a long list.
    pub snps: Vec<SnpCell>,
    /// Total equivalent SNPs on the branch, and how many the block had room for. When they differ
    /// the block says so rather than quietly showing a subset.
    pub snp_total: usize,
    /// True when the band is too short to letter — the view puts its label in the tooltip only.
    pub cramped: bool,
    /// `name` fitted to the band's width. The full name is always in the band's `<title>`.
    pub label: String,
    /// Branches below this one were folded into it by the depth bound. Their men are counted in
    /// this band's composition; their sub-branching is not drawn. The view marks these so a
    /// reader can tell "this branch is simple" from "you are not being shown its shape".
    pub has_more: bool,
}

/// One SNP name placed inside a block.
#[derive(Debug, Clone, PartialEq)]
pub struct SnpCell {
    pub name: String,
    pub x: f64,
    pub y: f64,
}

/// One man, as a leaf below the branch he is placed on.
#[derive(Debug, Clone, PartialEq)]
pub struct Tip {
    /// Fitted to the box. The unabbreviated form is [`Self::full`], shown on hover.
    pub label: String,
    pub full: String,
    pub slot: usize,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// A ruler graduation: mutations accumulated along the lineage to this point.
#[derive(Debug, Clone, PartialEq)]
pub struct Tick {
    pub y: f64,
    pub snps: usize,
}

/// A legend row. Present whenever the chart carries two or more series — identity is never
/// carried by colour alone.
#[derive(Debug, Clone, PartialEq)]
pub struct LegendEntry {
    pub label: Option<String>,
    pub slot: usize,
    pub count: usize,
    /// See [`Segment::unknown`].
    pub unknown: bool,
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
    /// Men whose per-man box was too narrow to letter. They remain in their band's composition;
    /// only the box is gone.
    pub tips_suppressed: usize,
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
    /// The branch's phylogenetically equivalent SNPs. Order is not information — they cannot be
    /// separated — so they are listed alphabetically for a stable render.
    pub snps: Vec<String>,
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
/// Retained nodes, origins re-pointed at visible branches, how many branches were pruned, and
/// which retained branches have folded descendants.
pub struct Pruned {
    pub nodes: Vec<Node>,
    pub origins: Vec<SampleOrigin>,
    pub pruned: usize,
    pub has_more: std::collections::HashSet<i64>,
}

pub fn prune_to_origins(nodes: &[Node], origins: &[SampleOrigin]) -> Pruned {
    let by_id: HashMap<i64, &Node> = nodes.iter().map(|n| (n.id, n)).collect();
    let Some(root) = nodes.iter().find(|n| n.parent_id.is_none()) else {
        return Pruned {
            nodes: Vec::new(),
            origins: Vec::new(),
            pruned: 0,
            has_more: std::collections::HashSet::new(),
        };
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

    // Which retained branches have folded descendants — every retained ancestor of a hidden node.
    // Only *hidden* (depth-folded) nodes count: a branch pruned for carrying no origin adds
    // nothing a reader could drill into.
    let retained_ids: std::collections::HashSet<i64> = retained.iter().map(|n| n.id).collect();
    let mut has_more = std::collections::HashSet::new();
    for n in nodes.iter().filter(|n| n.hidden) {
        let mut at = n.parent_id;
        let mut guard = 0;
        while let Some(id) = at {
            if guard > nodes.len() {
                break;
            }
            if retained_ids.contains(&id) {
                has_more.insert(id);
                break;
            }
            at = by_id.get(&id).and_then(|p| p.parent_id);
            guard += 1;
        }
    }
    Pruned { nodes: retained, origins: moved, pruned, has_more }
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
            unknown: false,
        })
        .collect();
    let with_origin: usize = segs.iter().map(|s| s.count).sum::<usize>() + other;
    if other > 0 {
        segs.push(Segment { label: None, count: other, slot: 0, unknown: false });
    }
    // "No locality recorded" is DRAWN, always last, so absence sits at the same end of every bar
    // and bands can be compared by eye. Leaving it as bare background — which is what happened
    // until a real clade was rendered — made the chart disagree with its own legend, and made a
    // branch whose men are unrecorded look like a branch with fewer men.
    if unknown > 0 {
        segs.push(Segment { label: None, count: unknown, slot: 0, unknown: true });
    }
    (segs, with_origin, unknown)
}

/// Lay the subtree out. `nodes` is the tree window (parents before children is not required);
/// `origins` are the published origins of the placed samples beneath it.
pub fn layout(all_nodes: &[Node], all_origins: &[SampleOrigin], level: Level, placed_total: usize) -> Laid {
    // Attribute origins to visible branches and drop the branches with none beneath them, before
    // anything is measured — see `prune_to_origins`.
    let p = prune_to_origins(all_nodes, all_origins);
    let (pruned, has_more) = (p.pruned, p.has_more);
    let (nodes, origins) = (&p.nodes[..], &p.origins[..]);
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

    // Pass 2 (pre-order): x from the parent's band, y stacked directly beneath it.
    let mut bands = Vec::with_capacity(nodes.len());
    let mut tips = Vec::new();
    let mut left = vec![0.0f64; nodes.len()];
    let mut top = vec![0.0f64; nodes.len()];
    left[root_i] = GUTTER_W;
    top[root_i] = MARGIN;
    let mut stack = vec![root_i];
    let mut deepest = 0.0f64;
    while let Some(i) = stack.pop() {
        let n = &nodes[i];
        let (y, h) = (top[i], block_height(n.snps.len()));
        let dated = n.tmrca_ybp.is_some();
        let (_, with_origin, without_origin) =
            segments_for(comp.get(&n.id).unwrap_or(&HashMap::new()), &slots);

        // One SNP per line, nothing elided — the block is sized to its list, so the list always
        // fits and `snp_total` can never exceed what is drawn.
        let snps = flow_snps(&n.snps, left[i], y, extent[i]);
        bands.push(Band {
            id: n.id,
            label: fit(&n.name, extent[i], 10.0),
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
            snps,
            snp_total: n.snps.len(),
            cramped: h < 14.0,
            has_more: has_more.contains(&n.id),
        });
        deepest = deepest.max(y + h);

        let mut cx = left[i];
        for &c in &children[i] {
            left[c] = cx;
            // Children sit flush beneath their parent: containment carries descent, and vertical
            // position is cumulative mutations along the lineage.
            top[c] = y + h;
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
    let mut tips_suppressed = 0usize;
    for (node_id, mut list) in per_node {
        let Some(&(bx, bw)) = band_x.get(&node_id) else { continue };
        list.sort_by(|a, b| a.sample_guid.cmp(&b.sample_guid));
        let n = list.len() as f64;
        let w = (bw - H_GAP * (n - 1.0).max(0.0)) / n;
        // Below this a tip is a coloured sliver with no legible label — noise that hides the
        // composition bar above it. Those men are still counted in the band; only the per-man box
        // is dropped, and the count is reported.
        if w < MIN_TIP_W {
            tips_suppressed += list.len();
            continue;
        }
        let w = w.min(LEAF_W);
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
                full: label.clone(),
                label: fit(&label, w, 9.0),
                x: bx + k as f64 * (w + H_GAP),
                y: tip_y,
                w,
                h: TIP_H,
            });
        }
    }

    let ticks = ruler(nodes, &bands);
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
        tips_suppressed,
    }
}

/// Height a block needs: its name line plus one line per equivalent SNP.
///
/// **The block is sized to its SNPs, not to a clock.** An earlier cut sized it by elapsed years
/// (parent TMRCA → own TMRCA) and that fails on real data: `formed_ybp == tmrca_ybp` on **41% of
/// terminal branches and 26.5% of internal ones**, collapsing the branch to a point. On R-DF85 at
/// depth 4 that left **30 of 75 blocks unable to show a single one of their SNPs** — `R-BY18328`
/// got 3px of span for 9 mutations. SNP count never degenerates, and because mutations accrue at a
/// roughly steady rate it still reads as elapsed time: measured on this very tree, branch length
/// correlates with SNP count at **r = 0.975**, about **69 years per SNP**.
pub fn block_height(snps: usize) -> f64 {
    2.0 * SNP_PAD + NAME_LINE_H + snps as f64 * SNP_LINE_H
}

/// Place a block's equivalent SNPs: one per line, in order, nothing elided.
///
/// Truncating would shorten the box, and a shortened box misreports how long the branch ran
/// unbroken — the same reason the block is sized to the list rather than the list cut to the box.
fn flow_snps(names: &[String], x: f64, y: f64, w: f64) -> Vec<SnpCell> {
    let top = y + SNP_PAD + NAME_LINE_H;
    names
        .iter()
        .enumerate()
        .map(|(k, name)| SnpCell {
            name: fit(name, w - 2.0 * SNP_PAD, 9.0),
            x: x + SNP_PAD,
            y: top + (k as f64 + 0.8) * SNP_LINE_H,
        })
        .collect()
}

/// Approximate width of one character of the SVG label font, as a fraction of its size. The
/// canvas has no text metrics, so labels are fitted arithmetically; erring narrow would clip text
/// that fits, erring wide lets it spill.
const CHAR_W_RATIO: f64 = 0.55;

/// Truncate a label to what actually fits in `width` at `font_px`, with an ellipsis.
///
/// Necessary because the boxes are sized by the phylogeny, not by the text: a tip is at most
/// [`LEAF_W`] wide and holds ~13 characters, while `Sullivan · Co. Limerick` is 23. Left
/// unfitted, labels ran straight through their neighbours and the tip row became unreadable —
/// visible immediately on a real clade, invisible to a layout test that only checks rectangles.
fn fit(label: &str, width: f64, font_px: f64) -> String {
    let per_char = font_px * CHAR_W_RATIO;
    let budget = ((width - 4.0) / per_char).floor().max(0.0) as usize;
    let chars: Vec<char> = label.chars().collect();
    if chars.len() <= budget {
        return label.to_string();
    }
    if budget <= 1 {
        return String::new();
    }
    chars[..budget - 1].iter().collect::<String>().trim_end().to_string() + "…"
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

/// Graduations down the **deepest lineage** — the one that accrued the most mutations, and so the
/// one that reaches furthest down the canvas.
///
/// Ticks are computed by walking that lineage rather than spaced evenly, because evenly spaced
/// would be wrong: each block spends one line on its name, so a fixed pixels-per-SNP scale drifts
/// by a line per generation. Placing each graduation inside the block that contains it keeps the
/// axis honest — the ticks come out nearly regular, and where they do not, the irregularity is
/// real.
fn ruler(nodes: &[Node], bands: &[Band]) -> Vec<Tick> {
    let index: HashMap<i64, usize> = nodes.iter().enumerate().map(|(i, n)| (n.id, i)).collect();
    let band_of: HashMap<i64, &Band> = bands.iter().map(|b| (b.id, b)).collect();

    // Cumulative mutations to the bottom of each block, so "deepest" means most mutations rather
    // than most generations — one long branch outranks several short ones.
    let mut cum = vec![0usize; nodes.len()];
    let mut best = (0usize, 0usize);
    for (i, n) in nodes.iter().enumerate() {
        let above = n.parent_id.and_then(|p| index.get(&p)).map(|&p| cum[p]).unwrap_or(0);
        cum[i] = above + n.snps.len();
        if cum[i] > best.0 {
            best = (cum[i], i);
        }
    }
    // Walk back up from the deepest block, then read the chain root-first.
    let mut chain = Vec::new();
    let mut at = Some(best.1);
    while let Some(i) = at {
        chain.push(i);
        at = nodes[i].parent_id.and_then(|p| index.get(&p)).copied();
    }
    chain.reverse();

    let mut ticks = Vec::new();
    let mut seen = 0usize;
    for i in chain {
        let n = &nodes[i];
        let Some(b) = band_of.get(&n.id) else { continue };
        let body_top = b.y + SNP_PAD + NAME_LINE_H;
        let mut k = (seen / TICK_SNPS + 1) * TICK_SNPS;
        while k <= seen + n.snps.len() {
            ticks.push(Tick {
                y: body_top + (k - seen) as f64 * SNP_LINE_H,
                snps: k,
            });
            k += TICK_SNPS;
        }
        seen += n.snps.len();
    }
    ticks
}

fn legend_for(root_comp: &HashMap<Option<String>, usize>, slots: &HashMap<String, usize>) -> Vec<LegendEntry> {
    // The legend is exactly the root band's segments — including the drawn "Other" and
    // "no locality recorded" bars, which is what keeps chart and legend from disagreeing.
    segments_for(root_comp, slots)
        .0
        .into_iter()
        .map(|s| LegendEntry { label: s.label, slot: s.slot, count: s.count, unknown: s.unknown })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use du_db::place::{self, PlacePath};
    use uuid::Uuid;

    fn node(id: i64, name: &str, parent: Option<i64>, formed: Option<i32>, tmrca: Option<i32>) -> Node {
        Node {
            id,
            name: name.into(),
            parent_id: parent,
            formed_ybp: formed,
            tmrca_ybp: tmrca,
            hidden: false,
            snps: Vec::new(),
        }
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
        assert_eq!(root.without_origin, 1, "counted, not dropped");

        // And named in the legend, which is where the men's colours are explained. An absence is
        // its own entry — a branch of unrecorded men must not read as a branch with fewer men.
        let absent = laid.legend.iter().find(|e| e.unknown).expect("an unknown legend entry");
        assert_eq!(absent.count, 1);
        assert_eq!(absent.slot, 0, "an absence never wears a categorical hue");
        assert!(laid.legend.last().unwrap().unknown, "absence sorts last");
        // The man himself is drawn in the neutral slot.
        assert!(laid.tips.iter().any(|t| t.slot == 0));
    }

    /// Slot 0 carries two different things. They share a colour but not a meaning, and the legend
    /// must not call both "no locality recorded".
    #[test]
    fn other_and_no_locality_are_distinguishable() {
        let mut comp: HashMap<Option<String>, usize> =
            (0..10).map(|i| (Some(format!("Place {i:02}")), 10 - i)).collect();
        comp.insert(None, 4);
        let slots = assign_slots(&comp);
        let (segs, _, _) = segments_for(&comp, &slots);

        let other = segs.iter().find(|s| s.slot == 0 && !s.unknown).expect("Other");
        let absent = segs.iter().find(|s| s.unknown).expect("no locality recorded");
        assert_eq!(absent.count, 4);
        assert!(other.count > 0 && other.label.is_none());
        assert!(segs.last().unwrap().unknown, "absence is always last");
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
    /// A block's height IS its SNP count — one line per equivalent mutation, nothing elided — and
    /// vertical position is cumulative, so how far down a block sits is the mutations accrued
    /// along the path to it.
    #[test]
    fn block_height_is_its_snp_count_and_position_is_cumulative() {
        let mut nodes = tree();
        nodes[0].snps = (0..3).map(|i| format!("S{i}")).collect();
        nodes[1].snps = (0..9).map(|i| format!("A{i}")).collect();
        nodes[2].snps = (0..2).map(|i| format!("B{i}")).collect();
        let laid = layout(&nodes, &[origin(2, "Ireland"), origin(3, "Scotland")], Level::Country, 2);
        let b = |id: i64| laid.bands.iter().find(|b| b.id == id).unwrap().clone();

        assert_eq!(b(2).h, block_height(9));
        assert_eq!(b(3).h, block_height(2));
        assert!(b(2).h > b(3).h, "nine mutations is a longer branch than two");
        // Siblings share their parent's bottom, so they start level.
        assert!((b(2).y - b(3).y).abs() < 0.01);
        // And each child hangs flush beneath the parent — containment, no connector.
        assert!((b(2).y - (b(1).y + b(1).h)).abs() < 0.01);
    }

    /// The bug that prompted the change. `formed_ybp == tmrca_ybp` on 41% of terminal branches, so
    /// an age-sized block collapsed to nothing while still carrying a full SNP list — 30 of 75
    /// blocks on R-DF85 could not show a single mutation. Height must not depend on that estimate.
    #[test]
    fn a_branch_whose_age_estimate_collapsed_still_shows_every_snp() {
        // R-BY18328's real numbers: formed == tmrca, and its parent is 3 years older.
        let nodes = vec![
            node(1, "R-FT191128", None, Some(906), Some(906)),
            Node { snps: (0..9).map(|i| format!("BY{i}")).collect(), ..node(2, "R-BY18328", Some(1), Some(903), Some(903)) },
        ];
        let laid = layout(&nodes, &[origin(2, "Ireland")], Level::Country, 1);
        let b = laid.bands.iter().find(|b| b.id == 2).unwrap();
        assert_eq!(b.snps.len(), 9, "all nine drawn");
        assert_eq!(b.snp_total, 9);
        assert_eq!(b.h, block_height(9));
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

    /// A branch with no age estimate is still a branch with mutations. Geometry no longer depends
    /// on the age at all, so it draws at full height like any other; `dated` survives only to
    /// label it.
    #[test]
    fn an_undated_branch_still_gets_its_full_height() {
        let mut nodes = tree();
        nodes.push(Node { snps: (0..16).map(|i| format!("BY{i}")).collect(), ..node(4, "R-C", Some(2), None, None) });
        let laid = layout(&nodes, &[origin(4, "Ireland")], Level::Country, 1);
        let c = laid.bands.iter().find(|b| b.id == 4).unwrap();
        assert!(!c.dated, "still flagged as unmeasured");
        assert_eq!(c.h, block_height(16));
        assert_eq!(c.snps.len(), 16, "16 SNPs in an 18px sliver was the bug");
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

    /// A branch has no locality of its own — only the men standing on it do. Blocks were once
    /// tinted by the modal origin of their subtree, which asserted something the data does not
    /// support; the colour now lives only on the men.
    #[test]
    fn blocks_are_never_coloured_by_origin_only_the_men_are() {
        let origins = vec![
            origin(2, "Cork, Co. Cork, Ireland"),
            origin(2, "Cork, Co. Cork, Ireland"),
            origin(3, "Kenmare, Co. Kerry, Ireland"),
            bare(3),
        ];
        let laid = layout(&tree(), &origins, Level::Admin, 4);
        // Every man carries a slot; Cork and Kerry are different colours, the unrecorded man is 0.
        let slots: Vec<usize> = laid.tips.iter().map(|t| t.slot).collect();
        assert_eq!(slots.len(), 4);
        assert!(slots.contains(&0), "the unrecorded man wears the neutral");
        assert!(slots.iter().filter(|&&s| s == 1).count() == 2, "both Cork men share a slot");
        // And the legend still explains those colours, with the counts.
        let cork = laid.legend.iter().find(|e| e.label.as_deref() == Some("Co. Cork")).unwrap();
        assert_eq!(cork.count, 2);
        assert_eq!(laid.legend.iter().find(|e| e.label.as_deref() == Some("Co. Kerry")).unwrap().count, 1);
    }

    /// A block shows its equivalent SNPs — the mutations are unordered, so the list IS the block.
    /// It is cut to what the block holds rather than the block being grown, because the height is
    /// elapsed time and has to stay on the shared axis.
    #[test]
    fn a_block_lists_its_equivalent_snps_and_reports_what_did_not_fit() {
        let mut nodes = tree();
        nodes[1].snps = (0..80).map(|i| format!("FGC{i:05}")).collect();
        nodes[2].snps = vec!["A9185".into(), "BY23498".into()];
        let laid = layout(&nodes, &[origin(2, "Ireland"), origin(3, "Ireland")], Level::Country, 2);

        let short = laid.bands.iter().find(|b| b.id == 3).unwrap();
        assert_eq!(short.snp_total, 2);
        assert_eq!(short.snps.len(), 2, "a short list fits whole");
        assert!(short.snps.iter().any(|c| c.name == "A9185"));

        let long = laid.bands.iter().find(|b| b.id == 2).unwrap();
        assert_eq!(long.snp_total, 80);
        assert_eq!(long.snps.len(), 80, "nothing is elided — the block grows to its list");
        // Every placed name stays inside its block.
        for c in &long.snps {
            assert!(c.x >= long.x - 0.01 && c.x <= long.x + long.w + 0.01);
            assert!(c.y >= long.y - 0.01 && c.y <= long.y + long.h + 0.01);
        }
        // One per line, in order, and every one inside its block.
        assert!(long.snps.windows(2).all(|w| w[1].y > w[0].y));
        assert!(long.snps.iter().all(|c| c.y > long.y && c.y <= long.y + long.h));
        // The list clears the branch-name line. Anchored to the padding instead, every block
        // opened with its name and its first SNP overprinted.
        assert!(long.snps[0].y >= long.y + SNP_PAD + NAME_LINE_H);
    }

    /// A leaf block is exactly `LEAF_W` wide, so its SNP names must be fitted to that, not dropped.
    #[test]
    fn a_leaf_width_block_still_shows_its_snps() {
        let mut nodes = tree();
        nodes[1].snps = vec!["A9185".into(), "BY23498".into(), "FT225347".into()];
        let laid = layout(&nodes, &[origin(2, "Ireland")], Level::Country, 1);
        let leaf = laid.bands.iter().find(|b| b.id == 2).unwrap();
        assert_eq!(leaf.w, LEAF_W, "the narrowest a block gets");
        assert!(!leaf.snps.is_empty(), "its SNPs are drawn, not dropped");
        assert!(leaf.snps.iter().all(|c| c.x >= leaf.x && c.x <= leaf.x + leaf.w));
    }

    /// The reader is told what the chart could not account for.
    #[test]
    fn unresolved_samples_are_reported_against_the_placed_total() {
        let laid = layout(&tree(), &[origin(2, "Ireland")], Level::Country, 17);
        assert_eq!(laid.unresolved, 16, "17 placed, 1 with a published origin");
    }

    /// The ruler counts mutations down the deepest lineage. It is walked rather than spaced
    /// evenly, because each block spends a line on its name and a fixed scale would drift.
    #[test]
    fn the_ruler_counts_mutations_down_the_deepest_lineage() {
        let mut nodes = tree();
        nodes[0].snps = (0..4).map(|i| format!("S{i}")).collect();
        nodes[1].snps = (0..12).map(|i| format!("A{i}")).collect();
        nodes[2].snps = (0..2).map(|i| format!("B{i}")).collect();
        let laid = layout(&nodes, &[origin(2, "Ireland"), origin(3, "Ireland")], Level::Country, 2);

        assert!(!laid.ticks.is_empty());
        assert!(laid.ticks.windows(2).all(|w| w[0].y < w[1].y), "monotone down the page");
        assert!(laid.ticks.windows(2).all(|w| w[1].snps > w[0].snps));
        // Graduations land on multiples of the interval, and stop at the deepest lineage's total
        // (4 + 12 = 16, not 4 + 2).
        assert!(laid.ticks.iter().all(|t| t.snps % TICK_SNPS == 0));
        assert_eq!(laid.ticks.last().unwrap().snps, 15);
    }

    /// A legend is always available for two or more series — identity is never colour alone.
    #[test]
    fn the_legend_covers_every_drawn_series_including_absence() {
        let origins = vec![origin(2, "Cork, Co. Cork, Ireland"), bare(3)];
        let laid = layout(&tree(), &origins, Level::Admin, 2);
        assert!(laid.legend.iter().any(|e| e.label.as_deref() == Some("Co. Cork")));
        assert!(laid.legend.iter().any(|e| e.label.is_none() && e.slot == 0));
    }

    /// Boxes are sized by the phylogeny, not by the text, so labels must be cut to fit. Seen on a
    /// real clade: `Grant · United States` ran straight through its neighbours and the tip row
    /// was unreadable — which the rectangle-only layout assertions could never have caught.
    #[test]
    fn labels_are_fitted_to_their_boxes() {
        assert_eq!(fit("Kane", 74.0, 9.0), "Kane", "what fits is left alone");
        let cut = fit("Sullivan · Co. Limerick", 74.0, 9.0);
        assert!(cut.ends_with('…') && cut.chars().count() < 23);
        assert!(fit("anything", 4.0, 9.0).is_empty(), "no room at all yields no text");

        let laid = layout(&tree(), &[origin(2, "Kenmare, Co. Kerry, Ireland")], Level::Admin, 1);
        let tip = laid.tips.first().expect("one man");
        assert_eq!(tip.full, "Kane · Co. Kerry", "the full label survives for the tooltip");
        assert!(tip.label.chars().count() <= tip.full.chars().count());
        // Every band's drawn label fits the band it sits in.
        for b in &laid.bands {
            assert!((b.label.chars().count() as f64) * 10.0 * CHAR_W_RATIO <= b.w, "{}", b.name);
        }
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

    /// The depth bound is a LEGIBILITY bound, not a data one. Folding a branch must move its men
    /// into the nearest drawn ancestor, so the composition a reader sees is identical at every
    /// depth — only the visible branching changes. R-DF85 drew 266 bands across 11,220px unbounded.
    #[test]
    fn folding_by_depth_preserves_composition_exactly() {
        let deep = vec![
            node(1, "R-Root", None, Some(1600), Some(1400)),
            node(2, "R-Mid", Some(1), Some(1400), Some(1100)),
            node(3, "R-Deep", Some(2), Some(1100), Some(800)),
        ];
        let origins = vec![
            origin(2, "Cork, Co. Cork, Ireland"),
            origin(3, "Kenmare, Co. Kerry, Ireland"),
            origin(3, "Bandon, Co. Cork, Ireland"),
        ];
        let full = layout(&deep, &origins, Level::Admin, 3);

        // Fold everything below R-Mid, exactly as the route does past the display depth.
        let mut folded_nodes = deep.clone();
        folded_nodes[2].hidden = true;
        let folded = layout(&folded_nodes, &origins, Level::Admin, 3);

        let root_of = |l: &Laid| l.bands.iter().find(|b| b.id == 1).unwrap().clone();
        assert_eq!(root_of(&full).with_origin, root_of(&folded).with_origin, "3 men either way");
        // The legend is the root's composition, so it is the thing folding must not change.
        let count = |l: &Laid, name: &str| {
            l.legend.iter().find(|e| e.label.as_deref() == Some(name)).map(|e| e.count)
        };
        assert_eq!(count(&full, "Co. Cork"), Some(2));
        assert_eq!(count(&folded, "Co. Cork"), Some(2), "unchanged by folding");
        assert_eq!(count(&folded, "Co. Kerry"), Some(1));
        // Every man is still accounted for, wherever his branch got folded to. Folding puts all
        // three onto one leaf block, where they no longer each fit a legible box — so they move
        // from `tips` to `tips_suppressed` rather than disappearing.
        assert_eq!(full.tips.len() + full.tips_suppressed, 3);
        assert_eq!(folded.tips.len() + folded.tips_suppressed, 3);
        // R-Deep is gone from the drawing, and its men are now R-Mid's.
        assert!(folded.bands.iter().all(|b| b.id != 3));
        assert_eq!(folded.bands.iter().find(|b| b.id == 2).unwrap().with_origin, 3);
        // And the fold is advertised, so "simple" is never confused with "not shown".
        assert!(folded.bands.iter().find(|b| b.id == 2).unwrap().has_more);
        assert!(!full.bands.iter().find(|b| b.id == 2).unwrap().has_more);
    }

    /// A row of 8px slivers hides the composition bar instead of adding to it. The men stay
    /// counted; only the per-man box goes, and the count is reported.
    #[test]
    fn tips_too_narrow_to_label_are_dropped_and_counted() {
        let crowd: Vec<SampleOrigin> = (0..40).map(|_| origin(2, "Cork, Co. Cork, Ireland")).collect();
        let laid = layout(&tree(), &crowd, Level::Admin, 40);
        assert!(laid.tips.is_empty(), "40 men cannot each hold a legible box");
        assert_eq!(laid.tips_suppressed, 40, "reported, never silent");
        // They are still fully present in the composition.
        assert_eq!(laid.bands.iter().find(|b| b.id == 2).unwrap().with_origin, 40);
        // Every tip that IS drawn is wide enough to letter.
        let few = layout(&tree(), &[origin(2, "Cork, Co. Cork, Ireland")], Level::Admin, 1);
        assert!(few.tips.iter().all(|t| t.w >= MIN_TIP_W));
        assert_eq!(few.tips_suppressed, 0);
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
