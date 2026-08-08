-- PREVIEW DATA ONLY — synthetic ancestral origins for exercising the origins icicle
-- (`/ytree/node/:name/origins`, proposals/ancestral-origin-icicle.md) on a local database.
--
--   psql "$DATABASE_URL" -f scripts/seed-ancestral-origins.sql
--   psql "$DATABASE_URL" -c "DELETE FROM fed.ancestral_origin WHERE did = 'did:plc:preview';"
--
-- NEVER run this against production. Every row is stamped `did = 'did:plc:preview'` so the
-- whole set is removable with the one-line delete above, and so nothing here can be mistaken
-- for a real contributor's record.
--
-- WHY IT EXISTS. The view ships dark: `import_kit_identifiers.rs` reserves MDKA for records a
-- PDS publishes, and the Navigator publisher is not built yet, so there is no honest way to get
-- data in front of a reviewer. This fabricates a plausible cohort against REAL tree placements
-- so the layout, the palette, the era gate and the pruning can be seen working.
--
-- WHAT IT WRITES. Rows are inserted straight into the mirror, bypassing the Jetstream consumer
-- where the privacy gates live — so every row here is written already conformant to them, and
-- the preview shows what ingest would actually have kept:
--
--   * `surname` is one token (the gate rejects a given name);
--   * `birth_year` is always <= 1900;
--   * a row with NO birth year carries a country and NOTHING finer — that is the §2.3
--     precision ladder, not an oversight;
--   * coordinates are 2dp.
--
-- The mix is deliberately awkward, so the preview shows the hard cases rather than a tidy one:
-- more than eight distinct counties (exercises the fold into the reserved "Other" slot), US
-- diaspora alongside Irish counties, country-only rows, and men with no locality at all.

BEGIN;

-- The clades to populate: era-gated (TMRCA under the 1500 ybp ceiling) and deep enough in kits
-- to show real branching. Add or swap names here to preview a different part of the tree.
CREATE TEMP TABLE preview_root(name TEXT) ON COMMIT DROP;
INSERT INTO preview_root(name) VALUES ('R-DF85'), ('R-S764'), ('R-Z3000');

-- 40 slots, cycled over the kits in a stable order. Proportions are roughly an Irish surname
-- project's: a long Munster tail, a Scottish and English minority, a US diaspora, and a real
-- fraction with nothing recorded.
CREATE TEMP TABLE preview_mix(
  slot INT, surname TEXT, place TEXT, country TEXT, byear INT, lat FLOAT8, lon FLOAT8
) ON COMMIT DROP;
INSERT INTO preview_mix VALUES
  ( 0,'Sullivan','Kenmare, Co. Kerry, Ireland','Ireland',1812, 51.88, -9.58),
  ( 1,'McCarthy','Bandon, Co. Cork, Ireland','Ireland',1799, 51.75, -8.74),
  ( 2,'Donovan','Skibbereen, Co. Cork, Ireland','Ireland',1855, 51.55, -9.26),
  ( 3,'Kane','Creegh South, Co. Clare, Ireland','Ireland',1830, 52.75, -9.43),
  ( 4,'Murphy','Cork, Co. Cork, Ireland','Ireland',1841, 51.90, -8.47),
  ( 5,'Sullivan','Cahersiveen, Co. Kerry, Ireland','Ireland',1826, 51.95, -10.22),
  ( 6,'Brien','Ennis, Co. Clare, Ireland','Ireland',1808, 52.84, -8.99),
  ( 7,'Walsh','Clonmel, Co. Tipperary, Ireland','Ireland',1863, 52.35, -7.70),
  ( 8,'Ryan','Nenagh, Co. Tipperary, Ireland','Ireland',1834, 52.86, -8.20),
  ( 9,'McCarthy','Macroom, Co. Cork, Ireland','Ireland',1798, 51.90, -8.96),
  (10,'Connor','Galway, Co. Galway, Ireland','Ireland',1849, 53.27, -9.05),
  (11,'Fitzgerald','Dungarvan, Co. Waterford, Ireland','Ireland',1817, 52.09, -7.62),
  (12,'Kelly','Westport, Co. Mayo, Ireland','Ireland',1852, 53.80, -9.52),
  (13,'Power','Kilkenny, Co. Kilkenny, Ireland','Ireland',1805, 52.65, -7.25),
  (14,'Barry','Wexford, Co. Wexford, Ireland','Ireland',1868, 52.34, -6.46),
  (15,'Sullivan','Limerick, Co. Limerick, Ireland','Ireland',1821, 52.66, -8.63),
  (16,'Doyle','Adare, Co. Limerick, Ireland','Ireland',1839, 52.56, -8.79),
  (17,'Cronin','Killarney, Co. Kerry, Ireland','Ireland',1811, 52.06, -9.51),
  -- Scotland and England: the constituent countries must stay distinct from "United Kingdom".
  (18,'Kelly','Moulin, Pitlochry PH16 5EP, UK','Scotland',1820, 56.71, -3.74),
  (19,'MacLeod','Stornoway, Isle of Lewis, Scotland','Scotland',1844, 58.21, -6.39),
  (20,'Campbell','Oban, Argyll, Scotland','Scotland',1802, 56.41, -5.47),
  (21,'Hughes','Chelmsford, England, UK','England',1858, 51.73,  0.48),
  (22,'Ward','Liverpool, England, UK','England',1836, 53.41, -2.98),
  -- Diaspora, as recorded. No inference is made about where these lines "really" came from.
  (23,'Brazil','Pickens County, SC, USA','United States',1801, 34.88, -82.71),
  (24,'ODonnell','Amelia County, VA 23002, USA','United States',1788, 37.34, -77.98),
  (25,'Sullivan','Boston, MA 02108, USA','United States',1847, 42.36, -71.06),
  (26,'Murphy','Wythe County, VA, USA','United States',1793, 36.92, -81.08),
  (27,'Kane','Hinds County, MS, USA','United States',1866, 32.26, -90.36),
  (28,'Walsh','Toronto, ON, Canada','Canada',1859, 43.65, -79.38),
  (29,'Ryan','Sydney, NSW, Australia','Australia',1854,-33.87, 151.21),
  -- Country only: no birth year, so the precision ladder withholds place and coordinate.
  (30,'Collins',NULL,'Ireland',NULL,NULL,NULL),
  (31,'Nolan',NULL,'Ireland',NULL,NULL,NULL),
  (32,'Moore',NULL,'Scotland',NULL,NULL,NULL),
  (33,'Grant',NULL,'United States',NULL,NULL,NULL),
  -- Published, but with no locality at all — must draw as its own visible slice.
  (34,'Quinn',NULL,NULL,NULL,NULL,NULL),
  (35,'Byrne',NULL,NULL,NULL,NULL,NULL),
  (36,'Flynn',NULL,NULL,NULL,NULL,NULL),
  -- A few more counties, pushing the distinct count past the eight palette slots.
  (37,'Brennan','Sligo, Co. Sligo, Ireland','Ireland',1828, 54.27, -8.48),
  (38,'Duffy','Letterkenny, Co. Donegal, Ireland','Ireland',1815, 54.95, -7.73),
  (39,'Reilly','Cavan, Co. Cavan, Ireland','Ireland',1871, 53.99, -7.36);

-- Every FTDNA-identified placed Y sample under the preview roots, in a stable order so a re-run
-- assigns the same locality to the same kit.
WITH RECURSIVE sub AS (
  SELECT h.id, h.name AS root
  FROM tree.haplogroup h JOIN preview_root p ON p.name = h.name
  WHERE h.haplogroup_type = 'Y_DNA' AND h.valid_until IS NULL
  UNION ALL
  SELECT r.child_haplogroup_id, s.root
  FROM sub s JOIN tree.haplogroup_relationship r
    ON r.parent_haplogroup_id = s.id AND r.valid_until IS NULL
),
kits AS (
  SELECT DISTINCT ON (i.value) i.value AS kit
  FROM tree.haplogroup_sample hs
  JOIN sub ON sub.id = hs.haplogroup_id
  JOIN core.biosample b ON b.sample_guid = hs.sample_guid AND b.deleted = false
  JOIN core.biosample_identifier i ON i.sample_guid = hs.sample_guid AND i.namespace = 'FTDNA'
  WHERE hs.dna_type = 'Y_DNA' AND hs.status IN ('PLACED','CURATED')
  ORDER BY i.value
),
numbered AS (SELECT kit, (row_number() OVER (ORDER BY kit) - 1) AS rn FROM kits)
INSERT INTO fed.ancestral_origin
  (did, rkey, at_uri, external_ids, lineage, surname, origin_place, origin_country,
   birth_year, death_year, geocoord, record_created_at, time_us)
SELECT
  'did:plc:preview',
  'kit-' || n.kit,
  'at://did:plc:preview/com.decodingus.atmosphere.ancestralOrigin/kit-' || n.kit,
  jsonb_build_array(jsonb_build_object('namespace','FTDNA','value', n.kit)),
  'Y_DNA',
  m.surname,
  m.place,
  m.country,
  m.byear,
  -- A death year only where a birth year established the ancestor at all.
  CASE WHEN m.byear IS NOT NULL THEN m.byear + 55 + (n.rn % 20) END,
  CASE WHEN m.lat IS NOT NULL AND m.lon IS NOT NULL
       THEN ST_SetSRID(ST_MakePoint(round(m.lon::numeric, 2), round(m.lat::numeric, 2)), 4326) END,
  now(),
  1
FROM numbered n
JOIN preview_mix m ON m.slot = n.rn % 40
ON CONFLICT (did, rkey) DO NOTHING;

COMMIT;

-- What landed, and at what coverage.
SELECT count(*) AS preview_rows,
       count(*) FILTER (WHERE origin_place IS NOT NULL)   AS with_place,
       count(*) FILTER (WHERE origin_country IS NOT NULL) AS with_country,
       count(*) FILTER (WHERE geocoord IS NOT NULL)       AS with_geocoord,
       count(*) FILTER (WHERE birth_year IS NULL)         AS no_birth_year
FROM fed.ancestral_origin WHERE did = 'did:plc:preview';
