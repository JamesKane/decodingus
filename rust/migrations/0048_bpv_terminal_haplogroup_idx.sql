-- tree.biosample_private_variant.terminal_haplogroup_id had a FK to tree.haplogroup
-- but no index on the referencing column. On a greenfield Y reload, deleting the
-- ~12k haplogroups runs that FK's existence check once per deleted row, and with no
-- index each check seq-scans the ~800k private-variant rows (incl. same-transaction
-- dead tuples) — making the final DELETE take >10s and get severed by the dev
-- container's idle-flow reaper. Index the FK column so the check is an index probe.
-- (All other FK columns referencing tree.haplogroup are already indexed or empty.)
CREATE INDEX IF NOT EXISTS bpv_terminal_haplogroup_idx
    ON tree.biosample_private_variant (terminal_haplogroup_id);
