//! Recruitment campaigns (Tier 3c) — a **privacy-preserving** cohort broker. A project
//! researcher defines criteria (haplogroup + lineage) and a message; the AppView computes
//! the matching cohort from `fed.biosample` and delivers invitations. The researcher
//! **never receives the cohort** — [`campaigns_for_project`] exposes only aggregate counts,
//! and [`accepted_dids`] only the members who opted in. Invited/declined DIDs are never
//! surfaced. Same "never materialize everyone" stance as IBD candidate generation.

use crate::DbError;
use chrono::{DateTime, Utc};
use du_domain::enums::DnaType;
use serde::Serialize;
use sqlx::PgPool;
use uuid::Uuid;

/// Canonical signing strings for the recruitment Edge API (`/api/v1/recruitment/*`). The
/// Navigator mirrors these byte-for-byte (`navigator_sync::recruitment::messages`), so the
/// two ends cannot drift.
pub mod messages {
    /// Replay-guarded poll for the caller's open invitations.
    pub fn poll(did: &str, ts: i64) -> String {
        format!("recruitment-poll\n{did}\n{ts}")
    }
    /// Accept (`true`) or decline (`false`) an invitation to a campaign.
    pub fn respond(did: &str, campaign_id: i64, accept: bool) -> String {
        format!("recruitment-respond\n{did}\n{campaign_id}\n{accept}")
    }
    /// Replay-guarded poll for the projects the caller may recruit for.
    pub fn projects(did: &str, ts: i64) -> String {
        format!("recruitment-projects\n{did}\n{ts}")
    }
    /// Create a campaign in `project_id` targeting `target_haplogroup` on `lineage`. The
    /// free-text title/message are *not* signed (content, not targeting); the structural
    /// fields that decide who gets contacted are.
    pub fn create(did: &str, project_id: i64, target_haplogroup: &str, lineage: &str) -> String {
        format!("recruitment-create\n{did}\n{project_id}\n{target_haplogroup}\n{lineage}")
    }
}

/// A campaign as the researcher sees it — aggregate only (no invited/declined DIDs).
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct CampaignRow {
    pub id: i64,
    pub title: String,
    pub message: String,
    pub target_haplogroup: String,
    pub lineage: String,
    pub status: String,
    pub cohort_size: i32,
    pub accepted_count: i64,
    pub created_at: DateTime<Utc>,
}

/// Create a campaign (status ACTIVE, cohort_size 0 until delivery). Returns its id.
pub async fn create_campaign(
    pool: &PgPool,
    project_id: i64,
    created_by: Uuid,
    title: &str,
    message: &str,
    target_haplogroup: &str,
    lineage: &str,
) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "INSERT INTO research.recruitment_campaign \
           (project_id, created_by, title, message, target_haplogroup, lineage) \
         VALUES ($1,$2,$3,$4,$5,$6) RETURNING id",
    )
    .bind(project_id)
    .bind(created_by)
    .bind(title)
    .bind(message)
    .bind(target_haplogroup)
    .bind(lineage)
    .fetch_one(pool)
    .await?)
}

/// Compute the matching cohort: distinct DIDs whose mirrored biosample carries the target
/// haplogroup — or, when the target resolves to a tree node, **any of its subclades** — on
/// the chosen lineage. `exclude` (the researcher) is dropped from the cohort. When the target
/// doesn't resolve to a node (an off-tree call), it falls back to the literal-string match
/// (the v1 behavior), so campaigns for not-yet-placed clades still reach exact carriers.
pub async fn compute_cohort(
    pool: &PgPool,
    target_haplogroup: &str,
    lineage: &str,
    exclude: Option<&str>,
) -> Result<Vec<String>, DbError> {
    let (column, dna_type) = match lineage {
        "Y_DNA" => ("y_haplogroup", DnaType::YDna),
        "MT_DNA" => ("mt_haplogroup", DnaType::MtDna),
        _ => return Err(DbError::Conflict(format!("unknown lineage {lineage}"))),
    };
    // Expand the target to itself + every descendant clade via the tree (subtree includes the
    // root). If it doesn't resolve to a node, match the literal string the researcher typed.
    let names = match crate::haplogroup::resolve_name_or_variant(pool, target_haplogroup, dna_type).await? {
        Some(canonical) => crate::haplogroup::subtree(pool, dna_type, Some(&canonical))
            .await?
            .into_iter()
            .map(|n| n.name)
            .collect::<Vec<String>>(),
        None => vec![target_haplogroup.to_string()],
    };
    // Column is a fixed map, never interpolated from user input.
    let sql = format!(
        "SELECT DISTINCT did FROM fed.biosample \
         WHERE {column} = ANY($1) AND ($2::text IS NULL OR did <> $2) ORDER BY did"
    );
    Ok(sqlx::query_scalar(&sql).bind(&names).bind(exclude).fetch_all(pool).await?)
}

/// Deliver a campaign to a cohort: INVITED rows (idempotent) + set `cohort_size` to the
/// number now invited. Returns how many fresh invitations were created (callers notify
/// exactly those). Re-delivering doesn't re-invite or re-notify.
pub async fn deliver(pool: &PgPool, campaign_id: i64, dids: &[String]) -> Result<Vec<String>, DbError> {
    let mut fresh = Vec::new();
    let mut tx = pool.begin().await?;
    for did in dids {
        let inserted: Option<i64> = sqlx::query_scalar(
            "INSERT INTO research.recruitment_target (campaign_id, target_did) VALUES ($1, $2) \
             ON CONFLICT (campaign_id, target_did) DO NOTHING RETURNING campaign_id",
        )
        .bind(campaign_id)
        .bind(did)
        .fetch_optional(&mut *tx)
        .await?;
        if inserted.is_some() {
            fresh.push(did.clone());
        }
    }
    sqlx::query(
        "UPDATE research.recruitment_campaign SET cohort_size = \
           (SELECT count(*) FROM research.recruitment_target WHERE campaign_id = $1) WHERE id = $1",
    )
    .bind(campaign_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(fresh)
}

/// One campaign (with its accepted count), by id.
pub async fn get_campaign(pool: &PgPool, id: i64) -> Result<Option<CampaignRow>, DbError> {
    Ok(sqlx::query_as(&campaign_select("c.id = $1")).bind(id).fetch_optional(pool).await?)
}

/// A project's campaigns, newest first (researcher view — aggregate only).
pub async fn campaigns_for_project(pool: &PgPool, project_id: i64) -> Result<Vec<CampaignRow>, DbError> {
    Ok(sqlx::query_as(&campaign_select("c.project_id = $1 ORDER BY c.created_at DESC"))
        .bind(project_id)
        .fetch_all(pool)
        .await?)
}

fn campaign_select(where_clause: &str) -> String {
    format!(
        "SELECT c.id, c.title, c.message, c.target_haplogroup, c.lineage, c.status, c.cohort_size, \
                (SELECT count(*) FROM research.recruitment_target t \
                 WHERE t.campaign_id = c.id AND t.status = 'ACCEPTED') AS accepted_count, \
                c.created_at \
         FROM research.recruitment_campaign c WHERE {where_clause}"
    )
}

/// The researcher learns the user_id (the opt-in's identity) here is intentionally
/// returned as the DID; the web layer resolves a display name. **Only ACCEPTED** rows —
/// invited/declined remain private.
pub async fn accepted_dids(pool: &PgPool, campaign_id: i64) -> Result<Vec<String>, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT target_did FROM research.recruitment_target \
         WHERE campaign_id = $1 AND status = 'ACCEPTED' ORDER BY responded_at",
    )
    .bind(campaign_id)
    .fetch_all(pool)
    .await?)
}

/// The created-by user of a campaign (to notify on an acceptance) + its project.
pub async fn campaign_owner_project(pool: &PgPool, campaign_id: i64) -> Result<Option<(Uuid, i64)>, DbError> {
    Ok(sqlx::query_as("SELECT created_by, project_id FROM research.recruitment_campaign WHERE id = $1")
        .bind(campaign_id)
        .fetch_optional(pool)
        .await?)
}

/// A target's status in a campaign (`None` if they were never invited) — the invitation
/// page's authorization check.
pub async fn target_status(pool: &PgPool, campaign_id: i64, did: &str) -> Result<Option<String>, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT status FROM research.recruitment_target WHERE campaign_id = $1 AND target_did = $2",
    )
    .bind(campaign_id)
    .bind(did)
    .fetch_optional(pool)
    .await?)
}

/// Record a target's response (accept/decline) — only from the INVITED state (idempotent /
/// no flip-flop). Returns whether it changed.
pub async fn respond(pool: &PgPool, campaign_id: i64, did: &str, accept: bool) -> Result<bool, DbError> {
    let status = if accept { "ACCEPTED" } else { "DECLINED" };
    let n = sqlx::query(
        "UPDATE research.recruitment_target SET status = $3, responded_at = now() \
         WHERE campaign_id = $1 AND target_did = $2 AND status = 'INVITED'",
    )
    .bind(campaign_id)
    .bind(did)
    .bind(status)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// A member's open (INVITED) invitations, with the campaign + project context.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct InvitationRow {
    pub campaign_id: i64,
    pub title: String,
    pub message: String,
    pub project_name: String,
}

pub async fn invitations_for(pool: &PgPool, did: &str) -> Result<Vec<InvitationRow>, DbError> {
    Ok(sqlx::query_as(
        "SELECT c.id AS campaign_id, c.title, c.message, g.project_name \
         FROM research.recruitment_target t \
         JOIN research.recruitment_campaign c ON c.id = t.campaign_id \
         JOIN social.group_project g ON g.id = c.project_id \
         WHERE t.target_did = $1 AND t.status = 'INVITED' AND c.status = 'ACTIVE' \
         ORDER BY c.created_at DESC",
    )
    .bind(did)
    .fetch_all(pool)
    .await?)
}
