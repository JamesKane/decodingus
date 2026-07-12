//! Tree API: haplotree assembly from `du-db` subtree rows, ETag/conditional-GET
//! cache revalidation, and the `/api/v1/{y,mt}-tree[...]` handlers. Wire DTOs live
//! in [`super::dto`]; the router + OpenAPI doc that mount these live in [`super`].

use super::dto::{HaplogroupNodeDto, LeafSampleDto, LeafSamplesDto, RootParams, TreeDto, TreeVersionDto, VariantDto};
use crate::error::AppError;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use du_domain::enums::DnaType;
use std::collections::HashMap;

// ── tree assembly ────────────────────────────────────────────────────────────

fn assemble_forest(
    nodes: Vec<du_db::haplogroup::SubtreeNode>,
    variants: &HashMap<i64, Vec<VariantDto>>,
    counts: &HashMap<i64, i64>,
) -> Vec<HaplogroupNodeDto> {
    let mut by_parent: HashMap<Option<i64>, Vec<du_db::haplogroup::SubtreeNode>> = HashMap::new();
    for n in nodes {
        by_parent.entry(n.parent_id).or_default().push(n);
    }
    build_level(None, &by_parent, variants, counts, 0)
}

fn build_level(
    parent: Option<i64>,
    by_parent: &HashMap<Option<i64>, Vec<du_db::haplogroup::SubtreeNode>>,
    variants: &HashMap<i64, Vec<VariantDto>>,
    counts: &HashMap<i64, i64>,
    depth: u16,
) -> Vec<HaplogroupNodeDto> {
    // Depth guard: tree-merge data can contain cycles; cap recursion defensively.
    if depth > 256 {
        return Vec::new();
    }
    let mut kids = match by_parent.get(&parent) {
        Some(k) => k.iter().collect::<Vec<_>>(),
        None => return Vec::new(),
    };
    kids.sort_by(|a, b| a.name.cmp(&b.name));
    kids.into_iter()
        .map(|n| {
            let children = build_level(Some(n.id), by_parent, variants, counts, depth + 1);
            // Cumulative: this node's own placed leaves + everything under its children.
            let sample_count =
                counts.get(&n.id).copied().unwrap_or(0) + children.iter().map(|c| c.sample_count).sum::<i64>();
            HaplogroupNodeDto {
                id: n.id,
                name: n.name.clone(),
                haplogroup_type: n.haplogroup_type.clone(),
                formed_ybp: n.formed_ybp,
                tmrca_ybp: n.tmrca_ybp,
                sample_count,
                variants: variants.get(&n.id).cloned().unwrap_or_default(),
                children,
            }
        })
        .collect()
}

async fn build_tree(st: &AppState, dna: DnaType, root: Option<&str>) -> Result<TreeDto, AppError> {
    let nodes = du_db::haplogroup::subtree(&st.pool, dna, root).await?;
    let counts = du_db::tree_sample::counts_by_node(&st.pool, dna).await?;
    Ok(TreeDto { roots: assemble_forest(nodes, &HashMap::new(), &counts) })
}

/// Like [`build_tree`] but embeds each node's defining variants (with multi-build
/// coordinates) — one payload a client can build a placement tree from without per-node
/// fetches. Variants are loaded for the whole lineage in one query and grouped by node.
async fn build_tree_full(st: &AppState, dna: DnaType, root: Option<&str>) -> Result<TreeDto, AppError> {
    let nodes = du_db::haplogroup::subtree(&st.pool, dna, root).await?;
    let mut variants: HashMap<i64, Vec<VariantDto>> = HashMap::new();
    for (hid, v, link_ancestral, link_derived) in du_db::variant::for_dna_type_grouped(&st.pool, dna).await? {
        // Carry the per-branch ASR polarity so the descent report classifies against the
        // branch's actual ancestral→derived direction, not the variant's global coordinate
        // polarity (which flips ~18% of backbone calls). See VariantDto::link_ancestral.
        variants.entry(hid).or_default().push(VariantDto { link_ancestral, link_derived, ..VariantDto::from(v) });
    }
    let counts = du_db::tree_sample::counts_by_node(&st.pool, dna).await?;
    Ok(TreeDto { roots: assemble_forest(nodes, &variants, &counts) })
}

// ── tree cache revalidation (ETag / conditional GET) ─────────────────────────

/// The cache token for a tree representation. Strong ETag keyed on the persisted
/// tree revision (`du_db::tree_revision`) plus the things that vary the payload:
/// full-vs-plain, dna type, and subtree root. The revision is bumped by every
/// tree-mutating op (topology, variant set, coordinate enrichment, naming), so a
/// matching `If-None-Match` is a safe 304.
pub(crate) fn tree_etag(full: bool, dna: DnaType, root: Option<&str>, revision: i64) -> String {
    let shape = if full { "full" } else { "plain" };
    let dna = if matches!(dna, DnaType::YDna) { "y" } else { "mt" };
    format!("\"{shape}-{dna}-{}-r{revision}\"", root.unwrap_or("*"))
}

/// Whether the request's `If-None-Match` matches our current `etag` (a `*`
/// wildcard or a comma-separated list of strong validators).
pub(crate) fn if_none_match(headers: &HeaderMap, etag: &str) -> bool {
    let Some(val) = headers.get(header::IF_NONE_MATCH).and_then(|v| v.to_str().ok()) else {
        return false;
    };
    val.split(',').map(str::trim).any(|t| t == "*" || t == etag)
}

/// HTTP-date (`Last-Modified`) for a revision timestamp.
fn http_date(ts: chrono::DateTime<chrono::Utc>) -> String {
    ts.format("%a, %d %b %Y %H:%M:%S GMT").to_string()
}

/// Directory holding the on-disk tree cache. `DU_TREE_CACHE_DIR` overrides it
/// (set to a scratch volume in prod); defaults to a subdir of the system temp dir.
fn cache_dir() -> std::path::PathBuf {
    std::env::var_os("DU_TREE_CACHE_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("du-tree-cache"))
}

/// A per-database prefix for cache filenames, so two databases (e.g. parallel test
/// ephemeral DBs, which restart `revision` at 1) never read each other's files. In a
/// real deployment this is a single constant value; `revision` alone is authoritative
/// within one database.
fn cache_namespace(pool: &du_db::PgPool) -> String {
    let name = pool.connect_options().get_database().unwrap_or("db").to_string();
    name.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' }).collect()
}

/// Cache-file prefix shared by every revision of one (namespace, shape, dna) —
/// `sweep_stale` uses it to find prior-revision siblings.
fn cache_prefix(ns: &str, full: bool, dna: DnaType) -> String {
    let shape = if full { "full" } else { "plain" };
    let dna = if matches!(dna, DnaType::YDna) { "y" } else { "mt" };
    format!("{ns}-tree-{shape}-{dna}-r")
}

/// Cache file for the whole-tree (rootless) response at `revision`. The revision is
/// in the name so a bump lands on a fresh path and stale files can be swept.
fn cache_file(ns: &str, full: bool, dna: DnaType, revision: i64) -> std::path::PathBuf {
    cache_dir().join(format!("{}{revision}.json", cache_prefix(ns, full, dna)))
}

/// Build the payload once and write it to `path` (atomically, via a temp file +
/// rename), sweeping any older-revision siblings for the same shape/dna so the cache
/// dir only ever holds the current revision. Returns the serialized bytes so the
/// caller can serve this first request without a re-read.
async fn build_and_cache(st: &AppState, dna: DnaType, full: bool, prefix: &str, path: &std::path::Path) -> Result<Vec<u8>, AppError> {
    let dto = if full { build_tree_full(st, dna, None).await? } else { build_tree(st, dna, None).await? };
    let bytes = serde_json::to_vec(&dto).map_err(|e| AppError::Internal(e.to_string()))?;
    let dir = cache_dir();
    tokio::fs::create_dir_all(&dir).await.map_err(|e| AppError::Internal(e.to_string()))?;
    // Unique temp name (pid + a process-local counter) so concurrent builders never
    // clobber each other's partial file; the rename onto `path` is atomic, so a reader
    // sees all-or-nothing.
    static TMP_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let seq = TMP_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let base = path.file_name().and_then(|n| n.to_str()).unwrap_or("tree");
    let tmp = dir.join(format!("{base}.tmp{}-{seq}", std::process::id()));
    tokio::fs::write(&tmp, &bytes).await.map_err(|e| AppError::Internal(e.to_string()))?;
    tokio::fs::rename(&tmp, path).await.map_err(|e| AppError::Internal(e.to_string()))?;
    sweep_stale(&dir, prefix, path).await;
    Ok(bytes)
}

/// Remove prior-revision files for this (namespace, shape, dna) — every entry sharing
/// `prefix` except the just-written `keep`. Scoping to `prefix` means a sweep never
/// touches another database's or shape's files. Best-effort: failures are ignored
/// (they'll be overwritten or swept next time).
async fn sweep_stale(dir: &std::path::Path, prefix: &str, keep: &std::path::Path) {
    let Ok(mut rd) = tokio::fs::read_dir(dir).await else { return };
    while let Ok(Some(entry)) = rd.next_entry().await {
        let p = entry.path();
        if p != keep && p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with(prefix) && n.ends_with(".json")) {
            let _ = tokio::fs::remove_file(&p).await;
        }
    }
}

/// Stream `path` as the response body: a small read buffer per connection, so the
/// process never holds the whole ~60 MB payload — the OS page cache keeps the file
/// resident while it's hot and evicts it when it isn't.
async fn stream_file(path: &std::path::Path, cache_headers: [(header::HeaderName, String); 4]) -> Option<Response> {
    let file = tokio::fs::File::open(path).await.ok()?;
    let stream = tokio_util::io::ReaderStream::new(file);
    Some((StatusCode::OK, cache_headers, axum::body::Body::from_stream(stream)).into_response())
}

/// Conditional GET for a tree endpoint: read the cheap revision marker, build the
/// ETag, and short-circuit to **304** when `If-None-Match` matches — *before* any
/// tree query/serialization. Otherwise serve the whole-tree payload from the on-disk
/// cache (built once per revision, streamed from a page-cached file), attaching the
/// `ETag` / `Last-Modified` / `Cache-Control: no-cache` headers. `?root=` subtrees are
/// not cached — they're built and served directly.
async fn tree_conditional(
    st: &AppState,
    headers: &HeaderMap,
    dna: DnaType,
    root: Option<&str>,
    full: bool,
) -> Result<Response, AppError> {
    let (revision, updated_at) = du_db::tree_revision::current(&st.pool).await?;
    let etag = tree_etag(full, dna, root, revision);
    let last_modified = http_date(updated_at);
    let cache_headers = [
        (header::CONTENT_TYPE, "application/json".to_string()),
        (header::ETAG, etag.clone()),
        (header::LAST_MODIFIED, last_modified),
        (header::CACHE_CONTROL, "no-cache".to_string()),
    ];
    if if_none_match(headers, &etag) {
        return Ok((StatusCode::NOT_MODIFIED, cache_headers).into_response());
    }
    // Subtree (`?root=`) responses aren't cached — build and serve directly.
    let Some(()) = root.is_none().then_some(()) else {
        let dto = if full { build_tree_full(st, dna, root).await? } else { build_tree(st, dna, root).await? };
        return Ok((StatusCode::OK, cache_headers, Json(dto)).into_response());
    };
    let ns = cache_namespace(&st.pool);
    let path = cache_file(&ns, full, dna, revision);
    // Fast path: stream the already-built file for this revision.
    if let Some(resp) = stream_file(&path, cache_headers.clone()).await {
        return Ok(resp);
    }
    // Miss: build once (writes the file for subsequent requests) and serve the bytes
    // we already have in hand rather than re-reading them back off disk.
    let bytes = build_and_cache(st, dna, full, &cache_prefix(&ns, full, dna), &path).await?;
    Ok((StatusCode::OK, cache_headers, bytes).into_response())
}

/// The `/…-tree/version` body: revision + the full-tree ETag, so the Edge can
/// check the version (and prime an `If-None-Match`) without fetching the tree.
async fn tree_version(st: &AppState, dna: DnaType) -> Result<Json<TreeVersionDto>, AppError> {
    let (revision, updated_at) = du_db::tree_revision::current(&st.pool).await?;
    Ok(Json(TreeVersionDto {
        revision,
        etag: tree_etag(true, dna, None, revision),
        updated_at: updated_at.to_rfc3339(),
    }))
}

// ── handlers ─────────────────────────────────────────────────────────────────

#[utoipa::path(get, path = "/api/v1/y-tree", params(RootParams), tag = "tree",
    responses((status = 200, description = "Y-chromosome haplogroup tree", body = TreeDto),
              (status = 304, description = "Not modified (ETag matched If-None-Match)")))]
pub(crate) async fn y_tree(State(st): State<AppState>, headers: HeaderMap, Query(q): Query<RootParams>) -> Result<Response, AppError> {
    tree_conditional(&st, &headers, DnaType::YDna, q.root(), false).await
}

#[utoipa::path(get, path = "/api/v1/mt-tree", params(RootParams), tag = "tree",
    responses((status = 200, description = "Mitochondrial haplogroup tree", body = TreeDto),
              (status = 304, description = "Not modified (ETag matched If-None-Match)")))]
pub(crate) async fn mt_tree(State(st): State<AppState>, headers: HeaderMap, Query(q): Query<RootParams>) -> Result<Response, AppError> {
    tree_conditional(&st, &headers, DnaType::MtDna, q.root(), false).await
}

#[utoipa::path(get, path = "/api/v1/y-tree/full", params(RootParams), tag = "tree",
    responses((status = 200, description = "Y-chromosome haplogroup tree with per-node defining variants", body = TreeDto),
              (status = 304, description = "Not modified (ETag matched If-None-Match)")))]
pub(crate) async fn y_tree_full(State(st): State<AppState>, headers: HeaderMap, Query(q): Query<RootParams>) -> Result<Response, AppError> {
    tree_conditional(&st, &headers, DnaType::YDna, q.root(), true).await
}

#[utoipa::path(get, path = "/api/v1/mt-tree/full", params(RootParams), tag = "tree",
    responses((status = 200, description = "Mitochondrial haplogroup tree with per-node defining variants", body = TreeDto),
              (status = 304, description = "Not modified (ETag matched If-None-Match)")))]
pub(crate) async fn mt_tree_full(State(st): State<AppState>, headers: HeaderMap, Query(q): Query<RootParams>) -> Result<Response, AppError> {
    tree_conditional(&st, &headers, DnaType::MtDna, q.root(), true).await
}

#[utoipa::path(get, path = "/api/v1/y-tree/version", tag = "tree",
    responses((status = 200, description = "Current Y-tree revision + ETag (cheap cache-revalidation probe)", body = TreeVersionDto)))]
pub(crate) async fn y_tree_version(State(st): State<AppState>) -> Result<Json<TreeVersionDto>, AppError> {
    tree_version(&st, DnaType::YDna).await
}

#[utoipa::path(get, path = "/api/v1/mt-tree/version", tag = "tree",
    responses((status = 200, description = "Current mt-tree revision + ETag (cheap cache-revalidation probe)", body = TreeVersionDto)))]
pub(crate) async fn mt_tree_version(State(st): State<AppState>) -> Result<Json<TreeVersionDto>, AppError> {
    tree_version(&st, DnaType::MtDna).await
}

async fn node_samples(st: &AppState, dna: DnaType, name: &str) -> Result<Json<LeafSamplesDto>, AppError> {
    // Resolve the requested name/SNP to a canonical node, then list its at-or-below leaves.
    let Some(node) = du_db::haplogroup::resolve_name_or_variant(&st.pool, name, dna).await? else {
        return Err(AppError::NotFound(format!("haplogroup {name}")));
    };
    let items = du_db::tree_sample::samples_under(&st.pool, &node, dna)
        .await?
        .into_iter()
        .map(LeafSampleDto::from)
        .collect();
    Ok(Json(LeafSamplesDto { items }))
}

#[utoipa::path(get, path = "/api/v1/y-tree/node/{name}/samples",
    params(("name" = String, Path, description = "Haplogroup name or defining SNP")), tag = "tree",
    responses((status = 200, description = "Non-D2C sample leaves at or below the Y node", body = LeafSamplesDto),
              (status = 404, description = "Unknown haplogroup")))]
pub(crate) async fn y_node_samples(State(st): State<AppState>, Path(name): Path<String>) -> Result<Json<LeafSamplesDto>, AppError> {
    node_samples(&st, DnaType::YDna, &name).await
}

#[utoipa::path(get, path = "/api/v1/mt-tree/node/{name}/samples",
    params(("name" = String, Path, description = "Haplogroup name or defining variant")), tag = "tree",
    responses((status = 200, description = "Non-D2C sample leaves at or below the mt node", body = LeafSamplesDto),
              (status = 404, description = "Unknown haplogroup")))]
pub(crate) async fn mt_node_samples(State(st): State<AppState>, Path(name): Path<String>) -> Result<Json<LeafSamplesDto>, AppError> {
    node_samples(&st, DnaType::MtDna, &name).await
}
