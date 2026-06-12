//! Materialize a `du_domain::merge::MergePlan` into a reviewable change set.
//!
//! Each [`MergeOp`] becomes a `tree.tree_change` row. New-node placement uses
//! the placeholder mechanism the apply engine understands: a CREATE carries its
//! negative `placeholder`, and ops attaching to it carry `*_placeholder` refs
//! (resolved to real ids during `change_set::apply`). Variant *names* from the
//! plan are resolved to `core.variant` ids here (get-or-create as UNNAMED).
//!
//! The resulting set lands in READY_FOR_REVIEW: a curator reviews/approves the
//! changes (and the algorithm's ambiguity flags) before applying.

use crate::DbError;
use du_domain::merge::{MergeOp, MergePlan, NodeRef};
use serde_json::{json, Map, Value};
use sqlx::{PgPool, Postgres, Transaction};
use std::collections::HashMap;

pub struct Materialized {
    pub change_set_id: i64,
    pub change_count: i64,
}

pub async fn materialize(
    pool: &PgPool,
    plan: &MergePlan,
    source: &str,
    dna: &str,
    created_by: &str,
) -> Result<Materialized, DbError> {
    let mut tx = pool.begin().await?;

    let cs_id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.change_set (source, haplogroup_type, status, description, created_by) \
         VALUES ($1, $2::core.dna_type, 'READY_FOR_REVIEW', $3, $4) RETURNING id",
    )
    .bind(source)
    .bind(dna)
    .bind(format!("Merge from {source}: {} ambiguities", plan.ambiguities.len()))
    .bind(created_by)
    .fetch_one(&mut *tx)
    .await?;

    let mut cache: HashMap<String, i64> = HashMap::new();
    let mut count: i64 = 0;

    for op in &plan.ops {
        match op {
            MergeOp::CreateNode { placeholder, name, parent, variants } => {
                let mut ids = Vec::with_capacity(variants.len());
                for v in variants {
                    ids.push(get_or_create_variant(&mut tx, &mut cache, v).await?);
                }
                let mut nv = Map::new();
                nv.insert("name".into(), json!(name));
                nv.insert("haplogroup_type".into(), json!(dna));
                nv.insert("placeholder".into(), json!(placeholder));
                nv.insert("variant_ids".into(), json!(ids));
                nv.insert("source".into(), json!(source));
                insert_parent_ref(&mut nv, parent, "parent_haplogroup_id", "parent_placeholder");
                insert_change(&mut tx, cs_id, "CREATE", None, Value::Object(nv)).await?;
                count += 1;
            }
            MergeOp::Reparent { node, new_parent } => {
                let mut nv = Map::new();
                insert_parent_ref(&mut nv, new_parent, "new_parent_haplogroup_id", "new_parent_placeholder");
                insert_change(&mut tx, cs_id, "REPARENT", Some(*node), Value::Object(nv)).await?;
                count += 1;
            }
            MergeOp::EditVariants { node, add, remove } => {
                let mut add_ids = Vec::new();
                for v in add {
                    add_ids.push(get_or_create_variant(&mut tx, &mut cache, v).await?);
                }
                let mut remove_ids = Vec::new();
                for v in remove {
                    if let Some(id) = lookup_variant(&mut tx, v).await? {
                        remove_ids.push(id);
                    }
                }
                let nv = json!({ "add": add_ids, "remove": remove_ids });
                insert_change(&mut tx, cs_id, "VARIANT_EDIT", Some(*node), nv).await?;
                count += 1;
            }
            // Informational: the source matched an existing node 1:1. No tree
            // mutation; left out of the change set (visible in the merge stats).
            MergeOp::MatchMetadata { .. } => {}
        }
    }

    sqlx::query("UPDATE tree.change_set SET change_count = $2 WHERE id = $1")
        .bind(cs_id)
        .bind(count)
        .execute(&mut *tx)
        .await?;

    tx.commit().await?;
    Ok(Materialized { change_set_id: cs_id, change_count: count })
}

fn insert_parent_ref(nv: &mut Map<String, Value>, r: &NodeRef, id_key: &str, ph_key: &str) {
    match r {
        NodeRef::Existing(id) => {
            nv.insert(id_key.into(), json!(id));
        }
        NodeRef::New(ph) => {
            nv.insert(ph_key.into(), json!(ph));
        }
        NodeRef::Root => {}
    }
}

async fn insert_change(
    tx: &mut Transaction<'_, Postgres>,
    cs_id: i64,
    change_type: &str,
    haplogroup_id: Option<i64>,
    new_values: Value,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO tree.tree_change (change_set_id, change_type, haplogroup_id, new_values) \
         VALUES ($1, $2::tree.tree_change_type, $3, $4)",
    )
    .bind(cs_id)
    .bind(change_type)
    .bind(haplogroup_id)
    .bind(new_values)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

async fn get_or_create_variant(
    tx: &mut Transaction<'_, Postgres>,
    cache: &mut HashMap<String, i64>,
    name: &str,
) -> Result<i64, DbError> {
    if let Some(&id) = cache.get(name) {
        return Ok(id);
    }
    let id = crate::variant::ensure_base_variant_id(&mut **tx, name).await?;
    cache.insert(name.to_string(), id);
    Ok(id)
}

async fn lookup_variant(tx: &mut Transaction<'_, Postgres>, name: &str) -> Result<Option<i64>, DbError> {
    Ok(sqlx::query_scalar("SELECT id FROM core.variant WHERE canonical_name = $1")
        .bind(name)
        .fetch_optional(&mut **tx)
        .await?)
}
