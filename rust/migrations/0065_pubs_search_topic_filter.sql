-- Precision filter for OpenAlex discovery.
--
-- Sorting discovery by publication_date (mig 0063) dropped the implicit precision
-- that relevance ranking used to give, so broad keyword queries began surfacing
-- health and non-human work (mito-disease, dog/cattle/plant aDNA, microbiome).
-- Human anthropological genetics concentrates in a small set of OpenAlex
-- primary_topics (empirically ~82% of our corpus is T10751 "Forensic and Genetic
-- Research"), so an optional per-config topic whitelist — appended to the date
-- filter — restores precision without a code change to tune.

ALTER TABLE pubs.publication_search_config ADD COLUMN topic_filter TEXT;

-- Default: the tight human-anthropological-genetics topic set. Excludes Health
-- Sciences (no good paper falls there) and the noisy non-human topics (livestock,
-- conservation/wildlife population structure T10012, plant/microbe). Curators can
-- widen/narrow this per config. Runs after the 0063 seed, so greenfield configs
-- get it too.
UPDATE pubs.publication_search_config
   SET topic_filter = 'primary_topic.id:T10751|T10421|T10087|T10992|T10015|T12232'
 WHERE topic_filter IS NULL;
