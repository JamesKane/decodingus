//! Server-side phylogenetic-tree layout — the Rust port of the legacy Scala
//! `TreeLayoutService`. Given a (depth-windowed) nested haplogroup tree and an
//! orientation, it computes pixel-ready node boxes and right-angle SVG connector
//! paths so the templates can render an inline `<svg>` cladogram with no
//! client-side layout library.
//!
//! Two orientations mirror the two Scala render modes:
//!   * **Horizontal** — depth runs left→right, breadth top→bottom.
//!   * **Vertical**   — depth runs top→bottom, breadth left→right.
//!
//! Unlike the Scala engine we do *not* collapse non-backbone branches: the
//! caller already bounds the tree to a fixed depth window, so we lay out exactly
//! what we are given. Backbone / recently-updated remain as node *coloring*.

/// 1950 — the radiocarbon "before present" reference year, for ybp→calendar.
const PRESENT_YEAR: i32 = 1950;

const NODE_WIDTH: f64 = 150.0;
const NODE_HEIGHT: f64 = 80.0;
const MARGIN_TOP: f64 = 50.0;
const MARGIN_LEFT: f64 = 120.0;
/// Sample-tip marker radius and label offset.
const TIP_R: f64 = 4.0;
const TIP_LABEL_DX: f64 = 8.0;
/// Approx. label width reserved past a tip marker (for canvas sizing).
const TIP_LABEL_W: f64 = 130.0;

/// Tree render orientation; persisted in the `tree_orient` cookie.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Orientation {
    Horizontal,
    Vertical,
}

impl Orientation {
    /// `"v"`/`"vertical"` → Vertical, anything else → Horizontal.
    pub fn parse(s: &str) -> Orientation {
        match s.trim().to_ascii_lowercase().as_str() {
            "v" | "vertical" | "true" => Orientation::Vertical,
            _ => Orientation::Horizontal,
        }
    }
    pub fn code(self) -> &'static str {
        match self {
            Orientation::Horizontal => "h",
            Orientation::Vertical => "v",
        }
    }
    pub fn is_vertical(self) -> bool {
        self == Orientation::Vertical
    }
    /// (depth spacing, breadth spacing) for this orientation.
    fn spacing(self) -> (f64, f64) {
        match self {
            Orientation::Horizontal => (200.0, 90.0),
            Orientation::Vertical => (130.0, 180.0),
        }
    }
}

/// Input node fed to the layout engine (nested).
#[derive(Debug, Clone)]
pub struct InNode {
    pub name: String,
    pub variant_count: i64,
    /// Placed-sample leaf labels to hang under this node (capped; YFull-style tips).
    pub samples: Vec<String>,
    /// How many more placed samples beyond `samples` (renders a "+N" overflow tip).
    pub sample_overflow: i64,
    pub is_backbone: bool,
    pub is_recent: bool,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    /// Window-boundary node with clipped children — show a "+" affordance.
    pub has_hidden: bool,
    pub children: Vec<InNode>,
}

/// A laid-out node box, with pixel coordinates ready for the SVG template.
#[derive(Debug, Clone)]
pub struct LaidNode {
    pub name: String,
    pub variant_count: i64,
    /// CSS class selecting the fill: backbone / recent / default.
    pub fill_class: &'static str,
    pub is_backbone: bool,
    pub is_recent: bool,
    pub has_hidden: bool,
    /// Formatted calendar year (e.g. "2400 BC"), if an age is set.
    pub formed: Option<String>,
    pub tmrca: Option<String>,
    /// Top-left of the 150×80 box.
    pub rect_x: f64,
    pub rect_y: f64,
    /// Box center (text anchor X).
    pub cx: f64,
    pub cy: f64,
    /// Pre-computed text-baseline Y positions (template stays arithmetic-free).
    /// The name/count/formed/tmrca lines are vertically centered as a block, so a
    /// node with no ages stays centered while one with both shows four tidy rows.
    pub name_y: f64,
    pub count_y: f64,
    pub formed_y: f64,
    pub tmrca_y: f64,
}

/// A placed sample rendered as a minimal terminal tip hanging off its haplogroup node.
#[derive(Debug, Clone)]
pub struct LaidTip {
    /// The sample label (accession/alias) or, for overflow, `+N`.
    pub label: String,
    /// Tip marker center.
    pub cx: f64,
    pub cy: f64,
    /// Baseline for the label text (to the right of the marker).
    pub label_x: f64,
    /// When set, this is the `+N` overflow tip; opens the node's sidebar (the haplogroup name).
    pub overflow_node: Option<String>,
}

/// An SVG connector path between a parent and one child.
#[derive(Debug, Clone)]
pub struct Link {
    pub path: String,
}

/// The full laid-out tree.
#[derive(Debug, Clone)]
pub struct Laid {
    pub nodes: Vec<LaidNode>,
    /// Placed-sample leaf tips (rendered minimal + distinct from the haplogroup boxes).
    pub tips: Vec<LaidTip>,
    pub links: Vec<Link>,
    pub width: f64,
    pub height: f64,
}

/// ybp → "<year> AD" / "<year> BC" (mirrors the Scala `formatYbp`).
fn format_ybp(ybp: i32) -> String {
    let year = PRESENT_YEAR - ybp;
    if year < 0 {
        format!("{} BC", -year)
    } else {
        format!("{year} AD")
    }
}

fn fill_class(is_backbone: bool, is_recent: bool) -> &'static str {
    if is_backbone {
        "node-backbone"
    } else if is_recent {
        "node-recent"
    } else {
        "node-default"
    }
}

/// Internal placement of a node: its breadth/depth position in the abstract
/// (depth, breadth) plane before mapping to SVG x/y.
struct Placement {
    breadth: f64,
    depth: f64,
}

struct Builder {
    orientation: Orientation,
    depth_spacing: f64,
    breadth_spacing: f64,
    nodes: Vec<LaidNode>,
    tips: Vec<LaidTip>,
    links: Vec<Link>,
}

impl Builder {
    /// Map an abstract (depth, breadth) position to an SVG box center.
    fn center(&self, depth: f64, breadth: f64) -> (f64, f64) {
        match self.orientation {
            // depth → X, breadth → Y
            Orientation::Horizontal => (depth, breadth),
            // breadth → X, depth → Y
            Orientation::Vertical => (breadth, depth),
        }
    }

    /// Returns the node's placement; `breadth_cursor` is advanced past the
    /// node's subtree.
    fn place(&mut self, node: &InNode, depth: usize, breadth_cursor: &mut f64) -> Placement {
        let depth_pos = depth as f64 * self.depth_spacing
            + if self.orientation == Orientation::Horizontal { MARGIN_LEFT } else { MARGIN_TOP };

        // Lay out children first so a parent can center over them.
        let child_placements: Vec<Placement> = node
            .children
            .iter()
            .map(|c| self.place(c, depth + 1, breadth_cursor))
            .collect();

        // Lay out the node's placed samples as leaf tips one depth-step out (after the subclade
        // children in breadth). Each reserves a **full node slot** — sample tips are spaced like
        // any other leaf so their labels never collide. A trailing "+N" tip stands in for the
        // overflow and opens the node's sidebar.
        let tip_depth = (depth + 1) as f64 * self.depth_spacing
            + if self.orientation == Orientation::Horizontal { MARGIN_LEFT } else { MARGIN_TOP };
        let mut tip_breadths: Vec<f64> = Vec::new();
        let mut tip_labels: Vec<(String, Option<String>)> =
            node.samples.iter().map(|s| (s.clone(), None)).collect();
        if node.sample_overflow > 0 {
            tip_labels.push((format!("+{}", node.sample_overflow), Some(node.name.clone())));
        }
        for (label, overflow_node) in tip_labels {
            let b = *breadth_cursor;
            *breadth_cursor += self.breadth_spacing;
            let (cx, cy) = self.center(tip_depth, b);
            self.tips.push(LaidTip { label, cx, cy, label_x: cx + TIP_LABEL_DX, overflow_node });
            tip_breadths.push(b);
        }

        // Center the node over its children *and* its sample tips (tips are leaves too).
        let mut spans: Vec<f64> = child_placements.iter().map(|p| p.breadth).collect();
        spans.extend(tip_breadths.iter().copied());
        let breadth_pos = match (spans.first(), spans.last()) {
            (Some(first), Some(last)) => (first + last) / 2.0,
            _ => {
                // True leaf (no children, no samples): take the cursor, then advance it.
                let b = *breadth_cursor;
                *breadth_cursor += self.breadth_spacing;
                b
            }
        };

        let (cx, cy) = self.center(depth_pos, breadth_pos);

        // Vertically center the text block: name + variants are always present;
        // the formed / TMRCA age lines are added only when set. Baselines are laid
        // out on a shared line-height and centered on `cy`, so an age-less node
        // looks balanced and a node with both ages fills the box with four rows.
        let formed = node.formed_ybp.map(format_ybp);
        let tmrca = node.tmrca_ybp.map(format_ybp);
        const LINE_H: f64 = 17.0;
        const BASELINE_NUDGE: f64 = 4.0; // baseline → visual-center correction
        let lines = 2 + formed.is_some() as i32 + tmrca.is_some() as i32;
        let line_y = |row: i32| cy + (row as f64 - (lines as f64 - 1.0) / 2.0) * LINE_H + BASELINE_NUDGE;
        let name_y = line_y(0);
        let count_y = line_y(1);
        // Ages take the next rows in order; an unset age's baseline is unused.
        let formed_y = line_y(2);
        let tmrca_y = line_y(2 + formed.is_some() as i32);

        self.nodes.push(LaidNode {
            name: node.name.clone(),
            variant_count: node.variant_count,
            fill_class: fill_class(node.is_backbone, node.is_recent),
            is_backbone: node.is_backbone,
            is_recent: node.is_recent,
            has_hidden: node.has_hidden,
            formed,
            tmrca,
            rect_x: cx - NODE_WIDTH / 2.0,
            rect_y: cy - NODE_HEIGHT / 2.0,
            cx,
            cy,
            name_y,
            count_y,
            formed_y,
            tmrca_y,
        });

        // Connectors from this node to each child and to each sample tip.
        for child in &child_placements {
            self.links.push(Link {
                path: self.link_path(depth_pos, breadth_pos, child.depth, child.breadth),
            });
        }
        for &tb in &tip_breadths {
            self.links.push(Link { path: self.tip_link_path(depth_pos, breadth_pos, tip_depth, tb) });
        }

        Placement { breadth: breadth_pos, depth: depth_pos }
    }

    /// Connector from a node to one of its sample tips. The vertical bus is placed at the
    /// **same** position as the node's child connectors (midpoint to the box edge), then the
    /// final stub continues out to the tip marker — so a node's subclade and sample links share
    /// one clean bus instead of two misaligned bars.
    fn tip_link_path(&self, p_depth: f64, p_breadth: f64, c_depth: f64, c_breadth: f64) -> String {
        match self.orientation {
            Orientation::Horizontal => {
                let sx = p_depth + NODE_WIDTH / 2.0;
                let bus = (sx + (c_depth - NODE_WIDTH / 2.0)) / 2.0;
                let tx = c_depth - TIP_R;
                format!("M {sx:.1} {p_breadth:.1} H {bus:.1} V {c_breadth:.1} H {tx:.1}")
            }
            Orientation::Vertical => {
                let sy = p_depth + NODE_HEIGHT / 2.0;
                let bus = (sy + (c_depth - NODE_HEIGHT / 2.0)) / 2.0;
                let ty = c_depth - TIP_R;
                format!("M {p_breadth:.1} {sy:.1} V {bus:.1} H {c_breadth:.1} V {ty:.1}")
            }
        }
    }

    /// Right-angle connector from a parent (depth/breadth) to a child, in the
    /// orientation's SVG coordinate space.
    fn link_path(&self, p_depth: f64, p_breadth: f64, c_depth: f64, c_breadth: f64) -> String {
        match self.orientation {
            Orientation::Horizontal => {
                let sx = p_depth + NODE_WIDTH / 2.0;
                let tx = c_depth - NODE_WIDTH / 2.0;
                let mid = (sx + tx) / 2.0;
                format!("M {sx:.1} {p_breadth:.1} H {mid:.1} V {c_breadth:.1} H {tx:.1}")
            }
            Orientation::Vertical => {
                let sy = p_depth + NODE_HEIGHT / 2.0;
                let ty = c_depth - NODE_HEIGHT / 2.0;
                let mid = (sy + ty) / 2.0;
                format!("M {p_breadth:.1} {sy:.1} V {mid:.1} H {c_breadth:.1} V {ty:.1}")
            }
        }
    }
}

/// Lay out the given tree for the orientation. Returns `None` if `root` is None.
pub fn layout(root: Option<&InNode>, orientation: Orientation) -> Option<Laid> {
    let root = root?;
    let (depth_spacing, breadth_spacing) = orientation.spacing();
    let initial_breadth = if orientation == Orientation::Horizontal { MARGIN_TOP } else { MARGIN_LEFT };
    let mut b = Builder {
        orientation,
        depth_spacing,
        breadth_spacing,
        nodes: Vec::new(),
        tips: Vec::new(),
        links: Vec::new(),
    };
    let mut cursor = initial_breadth;
    b.place(root, 0, &mut cursor);

    let node_cx = b.nodes.iter().map(|n| n.cx).fold(0.0_f64, f64::max);
    let tip_cx = b.tips.iter().map(|t| t.label_x + TIP_LABEL_W).fold(0.0_f64, f64::max);
    let max_cx = node_cx.max(tip_cx);
    let node_cy = b.nodes.iter().map(|n| n.cy).fold(0.0_f64, f64::max);
    let tip_cy = b.tips.iter().map(|t| t.cy).fold(0.0_f64, f64::max);
    let max_cy = node_cy.max(tip_cy);
    let width = max_cx + NODE_WIDTH / 2.0 + MARGIN_LEFT;
    let height = max_cy + NODE_HEIGHT / 2.0 + MARGIN_TOP;

    Some(Laid { nodes: b.nodes, tips: b.tips, links: b.links, width, height })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn leaf(name: &str) -> InNode {
        InNode {
            name: name.into(),
            variant_count: 0,
            samples: vec![],
            sample_overflow: 0,
            is_backbone: false,
            is_recent: false,
            formed_ybp: None,
            tmrca_ybp: None,
            has_hidden: false,
            children: vec![],
        }
    }

    #[test]
    fn parent_centers_over_children() {
        let root = InNode { children: vec![leaf("A"), leaf("B")], ..leaf("R") };
        let laid = layout(Some(&root), Orientation::Horizontal).unwrap();
        assert_eq!(laid.nodes.len(), 3);
        assert_eq!(laid.links.len(), 2);
        // Root (pushed last, after its children) sits at the breadth midpoint.
        let root_node = laid.nodes.iter().find(|n| n.name == "R").unwrap();
        let a = laid.nodes.iter().find(|n| n.name == "A").unwrap();
        let bb = laid.nodes.iter().find(|n| n.name == "B").unwrap();
        // Horizontal: breadth is the Y axis.
        assert!((root_node.cy - (a.cy + bb.cy) / 2.0).abs() < 0.01);
        // Children are one depth-step to the right of the root.
        assert!(a.cx > root_node.cx && bb.cx > root_node.cx);
    }

    #[test]
    fn vertical_swaps_axes() {
        let root = InNode { children: vec![leaf("A"), leaf("B")], ..leaf("R") };
        let laid = layout(Some(&root), Orientation::Vertical).unwrap();
        let root_node = laid.nodes.iter().find(|n| n.name == "R").unwrap();
        let a = laid.nodes.iter().find(|n| n.name == "A").unwrap();
        // Vertical: depth is the Y axis — children sit below the root.
        assert!(a.cy > root_node.cy);
        assert!((root_node.cx - a.cx).abs() < 200.0);
    }

    #[test]
    fn text_block_centers_and_ages_stack() {
        // No ages: name above center, variants below, ordered top→bottom.
        let bare = layout(Some(&leaf("R")), Orientation::Horizontal).unwrap();
        let n = &bare.nodes[0];
        assert!(n.name_y < n.count_y);
        let bare_center = (n.name_y + n.count_y) / 2.0;

        // Both ages: four rows, strictly top→bottom (name < count < formed < tmrca),
        // and the block stays centered at the same point as the age-less node.
        let aged = InNode { formed_ybp: Some(5254), tmrca_ybp: Some(3636), ..leaf("R") };
        let laid = layout(Some(&aged), Orientation::Horizontal).unwrap();
        let n = &laid.nodes[0];
        assert!(n.name_y < n.count_y && n.count_y < n.formed_y && n.formed_y < n.tmrca_y);
        let aged_center = (n.name_y + n.tmrca_y) / 2.0;
        assert!((bare_center - aged_center).abs() < 0.01, "blocks share a vertical center");
        assert_eq!(n.formed.as_deref(), Some("3304 BC"));
        assert_eq!(n.tmrca.as_deref(), Some("1686 BC"));
    }

    #[test]
    fn ybp_formatting() {
        assert_eq!(format_ybp(2000), "50 BC"); // 1950 - 2000 = -50
        assert_eq!(format_ybp(1000), "950 AD");
    }
}
