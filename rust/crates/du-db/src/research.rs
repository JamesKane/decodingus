//! D2 ResearchSubject registry (AppView side) — **PII-free**. A pseudonymous
//! "person" node co-admins attach project memberships + merge-links to. Identity
//! resolution (which kit/sample is which person) happens Edge-to-Edge over D1 /
//! genetically over D3; the AppView learns no name, kit number, or hash of one.
//! This module is pure storage + the authorization readers the handler gates on.
//!
//! **Canonical signed messages** ([`messages`]) are a cross-repo contract with the
//! Navigator Edge — keep them byte-stable.

use crate::DbError;
use sqlx::PgPool;
use uuid::Uuid;

/// The exact bytes each request's Ed25519 signature is computed over (cross-repo).
pub mod messages {
    pub fn register(steward_did: &str, project_id: i64, subject_id: Option<&str>) -> String {
        format!("research-register\n{steward_did}\n{project_id}\n{}", subject_id.unwrap_or(""))
    }
    pub fn merge(asserted_by_did: &str, keep: &str, retire: &str, method: &str) -> String {
        format!("research-merge\n{asserted_by_did}\n{keep}\n{retire}\n{method}")
    }
    pub fn custody(steward_did: &str, subject_id: &str, new_custody_did: &str) -> String {
        format!("research-custody\n{steward_did}\n{subject_id}\n{new_custody_did}")
    }
    /// Replay-guarded read poll: caller proves it is `did` at `ts` (unix seconds).
    pub fn poll(did: &str, ts: i64) -> String {
        format!("research-poll\n{did}\n{ts}")
    }
    pub fn add_member(actor_did: &str, project_id: i64, member_did: &str, role: &str) -> String {
        format!("research-add-member\n{actor_did}\n{project_id}\n{member_did}\n{role}")
    }
    pub fn revoke_member(actor_did: &str, project_id: i64, member_did: &str) -> String {
        format!("research-revoke-member\n{actor_did}\n{project_id}\n{member_did}")
    }
}

// ── collaborator-team ACL (D5) ────────────────────────────────────────────────

/// A project collaborator role. `social.group_project.owner_did` is the founding ADMIN.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Admin,
    CoAdmin,
    Moderator,
    Curator,
}

impl Role {
    pub fn parse(s: &str) -> Option<Role> {
        Some(match s {
            "ADMIN" => Role::Admin,
            "CO_ADMIN" => Role::CoAdmin,
            "MODERATOR" => Role::Moderator,
            "CURATOR" => Role::Curator,
            _ => return None,
        })
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Role::Admin => "ADMIN",
            Role::CoAdmin => "CO_ADMIN",
            Role::Moderator => "MODERATOR",
            Role::Curator => "CURATOR",
        }
    }
    /// Whether this role is granted `cap` (the D5 §4 capability→role map). Capabilities
    /// beyond what's enforced today (assertions/disputes/catalog) are defined for the
    /// cross-repo contract; they gate D4 once it lands.
    pub fn allows(self, cap: Capability) -> bool {
        use Capability::*;
        use Role::*;
        match cap {
            ReadProject => true, // any live team member
            ManageRoles => self == Admin,
            ManageSubjects | WriteAssertions => matches!(self, Admin | CoAdmin),
            ResolveDispute => matches!(self, Admin | Curator),
            PromoteToCatalog => self == Curator,
        }
    }
}

/// A capability the ACL gates (D5 §4).
#[derive(Debug, Clone, Copy)]
pub enum Capability {
    ManageRoles,
    ManageSubjects,
    ReadProject,
    WriteAssertions,   // D4 (forward)
    ResolveDispute,    // D4 (forward)
    PromoteToCatalog,  // catalog bridge (forward)
}

/// The caller's role in a project: `owner_did` ⇒ ADMIN, else the live `project_member`.
pub async fn role_of(pool: &PgPool, project_id: i64, did: &str) -> Result<Option<Role>, DbError> {
    if project_owner(pool, project_id).await?.as_deref() == Some(did) {
        return Ok(Some(Role::Admin));
    }
    let r: Option<String> = sqlx::query_scalar(
        "SELECT role FROM research.project_member WHERE project_id = $1 AND member_did = $2 AND left_at IS NULL",
    )
    .bind(project_id)
    .bind(did)
    .fetch_optional(pool)
    .await?;
    Ok(r.as_deref().and_then(Role::parse))
}

/// Whether `did` is a live team member of the project.
pub async fn is_team_member(pool: &PgPool, project_id: i64, did: &str) -> Result<bool, DbError> {
    Ok(role_of(pool, project_id, did).await?.is_some())
}

/// Whether `did` is granted `cap` in the project.
pub async fn can(pool: &PgPool, project_id: i64, did: &str, cap: Capability) -> Result<bool, DbError> {
    Ok(role_of(pool, project_id, did).await?.map(|r| r.allows(cap)).unwrap_or(false))
}

/// Add or update a team member (re-activates a revoked one).
pub async fn add_member(
    pool: &PgPool,
    project_id: i64,
    member_did: &str,
    role: Role,
    permissions: &[String],
    appointed_by: &str,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO research.project_member (project_id, member_did, role, permissions, appointed_by, left_at) \
         VALUES ($1, $2, $3, $4, $5, NULL) \
         ON CONFLICT (project_id, member_did) DO UPDATE SET \
            role = EXCLUDED.role, permissions = EXCLUDED.permissions, appointed_by = EXCLUDED.appointed_by, left_at = NULL",
    )
    .bind(project_id)
    .bind(member_did)
    .bind(role.as_str())
    .bind(permissions)
    .bind(appointed_by)
    .execute(pool)
    .await?;
    Ok(())
}

/// Revoke a member (set `left_at`). Returns whether a live row was revoked.
pub async fn revoke_member(pool: &PgPool, project_id: i64, member_did: &str) -> Result<bool, DbError> {
    let affected =
        sqlx::query("UPDATE research.project_member SET left_at = now() WHERE project_id = $1 AND member_did = $2 AND left_at IS NULL")
            .bind(project_id)
            .bind(member_did)
            .execute(pool)
            .await?
            .rows_affected();
    Ok(affected > 0)
}

/// A live team member (the owner appears as an implicit ADMIN).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct MemberRow {
    pub member_did: String,
    pub role: String,
}

pub async fn members_of(pool: &PgPool, project_id: i64) -> Result<Vec<MemberRow>, DbError> {
    Ok(sqlx::query_as(
        "SELECT member_did, role FROM ( \
            SELECT owner_did AS member_did, 'ADMIN' AS role, created_at AS joined_at \
            FROM social.group_project WHERE id = $1 AND owner_did IS NOT NULL \
            UNION ALL \
            SELECT member_did, role, joined_at FROM research.project_member \
            WHERE project_id = $1 AND left_at IS NULL \
         ) t ORDER BY joined_at",
    )
    .bind(project_id)
    .fetch_all(pool)
    .await?)
}

// ── registry ops ──────────────────────────────────────────────────────────────

/// Register a subject in a project: mint a fresh pseudonymous id when `subject_id` is
/// `None` (the new-member case), else attach the given id (the id-exchange-linked
/// case). The membership is idempotent. Returns the subject id.
pub async fn register_in_project(
    pool: &PgPool,
    subject_id: Option<Uuid>,
    project_id: i64,
    steward_did: &str,
) -> Result<Uuid, DbError> {
    let mut tx = pool.begin().await?;
    let sid = match subject_id {
        Some(id) => {
            // Ensure the row exists (idempotent for an externally-agreed id).
            sqlx::query("INSERT INTO research.research_subject (research_subject_id) VALUES ($1) ON CONFLICT DO NOTHING")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            id
        }
        None => {
            sqlx::query_scalar("INSERT INTO research.research_subject DEFAULT VALUES RETURNING research_subject_id")
                .fetch_one(&mut *tx)
                .await?
        }
    };
    sqlx::query(
        "INSERT INTO research.subject_membership (research_subject_id, project_id, steward_did) \
         VALUES ($1, $2, $3) ON CONFLICT (research_subject_id, project_id) DO NOTHING",
    )
    .bind(sid)
    .bind(project_id)
    .bind(steward_did)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(sid)
}

/// Merge two subjects (audited): keep `keep`, **tombstone** `retire` (set its
/// `retired_into = keep`) and repoint its memberships + biosample links to `keep`.
/// The pseudonymous id survives so a local holder can still resolve it.
pub async fn merge_subjects(
    pool: &PgPool,
    keep: Uuid,
    retire: Uuid,
    method: &str,
    asserted_by_did: &str,
    confidence: Option<f64>,
) -> Result<(), DbError> {
    if keep == retire {
        return Err(DbError::Conflict("cannot merge a subject into itself".into()));
    }
    let mut tx = pool.begin().await?;
    sqlx::query(
        "INSERT INTO research.subject_link (subject_a, subject_b, method, asserted_by_did, confidence) \
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(keep)
    .bind(retire)
    .bind(method)
    .bind(asserted_by_did)
    .bind(confidence)
    .execute(&mut *tx)
    .await?;
    // Repoint memberships (skip a project where the kept id is already a member).
    sqlx::query(
        "UPDATE research.subject_membership m SET research_subject_id = $1 \
         WHERE m.research_subject_id = $2 \
           AND NOT EXISTS (SELECT 1 FROM research.subject_membership k \
                           WHERE k.research_subject_id = $1 AND k.project_id = m.project_id)",
    )
    .bind(keep)
    .bind(retire)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM research.subject_membership WHERE research_subject_id = $1")
        .bind(retire)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "UPDATE research.subject_biosample b SET research_subject_id = $1 \
         WHERE b.research_subject_id = $2 \
           AND NOT EXISTS (SELECT 1 FROM research.subject_biosample k \
                           WHERE k.research_subject_id = $1 AND k.sample_guid = b.sample_guid)",
    )
    .bind(keep)
    .bind(retire)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM research.subject_biosample WHERE research_subject_id = $1")
        .bind(retire)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE research.research_subject SET retired_into = $1 WHERE research_subject_id = $2")
        .bind(keep)
        .bind(retire)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

/// Flip custody to a member DID (the member-claim pointer flip, §6). Returns whether
/// the subject existed.
pub async fn set_custody(pool: &PgPool, subject_id: Uuid, custody_did: &str) -> Result<bool, DbError> {
    let affected = sqlx::query("UPDATE research.research_subject SET custody_did = $2 WHERE research_subject_id = $1")
        .bind(subject_id)
        .bind(custody_did)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(affected > 0)
}

/// Link a subject to a federated sample (sparse; only when the person federated data).
pub async fn link_biosample(pool: &PgPool, subject_id: Uuid, sample_guid: Uuid) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO research.subject_biosample (research_subject_id, sample_guid) VALUES ($1, $2) \
         ON CONFLICT DO NOTHING",
    )
    .bind(subject_id)
    .bind(sample_guid)
    .execute(pool)
    .await?;
    Ok(())
}

// ── authorization readers (gate the handler) ──────────────────────────────────

/// The project's owner DID (the v1 register-ACL; extends to project-admins under D5).
pub async fn project_owner(pool: &PgPool, project_id: i64) -> Result<Option<String>, DbError> {
    Ok(sqlx::query_scalar("SELECT owner_did FROM social.group_project WHERE id = $1 AND deleted = false")
        .bind(project_id)
        .fetch_optional(pool)
        .await?
        .flatten())
}

/// Whether `did` participates in a project — its owner or a steward of a member
/// (gates the member-list read; extends to project-admins under D5).
pub async fn is_project_participant(pool: &PgPool, did: &str, project_id: i64) -> Result<bool, DbError> {
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM ( \
            SELECT 1 FROM social.group_project WHERE id = $2 AND owner_did = $1 \
            UNION ALL \
            SELECT 1 FROM research.subject_membership WHERE project_id = $2 AND steward_did = $1 \
         ) p",
    )
    .bind(did)
    .bind(project_id)
    .fetch_one(pool)
    .await?
        > 0)
}

/// Whether `did` stewards `subject_id` in any project (gates merge/custody).
pub async fn is_steward_of(pool: &PgPool, did: &str, subject_id: Uuid) -> Result<bool, DbError> {
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM research.subject_membership WHERE research_subject_id = $1 AND steward_did = $2",
    )
    .bind(subject_id)
    .bind(did)
    .fetch_one(pool)
    .await?
        > 0)
}

// ── reads ─────────────────────────────────────────────────────────────────────

/// A subject's membership (pseudonymous).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct SubjectRow {
    pub research_subject_id: Uuid,
    pub steward_did: String,
}

/// Pseudonymous subjects in a project (the member list the registry exposes).
pub async fn subjects_in_project(pool: &PgPool, project_id: i64) -> Result<Vec<SubjectRow>, DbError> {
    Ok(sqlx::query_as(
        "SELECT research_subject_id, steward_did FROM research.subject_membership \
         WHERE project_id = $1 ORDER BY added_at",
    )
    .bind(project_id)
    .fetch_all(pool)
    .await?)
}

/// A subject's custody + (if merged away) where it was retired into.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct SubjectView {
    pub research_subject_id: Uuid,
    pub custody_did: Option<String>,
    pub retired_into: Option<Uuid>,
}

pub async fn subject(pool: &PgPool, id: Uuid) -> Result<Option<SubjectView>, DbError> {
    Ok(sqlx::query_as(
        "SELECT research_subject_id, custody_did, retired_into FROM research.research_subject WHERE research_subject_id = $1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?)
}
